package com.foundations.cluster.manager;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Graceful worker drain/evacuation coordinator; 0.0.6a adds batch consolidation fencing. */
final class WorkerEvacuationManager implements AutoCloseable {
    record StopStatus(boolean active,long startedAtMillis,long deadlineMillis,int initialPlayers,int remainingPlayers,int migratedPlayers,int remainingRegions,String message) {}
    private static final class Operation {
        final String workerId;
        final long startedAtMillis;
        final long initialDeadlineMillis;
        final long migrationDeadlineMillis;
        volatile long deadlineMillis;
        final int initialPlayers;
        final Set<String> stopDrainedLayers;
        final boolean restartAfterStop;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile String message = "Starting graceful evacuation";
        Operation(String workerId,long startedAtMillis,long initialDeadlineMillis,long migrationDeadlineMillis,int initialPlayers,Set<String> stopDrainedLayers,boolean restartAfterStop){
            this.workerId=workerId;this.startedAtMillis=startedAtMillis;this.initialDeadlineMillis=initialDeadlineMillis;this.migrationDeadlineMillis=migrationDeadlineMillis;this.deadlineMillis=initialDeadlineMillis;this.initialPlayers=initialPlayers;this.stopDrainedLayers=stopDrainedLayers;
            this.restartAfterStop=restartAfterStop;
        }
    }

    private final ManagerConfig cfg;
    private final WorkerManager workers;
    private final SessionManager sessions;
    private final HandoffManager handoffs;
    private final LayerPlanner layers;
    private final RegionAuthorityManager regions;
    private final ClusterLog log;
    private final ConcurrentHashMap<String,Operation> operations = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    WorkerEvacuationManager(ManagerConfig cfg, WorkerManager workers, SessionManager sessions, HandoffManager handoffs,
                            LayerPlanner layers, RegionAuthorityManager regions, ClusterLog log) {
        this.cfg=cfg;this.workers=workers;this.sessions=sessions;this.handoffs=handoffs;this.layers=layers;this.regions=regions;this.log=log;
    }

    StopStatus begin(String workerId) {
        return beginBatch(List.of(workerId)).get(0);
    }

    /**
     * R10: Restart is a graceful lifecycle operation, not a raw JVM stop/start. If the
     * worker is currently online we evacuate physical anchors, simulation targets, and
     * Region Authority first. Offline workers are simply started.
     */
    StopStatus restart(String workerId) {
        WorkerRecord w=require(workerId);
        if(!w.managed)throw new IllegalStateException(workerId+" is externally managed and cannot be restarted by this manager.");
        if(!workers.processAlive(workerId)) {
            workers.restart(workerId);
            w.gracefulStopMessage="Offline worker restart requested";
            return status(workerId);
        }
        return beginInternal(List.of(workerId),true).get(0);
    }

    int restartAllManagedRolling() {
        List<String> ids=workers.all().stream().filter(w->w.managed).sorted(Comparator.comparing(w->w.id)).map(w->w.id).toList();
        executor.submit(()->{
            log.info("Graceful rolling restart requested for "+ids.size()+" managed worker(s)");
            for(String id:ids){
                try {
                    restart(id);
                    waitForRestartReady(id);
                } catch(Exception e){ log.warn("Graceful rolling restart failed for "+id+": "+e.getMessage()); }
            }
            log.info("Graceful rolling restart sequence complete");
        });
        return ids.size();
    }

    /**
     * Marks every worker draining before any evacuation thread is started. This is important
     * for autoscaling consolidation: players from worker-003 must not be moved to worker-002
     * when worker-002 is also scheduled to disappear in the same consolidation plan.
     */
    synchronized List<StopStatus> beginBatch(Collection<String> workerIds) {
        return beginInternal(workerIds,false);
    }

