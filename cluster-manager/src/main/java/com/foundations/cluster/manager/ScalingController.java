package com.foundations.cluster.manager;

import java.util.*;
import java.util.concurrent.*;

/**
 * 0.0.6a dynamic JVM autoscaler.
 *
 * Scale-up is capacity-first. Scale-down is consolidation-first: excess workers are
 * gracefully drained one at a time so players, layers and Region Authority move to
 * the remaining workers before the JVM is stopped. Memory budget is a hard guardrail.
 */
final class ScalingController implements AutoCloseable {
    private final ManagerConfig cfg;
    private final WorkerManager workers;
    private final WorkerEvacuationManager evacuations;
    private final ClusterLog log;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "foundations-scaling");
        t.setDaemon(true);
        return t;
    });

    private final AuditLog audit;
    private volatile long highSince;
    private volatile long lowSince;
    private volatile long lastScaleUp;
    private volatile long lastScaleDown;
    private volatile String recommendation = "";
    private volatile String recommendationAction = "";
    private volatile List<String> recommendationWorkers = List.of();
    private volatile long recommendationAt;
    private volatile String lastAction = "";
    private volatile int desiredWorkers;
    private volatile int observedLiveWorkers;
    private volatile int observedPlayers;
    private volatile int targetPlayersPerWorker;
    private volatile boolean observedStressed;

    ScalingController(ManagerConfig cfg, WorkerManager workers, WorkerEvacuationManager evacuations, ClusterLog log, AuditLog audit) {
        this.audit = audit;
        this.cfg = cfg;
        this.workers = workers;
        this.evacuations = evacuations;
        this.log = log;
        long seconds = Math.max(2, cfg.getLong("scaling.checkSeconds", 5));
        exec.scheduleAtFixedRate(this::tickSafe, seconds, seconds, TimeUnit.SECONDS);
        log.info("0.0.6a Dynamic Scaling initialized: mode=" + mode()
                + " targetUtilization=" + targetUtilization()
                + " memoryHardLimit=" + cfg.getBoolean("scaling.memory.enforceHardLimit", true));
    }

    String mode() { return cfg.get("scaling.mode", "manual").toLowerCase(Locale.ROOT); }

    private double targetUtilization() {
        return Math.max(0.50, Math.min(0.95, cfg.getDouble("scaling.targetUtilization", 0.75)));
    }

    private void tickSafe() {
        try { tick(); }
        catch (Throwable t) { log.warn("Scaling check failed: " + (t.getMessage() == null ? t.toString() : t.getMessage())); }
    }

    private void tick() {
        if (cfg.lifecycle.shuttingDown()) return;

        String mode = mode();
        long now = System.currentTimeMillis();
        CapacityBudget.Snapshot budget = workers.capacityBudget();

        List<WorkerRecord> live = workers.all().stream()
                .filter(CapacityBudget::consumesMemory)
                .filter(w -> w.state != WorkerState.STOPPING && w.state != WorkerState.KILLED && w.state != WorkerState.RETIRED)
                .sorted(Comparator.comparing(w -> w.id))
                .toList();
        List<WorkerRecord> routable = live.stream().filter(WorkerRecord::routableForNewSession).toList();

        int count = live.size();
        int players = live.stream().mapToInt(w -> Math.max(0, w.players)).sum();
        int min = Math.max(1, cfg.getInt("scaling.minWorkers", 1));
        int highPlayers = Math.max(1, cfg.getInt("scaling.highPlayersPerWorker", 70));
        int targetPerWorker = Math.max(1, (int)Math.floor(highPlayers * targetUtilization()));
        int warm = Math.max(0, cfg.getInt("scaling.warmWorkers", 0));
        int effectiveMax = Math.max(0, budget.effectiveMaxWorkers());
        double highMspt = Math.max(1, cfg.getDouble("scaling.highMspt", 45.0));
        double highCpu = Math.max(1, Math.min(100, cfg.getDouble("scaling.highCpuPercent", 85.0)));
        double highHeap = Math.max(1, Math.min(100, cfg.getDouble("scaling.highHeapPercent", 85.0)));
        boolean stressed = routable.stream().anyMatch(w -> (w.mspt > 0 && w.mspt >= highMspt)
                || w.cpuPercent >= highCpu
                || (w.heapMax > 0 && (w.heapUsed * 100.0 / w.heapMax) >= highHeap));

        int desiredByPopulation = Math.max(min, players == 0 ? min : (int)Math.ceil(players / (double)targetPerWorker));
        int desired = desiredByPopulation + warm;
        if (stressed && count > 0) desired = Math.max(desired, count + 1);
        if (effectiveMax > 0) desired = Math.min(desired, effectiveMax);
        else desired = 0;

        observedLiveWorkers = count;
        observedPlayers = players;
        targetPlayersPerWorker = targetPerWorker;
        observedStressed = stressed;
        desiredWorkers = desired;

        if (mode.equals("manual")) {
            highSince = 0;
            lowSince = 0;
            if (budget.runningWorkers() >= budget.effectiveMaxWorkers() && budget.hardLimit()) {
                setRecommendation("Memory budget is at its worker limit (" + budget.effectiveMaxWorkers() + "). Manual spawn is blocked until capacity is freed or the budget changes.", "", List.of());
            } else {
                clearRecommendation();
            }
            return;
        }

        if (effectiveMax < min) {
            recommend("Configured minimum workers=" + min + " exceeds the memory-safe maximum=" + effectiveMax + ". Increase the RAM budget or reduce worker reservation.");
        }

        // Do not fight a graceful consolidation already in progress.
        boolean evacuationInProgress = live.stream().anyMatch(w -> w.gracefulStopPending);

        boolean needScaleUp = count < desired || routable.isEmpty();
        if (needScaleUp && !evacuationInProgress) {
            if (highSince == 0) highSince = now;
            long sustain = count < min ? 0 : Math.max(0, cfg.getLong("scaling.scaleUpSustainSeconds", 30)) * 1000L;
            long cooldown = Math.max(0, cfg.getLong("scaling.scaleUpCooldownSeconds", 120)) * 1000L;
            String why = "players=" + players + " live=" + count + " desired=" + desired + " stressed=" + stressed;
            if (now - highSince >= sustain) {
                if (budget.availableSlots() <= 0) {
                    recommend("Need more capacity (" + why + ") but the memory budget is at its safe maximum of " + budget.effectiveMaxWorkers() + " workers.");
                } else if (mode.equals("automatic") && now - lastScaleUp >= cooldown) {
                    try {
                        WorkerRecord w = workers.spawn(false);
                        lastScaleUp = now;
                        highSince = 0;
                        clearRecommendation();
                        lastAction = "Scale up: started " + w.id + " (" + why + ")";
                        audit.record("autoscaler","AUTO_SCALE_UP",w.id,why);
                        log.info("Automatic scaling started " + w.id + "; " + why);
                        return;
                    } catch (Exception e) {
                        recommend("Scale-up blocked: " + e.getMessage());
                    }
                } else if (mode.equals("assisted")) {
                    setRecommendation("Add one worker: " + why + ". Memory-safe slots remaining=" + budget.availableSlots() + ".", "scale_up", List.of());
                }
            }
        } else {
            highSince = 0;
        }

        // Consolidate only after sustained low demand. This is deliberately much slower than scale-up.
        boolean surplus = count > Math.max(min, desired);
        if (surplus && !evacuationInProgress) {
            if (lowSince == 0) lowSince = now;
            long sustain = Math.max(0, cfg.getLong("scaling.scaleDownSustainSeconds", 300)) * 1000L;
            long cooldown = Math.max(0, cfg.getLong("scaling.scaleDownCooldownSeconds", 120)) * 1000L;
            if (now - lowSince >= sustain && now - lastScaleDown >= cooldown) {
                int targetCount = Math.max(min, desired);
                int maxParallel = Math.max(1, cfg.getInt("scaling.maxConcurrentDrains", 4));
                List<WorkerRecord> candidates = chooseConsolidationCandidates(live, targetCount, targetPerWorker, highMspt, highCpu, highHeap, maxParallel);
                if (candidates.isEmpty()) {
                    recommend("Capacity is low, but no worker can be safely consolidated without exceeding remaining worker limits.");
                } else if (candidates.stream().anyMatch(w -> w.players > 0) && !cfg.getBoolean("scaling.scaleDownMigratePlayers", true)) {
                    String ids = String.join(", ", candidates.stream().map(w -> w.id).toList());
                    recommend("Excess workers " + ids + " still have players. Waiting for them to empty because scale-down migration is disabled.");
                } else if (mode.equals("assisted")) {
                    List<String> ids = candidates.stream().map(w -> w.id).toList();
                    int movingPlayers = candidates.stream().mapToInt(w -> Math.max(0, w.players)).sum();
                    setRecommendation("Consolidate " + String.join(", ", ids) + " (" + movingPlayers + " player(s)) into " + targetCount + " target worker(s), then stop the drained JVMs.", "scale_down", ids);
                } else if (mode.equals("automatic")) {
                    try {
                        List<String> ids = candidates.stream().map(w -> w.id).toList();
                        evacuations.beginBatch(ids);
                        lastScaleDown = now;
                        // Demand has already been low for the sustain window. If more than one batch is
                        // required, only the cooldown should delay the next consolidation batch.
                        lowSince = now - sustain;
                        clearRecommendation();
                        int movingPlayers = candidates.stream().mapToInt(w -> Math.max(0, w.players)).sum();
                        lastAction = "Consolidation: draining " + String.join(", ", ids) + " (players=" + movingPlayers + ")";
                        audit.record("autoscaler","AUTO_SCALE_DOWN",String.join(",",ids),lastAction);
                        log.info("Automatic consolidation started for " + ids + " players=" + movingPlayers + " targetWorkers=" + targetCount);
                        return;
                    } catch (Exception e) {
                        recommend("Consolidation was blocked: " + e.getMessage());
                    }
                }
            }
        } else {
            lowSince = 0;
        }

        if (!needScaleUp && !surplus && recommendationAt > 0 && now - recommendationAt > 30_000L) clearRecommendation();
    }

    private List<WorkerRecord> chooseConsolidationCandidates(List<WorkerRecord> live, int desired, int targetPlayersPerWorker,
                                                              double highMspt, double highCpu, double highHeap, int maxParallel) {
        if (live.size() <= desired || live.size() <= 1) return List.of();
        int drainsNeeded = Math.min(Math.max(0, live.size() - desired), Math.max(1, maxParallel));
        if (drainsNeeded <= 0) return List.of();

        // Prefer keeping healthy, low-load, lower-numbered workers. This makes a simple
        // worker-001/002/003 cluster naturally consolidate toward worker-001 when loads tie.
        Comparator<WorkerRecord> drainOrder = Comparator
                .comparingInt((WorkerRecord w) -> Math.max(0, w.players))
                .thenComparingDouble(this::loadScore)
                .thenComparing((WorkerRecord w) -> w.id, Comparator.reverseOrder());

        List<WorkerRecord> remaining = new ArrayList<>(live);
        List<WorkerRecord> drains = new ArrayList<>();
        int totalPlayers = live.stream().mapToInt(w -> Math.max(0, w.players)).sum();

        for (WorkerRecord candidate : live.stream().sorted(drainOrder).toList()) {
            if (drains.size() >= drainsNeeded || remaining.size() <= desired) break;
            if (!candidate.managed || candidate.gracefulStopPending || candidate.maintenance || candidate.pinned) continue;
            if (!(candidate.state == WorkerState.READY || candidate.state == WorkerState.ACTIVE || candidate.state == WorkerState.DRAINED)) continue;

            List<WorkerRecord> test = remaining.stream().filter(w -> w != candidate).toList();
            List<WorkerRecord> destinations = test.stream().filter(WorkerRecord::routableForNewSession).toList();
            if (destinations.isEmpty()) continue;

            int playerCapacity = 0;
            boolean allHot = true;
            for (WorkerRecord w : destinations) {
                int cap = targetPlayersPerWorker;
                if (w.maxPlayers > 0) cap = Math.min(cap, w.maxPlayers);
                playerCapacity += Math.max(1, cap);
                double heapPct = w.heapMax > 0 ? (w.heapUsed * 100.0 / w.heapMax) : 0;
                boolean hot = (w.mspt > 0 && w.mspt >= highMspt) || w.cpuPercent >= highCpu || heapPct >= highHeap;
                if (!hot) allHot = false;
            }
            if (totalPlayers > playerCapacity || allHot) continue;

            drains.add(candidate);
            remaining.remove(candidate);
        }
        return List.copyOf(drains);
    }

    private double loadScore(WorkerRecord w) {
        double heap = w.heapMax > 0 ? (double)w.heapUsed / w.heapMax : 0;
        double mspt = w.mspt > 0 ? w.mspt : 10;
        return mspt + w.players * 0.35 + heap * 12.0;
    }

    private void recommend(String text) { setRecommendation(text, "", List.of()); }

    private void setRecommendation(String text, String action, List<String> workerIds) {
        String next = text == null ? "" : text;
        String nextAction = action == null ? "" : action;
        List<String> nextWorkers = workerIds == null ? List.of() : List.copyOf(workerIds);
        boolean changed = !next.equals(recommendation) || !nextAction.equals(recommendationAction) || !nextWorkers.equals(recommendationWorkers);
        if (!changed) return;
        recommendation = next;
        recommendationAction = nextAction;
        recommendationWorkers = nextWorkers;
        recommendationAt = System.currentTimeMillis();
        if (!recommendation.isBlank()) log.info("Scaling recommendation: " + recommendation);
    }

    private void clearRecommendation() {
        recommendation = "";
        recommendationAction = "";
        recommendationWorkers = List.of();
        recommendationAt = 0;
    }

    synchronized String approveRecommendation() throws Exception {
        cfg.lifecycle.requireRunning();
        String action = recommendationAction;
        if (action == null || action.isBlank()) throw new IllegalStateException("There is no actionable scaling recommendation to approve.");
        if ("scale_up".equals(action)) {
            WorkerRecord w = workers.spawn(false);
            lastScaleUp = System.currentTimeMillis();
            lastAction = "Assisted scale up: started " + w.id;
            clearRecommendation();
            return lastAction;
        }
        if ("scale_down".equals(action)) {
            List<String> ids = recommendationWorkers;
            if(ids.stream().anyMatch(id -> workers.get(id)==null || workers.get(id).pinned)) throw new IllegalStateException("Recommendation is stale or a target is now pinned; wait for refresh");
            if (ids.isEmpty()) throw new IllegalStateException("The consolidation recommendation no longer has worker targets.");
            evacuations.beginBatch(ids);
            lastScaleDown = System.currentTimeMillis();
            lastAction = "Assisted consolidation: draining " + String.join(", ", ids);
            clearRecommendation();
            return lastAction;
        }
        throw new IllegalStateException("Unsupported recommendation action: " + action);
    }

    String statusJson() {
        CapacityBudget.Snapshot b = workers.capacityBudget();
        long now = System.currentTimeMillis();
        return "{"
                + "\"mode\":" + Json.q(mode())
                + ",\"recommendation\":" + Json.q(recommendation)
                + ",\"recommendationAction\":" + Json.q(recommendationAction)
                + ",\"recommendationWorkers\":[" + recommendationWorkers.stream().map(Json::q).collect(java.util.stream.Collectors.joining(",")) + "]"
                + ",\"recommendationAgeSeconds\":" + (recommendationAt == 0 ? -1 : Math.max(0, (now - recommendationAt) / 1000))
                + ",\"lastAction\":" + Json.q(lastAction)
                + ",\"liveWorkers\":" + observedLiveWorkers
                + ",\"desiredWorkers\":" + desiredWorkers
                + ",\"players\":" + observedPlayers
                + ",\"targetPlayersPerWorker\":" + targetPlayersPerWorker
                + ",\"targetUtilization\":" + targetUtilization()
                + ",\"stressed\":" + observedStressed
                + ",\"highPressureSeconds\":" + (highSince == 0 ? 0 : Math.max(0, (now - highSince) / 1000))
                + ",\"lowPressureSeconds\":" + (lowSince == 0 ? 0 : Math.max(0, (now - lowSince) / 1000))
                + ",\"lastScaleUpSecondsAgo\":" + (lastScaleUp == 0 ? -1 : Math.max(0, (now - lastScaleUp) / 1000))
                + ",\"lastScaleDownSecondsAgo\":" + (lastScaleDown == 0 ? -1 : Math.max(0, (now - lastScaleDown) / 1000))
                + ",\"physicalMemoryBytes\":" + b.physicalBytes()
                + ",\"memoryBudgetBytes\":" + b.configuredBudgetBytes()
                + ",\"memoryUsableBytes\":" + b.usableBytes()
                + ",\"systemReserveBytes\":" + b.systemReserveBytes()
                + ",\"workerReservationBytes\":" + b.workerReservationBytes()
                + ",\"configuredMaxWorkers\":" + b.configuredMaxWorkers()
                + ",\"memoryMaxWorkers\":" + (b.memoryMaxWorkers() == Integer.MAX_VALUE ? -1 : b.memoryMaxWorkers())
                + ",\"effectiveMaxWorkers\":" + b.effectiveMaxWorkers()
                + ",\"runningWorkerSlots\":" + b.runningWorkers()
                + ",\"availableWorkerSlots\":" + (b.availableSlots() == Integer.MAX_VALUE ? -1 : b.availableSlots())
                + ",\"memoryHardLimit\":" + b.hardLimit()
                + ",\"memoryBudgetSource\":" + Json.q(b.source())
                + ",\"clusterSeed\":" + Json.q(workers.worldSeedDisplay())
                + ",\"expectedWorldSeed\":" + workers.expectedWorldSeed()
                + ",\"worldIdentity\":" + Json.q(workers.worldIdentityDisplay())
                + "}";
    }

    @Override public void close() { exec.shutdownNow(); }
}
