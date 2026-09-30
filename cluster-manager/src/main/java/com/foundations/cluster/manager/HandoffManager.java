package com.foundations.cluster.manager;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistent-session transition coordinator.
 *
 * Normal movement keeps the player's Velocity backend anchored and moves only
 * the logical simulation target. R8 preserves the explicit evacuation handoff:
 * when an anchor worker is intentionally stopped, the same two-phase snapshot /
 * preload path culminates in a real Velocity backend switch so the worker can
 * reach zero players before shutdown.
 */
final class HandoffManager implements AutoCloseable {
    private final ManagerConfig cfg;
    private final WorkerManager workers;
    private final SessionManager sessions;
    private final LayerPlanner layers;
    private final PlayerStateManager playerStates;
    private final PlayerProfileManager playerProfiles;
    private final ClusterLog log;
    private final ConcurrentHashMap<String,HandoffRecord> records = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"foundations-handoff");t.setDaemon(true);return t;});

    HandoffManager(ManagerConfig cfg, WorkerManager workers, SessionManager sessions, LayerPlanner layers,
                   PlayerStateManager playerStates, PlayerProfileManager playerProfiles, ClusterLog log) {
        this.cfg=cfg;this.workers=workers;this.sessions=sessions;this.layers=layers;this.playerStates=playerStates;this.playerProfiles=playerProfiles;this.log=log;
        scheduler.scheduleAtFixedRate(this::tickSafe,1,1,TimeUnit.SECONDS);
        log.info("Persistent Session coordinator initialized: enabled="+enabled()+" mode="+mode()+" timeout="+timeoutSeconds()+"s evacuationProxySwitch=true legacyVelocitySwitch="+legacyVelocityBackendSwitch());
    }

    boolean enabled(){return cfg.getBoolean("handoff.enabled",true);}
    String mode(){return cfg.get("handoff.mode","persistent-session").trim().toLowerCase(Locale.ROOT);}
    boolean persistentSessionMode(){return !mode().equals("velocity-switch");}
    boolean legacyVelocityBackendSwitch(){return cfg.getBoolean("handoff.legacyVelocityBackendSwitch",false);}
    int timeoutSeconds(){return Math.max(5,cfg.getInt("handoff.timeoutSeconds",20));}
    Collection<HandoffRecord> all(){return new ArrayList<>(records.values());}
    HandoffRecord get(String id){return records.get(id);}

    synchronized HandoffRecord request(String playerId, String destinationWorkerId, String reason) {
        return requestInternal(playerId,destinationWorkerId,reason,false);
    }

    synchronized HandoffRecord requestEvacuation(String playerId, String destinationWorkerId, String reason) {
        return requestInternal(playerId,destinationWorkerId,reason,true);
    }

    /**
     * Internal logical simulation move. This deliberately preserves the
     * client-facing Velocity anchor. Do not expose this as a player transfer.
     */
    synchronized HandoffRecord requestLiveHandoff(String playerId, String destinationWorkerId, String reason) {
        return requestInternal(playerId,destinationWorkerId,reason,false);
    }

    /**
     * Explicit operator-requested player migration. This is a real backend move:
     * source snapshot -> destination preload -> final checkpoint -> Velocity
     * backend connection switch -> anchor/simulation commit to destination.
     */
    synchronized HandoffRecord requestManualBackendTransfer(String playerId, String destinationWorkerId, String reason) {
        return requestInternal(playerId,destinationWorkerId,reason,true);
    }

    private HandoffRecord requestInternal(String playerId,String destinationWorkerId,String reason,boolean proxySwitchRequired) {
        if(!enabled()) throw new IllegalStateException("persistent-session transitions are disabled");
        SessionRecord s=sessions.findByPlayer(playerId);
        if(s==null) throw new IllegalArgumentException("No active session for player: "+playerId);
        String anchorId=(s.anchorWorkerId==null||s.anchorWorkerId.isBlank())?s.workerId:s.anchorWorkerId;
        for(HandoffRecord r:records.values()) {
            if(!r.playerId.equalsIgnoreCase(playerId)||terminal(r.state))continue;
            if(proxySwitchRequired) {
                WorkerRecord existingDest=workers.get(r.destinationWorkerId);
                if(r.destinationWorkerId.equals(anchorId)||existingDest==null||!existingDest.routableForNewSession()){
                    abort(r.id,"replaced by graceful-stop evacuation");
                    continue;
                }
                r.proxySwitchRequired=true;
            }
            return r;
        }


        String currentSimulation=(s.simulationWorkerId==null||s.simulationWorkerId.isBlank())?anchorId:s.simulationWorkerId;
        WorkerRecord source=workers.get(anchorId);
        if(source==null || !source.usableForExistingSession()) throw new IllegalStateException("session anchor worker is unavailable: "+anchorId);
        WorkerRecord dest = chooseDestination(s,destinationWorkerId,proxySwitchRequired);
        if(dest==null) throw new IllegalStateException("no alternate READY/ACTIVE worker available");
        if(!proxySwitchRequired && dest.id.equals(currentSimulation)) throw new IllegalStateException("destination is already the current simulation target");
        if(proxySwitchRequired && dest.id.equals(anchorId)) throw new IllegalStateException("evacuation destination cannot be the anchor worker being evacuated");

        if(proxySwitchRequired)for(HandoffRecord prior:records.values()){
            long remaining=30000L-(System.currentTimeMillis()-prior.updatedAtMillis);
            if(remaining>0&&prior.proxySwitchRequired&&prior.playerId.equalsIgnoreCase(playerId)&&prior.destinationWorkerId.equals(dest.id)&&(prior.state==HandoffState.ABORTED||prior.state==HandoffState.FAILED))
                throw new IllegalStateException("Previous transfer failed; retry this destination in "+((remaining+999)/1000)+"s. Inspect Diagnostics → Physical Transfer Timeline.");
        }
        if(source.failedWrites>0||source.pendingWrites>10000)throw new IllegalStateException("Source has unconfirmed world writes; inspect Diagnostics → Save Health");
        LayerCellKey cell=new LayerCellKey(s.worldId,s.dimensionId,s.cellX,s.cellZ);
        LayerRecord targetLayer=layers.layersInCell(cell).stream()
                .filter(l->dest.id.equals(l.workerId) && l.state!=LayerState.RETIRED && l.state!=LayerState.DRAINING && l.state!=LayerState.FROZEN)
                .findFirst().orElseGet(()->layers.createLayer(cell,dest.id,proxySwitchRequired?"worker-evacuation-target":"persistent-session-target"));

        String id=String.format(Locale.ROOT,"handoff-%08d",seq.incrementAndGet());
        HandoffRecord r=new HandoffRecord(id,s.sessionId,s.playerId,anchorId,dest.id,targetLayer.id,reason,proxySwitchRequired);
        records.put(id,r);
        log.info(transitionLabel(proxySwitchRequired,r.reason)+" requested "+id+" player="+s.playerId+" anchor="+anchorId+" simulation="+currentSimulation+" -> "+dest.id+" layer="+targetLayer.id+" reason="+r.reason);
        return r;
    }

    private WorkerRecord chooseDestination(SessionRecord s,String requested,boolean evacuation){
        cfg.lifecycle.requireRunning();
        String anchor=(s.anchorWorkerId==null||s.anchorWorkerId.isBlank())?s.workerId:s.anchorWorkerId;
        String current=(s.simulationWorkerId==null||s.simulationWorkerId.isBlank())?anchor:s.simulationWorkerId;
        if(requested!=null&&!requested.isBlank()){
            WorkerRecord w=workers.get(requested);
            if(w==null||!workers.migrationEligible(w))throw new IllegalStateException("destination worker is not routable: "+requested);
            if(evacuation&&w.id.equals(anchor))throw new IllegalStateException("destination is the worker being evacuated: "+requested);
            return w;
        }
        Comparator<WorkerRecord> order;
        if(evacuation){
            order=Comparator.comparingInt((WorkerRecord w)->w.players+pendingEvacuationsTo(w.id))
                    .thenComparingDouble(w->w.mspt<=0?10:w.mspt);
        }else{
            order=Comparator.comparingDouble((WorkerRecord w)->w.mspt<=0?10:w.mspt).thenComparingInt(w->w.players);
        }
        return workers.all().stream().filter(workers::migrationEligible)
                .filter(w->evacuation?!w.id.equals(anchor):!w.id.equals(current))
                .min(order).orElse(null);
    }

    private int pendingEvacuationsTo(String workerId){
        int n=0;
        for(HandoffRecord r:records.values()) if(r.proxySwitchRequired&&!terminal(r.state)&&workerId.equals(r.destinationWorkerId)) n++;
        return n;
    }

    /** Backwards-compatible single command embedded in an agent heartbeat response. */
    String controlJsonForWorker(String workerId){
        for(HandoffRecord r:pendingCommandsForWorker(workerId)){
            if(r.state==HandoffState.PREPARING_SOURCE)return commandJson("SNAPSHOT_SOURCE_INITIAL",r,"");
            if(r.state==HandoffState.PREPARING_DESTINATION)return commandJson("PRELOAD_DESTINATION",r,r.snapshotJson);
            if(r.state==HandoffState.FINALIZING_SOURCE)return commandJson("SNAPSHOT_SOURCE_FINAL",r,"");
        }
        return "{\"handoffAction\":\"NONE\"}";
    }

    /**
     * R7.R6 batched control token. A worker can now prepare all evacuation
     * handoffs in one heartbeat instead of one player per heartbeat.
     */
    String controlTokenForWorker(String workerId){
        int max=Math.max(1,cfg.getInt("handoff.maxCommandsPerHeartbeat",128));
        StringBuilder out=new StringBuilder();int n=0;
        for(HandoffRecord r:pendingCommandsForWorker(workerId)){
            if(n++>=max)break;
            String action=r.state==HandoffState.PREPARING_SOURCE?"SNAPSHOT_SOURCE_INITIAL":r.state==HandoffState.PREPARING_DESTINATION?"PRELOAD_DESTINATION":"SNAPSHOT_SOURCE_FINAL";
            String snapshot=r.state==HandoffState.PREPARING_DESTINATION?r.snapshotJson:"";
            if(out.length()>0)out.append(';');
            out.append(b64(action)).append(',').append(b64(r.id)).append(',').append(b64(r.playerId)).append(',')
                    .append(b64(r.sourceWorkerId)).append(',').append(b64(r.destinationWorkerId)).append(',').append(b64(snapshot));
        }
        return out.toString();
    }

    private List<HandoffRecord> pendingCommandsForWorker(String workerId){
        return records.values().stream().filter(r->!terminal(r.state)).filter(r->
                (r.state==HandoffState.PREPARING_SOURCE&&r.sourceWorkerId.equals(workerId))||
                (r.state==HandoffState.PREPARING_DESTINATION&&r.destinationWorkerId.equals(workerId))||
                (r.state==HandoffState.FINALIZING_SOURCE&&r.sourceWorkerId.equals(workerId)))
                .sorted(Comparator.comparingLong(x->x.createdAtMillis)).toList();
    }

    private String commandJson(String action,HandoffRecord r,String snapshot){
        return "{\"handoffAction\":"+Json.q(action)+",\"handoffId\":"+Json.q(r.id)+",\"handoffPlayerId\":"+Json.q(r.playerId)+",\"handoffSourceWorkerId\":"+Json.q(r.sourceWorkerId)+",\"handoffDestinationWorkerId\":"+Json.q(r.destinationWorkerId)+",\"handoffSnapshot\":"+Json.q(snapshot)+"}";
    }

    synchronized HandoffRecord sourceSnapshot(String id,String sourceWorkerId,String snapshotJson){
        HandoffRecord r=require(id);
        if(!r.sourceWorkerId.equals(sourceWorkerId))throw new SecurityException("handoff source mismatch");
        if(r.state!=HandoffState.PREPARING_SOURCE && r.state!=HandoffState.FINALIZING_SOURCE)return r;
        r.snapshotJson=snapshotJson==null?"":snapshotJson;
        long now=System.currentTimeMillis();
        r.sourcePreparedAtMillis=now;r.updatedAtMillis=now;
        if(!r.snapshotJson.isBlank()) playerStates.updateFromSnapshotJson(r.snapshotJson,sourceWorkerId);
        String uuid=jsonString(r.snapshotJson,"uuid"),name=jsonString(r.snapshotJson,"name");
        if(!uuid.isBlank())playerProfiles.capture(sourceWorkerId,uuid,name);
        if(r.state==HandoffState.PREPARING_SOURCE){
            r.state=HandoffState.PREPARING_DESTINATION;
            log.info(transitionLabel(r.proxySwitchRequired,r.reason)+" source snapshot "+id+" player="+r.playerId+" -> preload "+r.destinationWorkerId);
        }else if(persistentSessionMode()&&!r.proxySwitchRequired){
            sessions.commitSimulationTarget(r.sessionId,r.destinationWorkerId,r.targetLayerId);
            r.state=HandoffState.COMMITTED;r.committedAtMillis=now;r.updatedAtMillis=now;
            log.info("Invisible transition COMMITTED "+id+" player="+r.playerId+" anchor="+r.sourceWorkerId+" simulationTarget="+r.destinationWorkerId+" (no Velocity backend switch)");
        }else{
            if(!uuid.isBlank())playerProfiles.prepare(r.destinationWorkerId,uuid,name);
            r.state=HandoffState.PROXY_READY;
            log.info(transitionLabel(r.proxySwitchRequired,r.reason)+" final source snapshot "+id+" player="+r.playerId+"; Velocity switch armed");
        }
        return r;
    }

    synchronized HandoffRecord destinationReady(String id,String destinationWorkerId){
        HandoffRecord r=require(id);
        if(!r.destinationWorkerId.equals(destinationWorkerId))throw new SecurityException("handoff destination mismatch");
        if(r.state==HandoffState.FINALIZING_SOURCE||r.state==HandoffState.PROXY_READY||r.state==HandoffState.COMMITTING||r.state==HandoffState.COMMITTED)return r;
        if(r.state!=HandoffState.PREPARING_DESTINATION)throw new IllegalStateException("handoff is not preparing destination");
        r.state=HandoffState.FINALIZING_SOURCE;r.destinationReadyAtMillis=System.currentTimeMillis();r.updatedAtMillis=r.destinationReadyAtMillis;
        log.info(transitionLabel(r.proxySwitchRequired,r.reason)+" destination ready "+id+" player="+r.playerId+" destination="+r.destinationWorkerId+"; requesting final anchor snapshot");
        return r;
    }

    synchronized HandoffRecord markCommitting(String id){
        HandoffRecord r=require(id);
        if(persistentSessionMode()&&!r.proxySwitchRequired) throw new IllegalStateException("Velocity backend switching is disabled for normal persistent-session movement");
        if(r.state!=HandoffState.PROXY_READY)throw new IllegalStateException("handoff final source state is not ready for proxy switch");
        r.state=HandoffState.COMMITTING;r.updatedAtMillis=System.currentTimeMillis();return r;
    }

    synchronized HandoffRecord commit(String id,String proxyWorkerId){
        HandoffRecord r=require(id);
        if(persistentSessionMode()&&!r.proxySwitchRequired) throw new IllegalStateException("Velocity backend switching is disabled for normal persistent-session movement");
        if(!(r.state==HandoffState.PROXY_READY||r.state==HandoffState.COMMITTING))throw new IllegalStateException("handoff is not ready to commit");
        if(proxyWorkerId!=null&&!proxyWorkerId.isBlank()&&!r.destinationWorkerId.equals(proxyWorkerId))throw new IllegalStateException("proxy committed unexpected destination "+proxyWorkerId);
        sessions.commitHandoff(r.sessionId,r.destinationWorkerId,r.targetLayerId);
        r.state=HandoffState.COMMITTED;r.committedAtMillis=System.currentTimeMillis();r.updatedAtMillis=r.committedAtMillis;
        log.info(transitionLabel(r.proxySwitchRequired,r.reason)+" COMMITTED "+id+" player="+r.playerId+" "+r.sourceWorkerId+" -> "+r.destinationWorkerId);
        return r;
    }

    synchronized HandoffRecord abort(String id,String error){
        HandoffRecord r=require(id);if(terminal(r.state))return r;r.state=HandoffState.ABORTED;r.error=error==null?"":error;r.updatedAtMillis=System.currentTimeMillis();
        log.warn("Transition ABORTED "+id+" player="+r.playerId+" reason="+r.error);return r;
    }

    synchronized int abortEvacuationsFrom(String sourceWorkerId,String error){
        int count=0;for(HandoffRecord r:records.values())if(r.proxySwitchRequired&&r.sourceWorkerId.equals(sourceWorkerId)&&!terminal(r.state)){abort(r.id,error);count++;}return count;
    }

    boolean hasActiveEvacuationFrom(String sourceWorkerId){
        for(HandoffRecord r:records.values()) if(r.proxySwitchRequired&&r.sourceWorkerId.equals(sourceWorkerId)&&!terminal(r.state)) return true;
        return false;
    }

    long latestEvacuationProgressMillis(String sourceWorkerId){
        long latest=0L;
        for(HandoffRecord r:records.values()) if(r.proxySwitchRequired&&r.sourceWorkerId.equals(sourceWorkerId)&&!terminal(r.state)) latest=Math.max(latest,r.updatedAtMillis);
        return latest;
    }

    List<HandoffRecord> readyForProxy(){
        boolean legacy=legacyVelocityBackendSwitch()||!persistentSessionMode();
        return records.values().stream().filter(r->r.state==HandoffState.PROXY_READY&&(r.proxySwitchRequired||legacy))
                .sorted(Comparator.comparingLong(r->r.createdAtMillis)).toList();
    }

    private void tickSafe(){try{tick();}catch(Throwable t){log.warn("Handoff tick failed: "+t.getMessage());}}
    private void tick(){
        if (cfg.lifecycle.shuttingDown()) return;
long now=System.currentTimeMillis(),ttl=timeoutSeconds()*1000L;for(HandoffRecord r:records.values()){
        if(terminal(r.state))continue;
        if(now-r.updatedAtMillis>ttl){r.state=HandoffState.FAILED;r.error="handoff phase timeout";r.updatedAtMillis=now;log.warn("Transition FAILED timeout "+r.id+" player="+r.playerId);continue;}
        WorkerRecord src=workers.get(r.sourceWorkerId),dst=workers.get(r.destinationWorkerId);
        if((src==null||!src.usableForExistingSession())&&r.state==HandoffState.PREPARING_SOURCE){r.state=HandoffState.FAILED;r.error="session anchor unavailable";r.updatedAtMillis=now;}
        if(dst==null||!dst.routableForNewSession()){r.state=HandoffState.FAILED;r.error="destination simulation worker unavailable";r.updatedAtMillis=now;}
    }}


    private static String transitionLabel(boolean proxySwitchRequired,String reason){
        if(!proxySwitchRequired)return "Invisible transition";
        String r=reason==null?"":reason.toLowerCase(Locale.ROOT);
        return r.contains("graceful stop")||r.contains("evacuat")?"Evacuation handoff":"Backend transfer handoff";
    }

    private HandoffRecord require(String id){HandoffRecord r=records.get(id);if(r==null)throw new IllegalArgumentException("Unknown handoff: "+id);return r;}
    private static boolean terminal(HandoffState s){return s==HandoffState.COMMITTED||s==HandoffState.ABORTED||s==HandoffState.FAILED;}
    private static String b64(String s){return Base64.getUrlEncoder().withoutPadding().encodeToString((s==null?"":s).getBytes(StandardCharsets.UTF_8));}
    private static String jsonString(String body,String key){if(body==null)return "";String n="\""+key+"\":\"";int i=body.indexOf(n);if(i<0)return "";i+=n.length();StringBuilder o=new StringBuilder();boolean e=false;for(;i<body.length();i++){char c=body.charAt(i);if(e){o.append(c);e=false;}else if(c=='\\')e=true;else if(c=='\"')break;else o.append(c);}return o.toString();}
    @Override public void close(){scheduler.shutdownNow();}
}