    private synchronized List<StopStatus> beginInternal(Collection<String> workerIds, boolean restartAfterStop) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String id : workerIds) if (id != null && !id.isBlank()) ids.add(id);
        if (ids.isEmpty()) throw new IllegalArgumentException("At least one worker is required for graceful evacuation.");

        // Validate the complete batch before mutating routing state.
        for (String workerId : ids) {
            WorkerRecord w = require(workerId);
            if (!w.managed) throw new IllegalStateException(workerId + " is externally managed and cannot be gracefully stopped by this manager.");
            if (!workers.processAlive(workerId)) throw new IllegalStateException(workerId + " is not running.");
            if (operations.containsKey(workerId)) throw new IllegalStateException("Graceful stop is already active for " + workerId + ".");
        }

        long graceSeconds = Math.max(1, cfg.getLong("worker.evacuationGraceSeconds", 10));
        long migrationTimeoutSeconds = Math.max(graceSeconds, cfg.getLong("worker.evacuationMigrationTimeoutSeconds", 60));
        long now = System.currentTimeMillis();
        List<Operation> created = new ArrayList<>();
        for (String workerId : ids) {
            WorkerRecord w = require(workerId);
            Set<String> changed = new LinkedHashSet<>();
            for (LayerRecord l : layers.allLayers()) {
                if (workerId.equals(l.workerId) && l.state != LayerState.RETIRED && l.state != LayerState.DRAINING) changed.add(l.id);
            }
            Operation op = new Operation(workerId, now, now + graceSeconds * 1000L, now + migrationTimeoutSeconds * 1000L, Math.max(w.players, sessions.anchorCountForWorker(workerId)), changed, restartAfterStop);
            operations.put(workerId, op);
            created.add(op);
        }

        // Fence routing for the whole batch first, then start movement.
        for (Operation op : created) {
            WorkerRecord w = require(op.workerId);
            w.gracefulStopPending = true;
            w.gracefulStopStartedAtMillis = now;
            w.gracefulStopDeadlineMillis = op.deadlineMillis;
            w.gracefulStopInitialPlayers = op.initialPlayers;
            w.gracefulStopMigratedPlayers = 0;
            w.gracefulStopMessage = op.message;
            workers.drainForStop(op.workerId);
            layers.drainWorkerLayers(op.workerId);
        }
        for (Operation op : created) {
            log.info((op.restartAfterStop?"Graceful Restart":"Graceful Stop")+" requested for " + op.workerId + " grace=" + graceSeconds + "s migrationTimeout="+migrationTimeoutSeconds+"s players=" + op.initialPlayers
                    + (created.size() > 1 ? " batch=" + created.size() : ""));
            executor.submit(() -> run(op));
        }

        List<StopStatus> result = new ArrayList<>();
        for (Operation op : created) result.add(status(op.workerId));
        return result;
    }

    StopStatus cancel(String workerId) {
        Operation op=operations.get(workerId);
        if(op==null)throw new IllegalStateException("No graceful stop is active for "+workerId);
        op.cancelled.set(true);
        op.message="Graceful stop cancelled";
        return status(workerId);
    }

    void forceStop(String workerId) {
        Operation op=operations.remove(workerId);
        if(op!=null)op.cancelled.set(true);
        WorkerRecord w=require(workerId);
        w.gracefulStopPending=false;w.gracefulStopMessage="Force Stop requested";
        workers.kill(workerId);
    }

    StopStatus status(String workerId) {
        WorkerRecord w=require(workerId);Operation op=operations.get(workerId);
        if(op==null)return new StopStatus(false,w.gracefulStopStartedAtMillis,w.gracefulStopDeadlineMillis,w.gracefulStopInitialPlayers,Math.max(w.players,sessions.anchorCountForWorker(workerId)),w.gracefulStopMigratedPlayers,regions.activeRegionCountForWorker(workerId),w.gracefulStopMessage==null?"":w.gracefulStopMessage);
        int remaining=Math.max(w.players,sessions.anchorCountForWorker(workerId));
        return new StopStatus(true,op.startedAtMillis,op.deadlineMillis,op.initialPlayers,remaining,Math.max(0,op.initialPlayers-remaining),regions.activeRegionCountForWorker(workerId),op.message);
    }

    private void run(Operation op) {
        WorkerRecord w=require(op.workerId);
        try {
            int evacuatedRegions=0;
            try { evacuatedRegions=regions.evacuateWorker(op.workerId,"graceful stop 0.0.6a"); }
            catch(Exception e){log.warn("Graceful Stop region evacuation warning for "+op.workerId+": "+e.getMessage());}
            if(evacuatedRegions>0)log.info("Graceful Stop evacuated "+evacuatedRegions+" authority region(s) from "+op.workerId);

            while(!op.cancelled.get()) {
                requestPlayerMoves(op);
                try { regions.evacuateWorker(op.workerId,"graceful stop 0.0.6a retry"); } catch(Exception ignored) {}
                int anchors=sessions.anchorCountForWorker(op.workerId);
                int simulations=sessions.simulationCountForWorker(op.workerId);
                int heartbeatPlayers=Math.max(0,w.players);
                int remaining=Math.max(heartbeatPlayers,anchors);
                int remainingRegions=regions.activeRegionCountForWorker(op.workerId);
                w.gracefulStopMigratedPlayers=Math.max(0,op.initialPlayers-remaining);

                if(anchors==0&&simulations==0&&heartbeatPlayers==0&&remainingRegions==0) {
                    op.message="Evacuation complete; stopping worker";w.gracefulStopMessage=op.message;
                    completeStop(op,w);
                    return;
                }

                long now=System.currentTimeMillis();
                boolean migrationInFlight=handoffs.hasActiveEvacuationFrom(op.workerId);
                if(now>=op.initialDeadlineMillis){
                    if(migrationInFlight && now<op.migrationDeadlineMillis){
                        op.deadlineMillis=op.migrationDeadlineMillis;
                        w.gracefulStopDeadlineMillis=op.deadlineMillis;
                        long progressAge=Math.max(0L,now-handoffs.latestEvacuationProgressMillis(op.workerId));
                        op.message="Waiting for in-flight migration: "+remaining+" player(s), "+remainingRegions+" region(s); last progress "+(progressAge/1000)+"s ago";
                    } else if(now>=op.migrationDeadlineMillis || !migrationInFlight){
                        break;
                    }
                } else {
                    op.message="Evacuating players: "+remaining+" remaining; regions: "+remainingRegions;
                }
                w.gracefulStopMessage=op.message;
                Thread.sleep(100L);
            }

            if(op.cancelled.get()) {
                if (w.state==WorkerState.KILLED || w.state==WorkerState.STOPPING || w.state==WorkerState.OFFLINE || !workers.processAlive(op.workerId)) {
                    operations.remove(op.workerId,op);
                    w.gracefulStopPending=false;
                    return;
                }
                handoffs.abortEvacuationsFrom(op.workerId,"graceful stop cancelled");
                restoreRouting(op,w,"Graceful stop cancelled");
                return;
            }

            int remaining=Math.max(w.players,sessions.anchorCountForWorker(op.workerId));
            int remainingRegions=regions.activeRegionCountForWorker(op.workerId);
            if(remaining>0||sessions.simulationCountForWorker(op.workerId)>0||remainingRegions>0) {
                handoffs.abortEvacuationsFrom(op.workerId,"graceful evacuation migration timeout reached");
                restoreRouting(op,w,"Stop aborted: "+remaining+" player(s), "+remainingRegions+" authority region(s) remained after migration-aware evacuation timeout");
                log.warn("Graceful Stop ABORTED for "+op.workerId+" after migration-aware deadline; remainingPlayers="+remaining+" remainingRegions="+remainingRegions+". Use Force Stop only if disconnecting them is acceptable.");
                return;
            }

            completeStop(op,w);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            restoreRouting(op,w,"Graceful stop interrupted");
        } catch(Exception e) {
            restoreRouting(op,w,"Stop aborted: "+e.getMessage());
            log.warn("Graceful Stop failed for "+op.workerId+": "+e.getMessage());
        }
    }

    private void completeStop(Operation op, WorkerRecord w) {
        operations.remove(op.workerId,op);
        w.gracefulStopPending=false;
        workers.stop(op.workerId);
        log.info((op.restartAfterStop?"Graceful Restart":"Graceful Stop")+" evacuation complete for "+op.workerId+" migrated="+w.gracefulStopMigratedPlayers+"; Minecraft stop sent");
        if(op.restartAfterStop) executor.submit(()->restartAfterStopped(op));
    }

    private void restartAfterStopped(Operation op) {
        long deadline=System.currentTimeMillis()+Math.max(15_000L,(cfg.getLong("worker.stopGraceSeconds",30)+15L)*1000L);
        try {
            while(System.currentTimeMillis()<deadline && workers.processAlive(op.workerId)) Thread.sleep(100L);
            if(workers.processAlive(op.workerId)) {
                log.warn("Graceful Restart could not relaunch "+op.workerId+": old JVM did not stop before restart deadline");
                return;
            }
            WorkerRecord w=require(op.workerId);
            // WorkerManager consumes the saved pre-evacuation drain policy on launch.
            // Explicit operator drain/maintenance changes made since then take precedence.
            w.gracefulStopMessage="Evacuation complete; restarting worker";
            workers.restart(op.workerId);
            log.info("Graceful Restart relaunch requested for "+op.workerId);
        } catch(InterruptedException e){ Thread.currentThread().interrupt(); }
        catch(Exception e){ log.warn("Graceful Restart relaunch failed for "+op.workerId+": "+e.getMessage()); }
    }

    private void waitForRestartReady(String workerId) throws InterruptedException {
        long timeout=Math.max(30_000L,cfg.getLong("worker.registrationTimeoutSeconds",180)*1000L);
        long deadline=System.currentTimeMillis()+timeout;
        while(System.currentTimeMillis()<deadline){
            WorkerRecord w=require(workerId);
            if(!w.gracefulStopPending && workers.managerControlled(workerId) && w.ready && (w.state==WorkerState.READY||w.state==WorkerState.ACTIVE||w.state==WorkerState.MAINTENANCE||w.state==WorkerState.DRAINED)) return;
            Thread.sleep(250L);
        }
        throw new IllegalStateException("Timed out waiting for "+workerId+" to complete graceful restart");
    }

    private void requestPlayerMoves(Operation op) {
        List<SessionRecord> current=new ArrayList<>(sessions.all());
        for(SessionRecord s:current) {
            try {
                if(op.workerId.equals(s.anchorWorkerId)) {
                    String preferred="";
                    if(s.simulationWorkerId!=null&&!s.simulationWorkerId.isBlank()&&!op.workerId.equals(s.simulationWorkerId)) {
                        WorkerRecord sim=workers.get(s.simulationWorkerId);if(sim!=null&&sim.routableForNewSession())preferred=sim.id;
                    }
                    handoffs.requestEvacuation(s.playerId,preferred,"worker graceful stop "+op.workerId);
                } else if(op.workerId.equals(s.simulationWorkerId)) {
                    handoffs.request(s.playerId,"","simulation worker graceful stop "+op.workerId);
                }
            } catch(IllegalStateException | IllegalArgumentException e) {
                // Expected while an earlier request for this player is already in flight or no destination is ready yet.
            }
        }
    }

    private void restoreRouting(Operation op,WorkerRecord w,String message) {
        operations.remove(op.workerId,op);
        w.gracefulStopPending=false;w.gracefulStopMessage=message;
        workers.releaseStopDrain(op.workerId);
        for(String layerId:op.stopDrainedLayers) {
            try { LayerRecord l=layers.allLayers().stream().filter(x->x.id.equals(layerId)).findFirst().orElse(null);if(l!=null&&l.state==LayerState.DRAINING)layers.undrain(layerId); }
            catch(Exception ignored){}
        }
    }

    private WorkerRecord require(String id){WorkerRecord w=workers.get(id);if(w==null)throw new IllegalArgumentException("Unknown worker: "+id);return w;}
    @Override public void close(){for(Operation op:operations.values())op.cancelled.set(true);executor.shutdownNow();}
}
