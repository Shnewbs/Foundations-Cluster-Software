package com.foundations.cluster.manager;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Population-only layer planner.
 * IMPORTANT: these leases do NOT grant persistent world write authority.
 * Region ownership/fencing remains a later milestone.
 */
final class LayerPlanner implements AutoCloseable {
    record Plan(LayerRecord layer, WorkerRecord worker, String reason) {}
    record Summary(int activeLayers, int groups, int assignments, int unavailableLayers) {}

    private final ManagerConfig cfg;
    private final WorkerManager workers;
    private final ClusterLog log;
    private final ConcurrentHashMap<String, LayerRecord> layers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LayerAssignment> assignments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> splitSince = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> mergeSince = new ConcurrentHashMap<>();
    private final AtomicInteger nextLayer = new AtomicInteger(1);
    private final ScheduledExecutorService planner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "foundations-layer-planner"); t.setDaemon(true); return t;
    });

    LayerPlanner(ManagerConfig cfg, WorkerManager workers, ClusterLog log) {
        this.cfg = cfg; this.workers = workers; this.log = log;
        long sec = Math.max(2, cfg.getLong("layer.checkSeconds", 5));
        planner.scheduleAtFixedRate(this::tickSafe, sec, sec, TimeUnit.SECONDS);
        log.info("Layer Planner initialized: enabled=" + enabled() + " cellChunks=" + cellChunks()
                + " target=" + targetPlayers() + " split=" + splitPlayers() + " merge=" + mergePlayers());
    }

    boolean enabled() { return cfg.getBoolean("layer.enabled", true); }
    int cellChunks() { return Math.max(1, cfg.getInt("layer.cellChunks", 16)); }
    int targetPlayers() { return Math.max(1, cfg.getInt("layer.targetPlayers", 60)); }
    int splitPlayers() { return Math.max(targetPlayers() + 1, cfg.getInt("layer.splitPlayers", 80)); }
    int mergePlayers() { return Math.max(0, Math.min(targetPlayers(), cfg.getInt("layer.mergePlayers", 30))); }
    int maxLayersPerCell() { return Math.max(1, cfg.getInt("layer.maxLayersPerCell", 8)); }
    long leaseMillis() { return Math.max(5, cfg.getLong("layer.leaseSeconds", 15)) * 1000L; }

    Collection<LayerRecord> allLayers() { return new ArrayList<>(layers.values()); }
    Collection<LayerAssignment> allAssignments() { return new ArrayList<>(assignments.values()); }
    LayerRecord get(String id) { return layers.get(id); }


    List<String> layerIdsForWorker(String workerId) {
        return layers.values().stream()
                .filter(l -> l.state != LayerState.RETIRED && workerId.equals(l.workerId))
                .sorted(Comparator.comparingInt(l -> l.slot))
                .map(l -> l.id).toList();
    }

    Summary summary() {
        int active = 0, unavailable = 0;
        Set<String> groups = new HashSet<>();
        for (LayerRecord l : layers.values()) {
            if (l.state != LayerState.RETIRED) groups.add(l.cell.groupId());
            if (l.state == LayerState.ACTIVE || l.state == LayerState.FROZEN || l.state == LayerState.DRAINING) active++;
            if (l.state == LayerState.UNAVAILABLE) unavailable++;
        }
        return new Summary(active, groups.size(), assignments.size(), unavailable);
    }

    Plan plan(Map<String,String> request, String sessionId, String playerId, String partyId) {
        if (!enabled()) {
            WorkerRecord w = workers.routeCandidate();
            if (w == null) throw new IllegalStateException("no READY/ACTIVE non-draining worker available");
            return new Plan(null, w, "layer-planner-disabled");
        }
        LayerCellKey cell = parseCell(request);
        String explicitLayer = request.getOrDefault("targetLayerId", "").trim();

        LayerAssignment existing = assignments.get(sessionId);
        if (existing != null) {
            LayerRecord old = layers.get(existing.layerId);
            if (old != null && old.cell.equals(cell) && layerUsableForExisting(old)) {
                WorkerRecord w = workers.get(old.workerId);
                if (w != null && w.usableForExistingSession()) return new Plan(old, w, "existing-layer-affinity");
            }
        }

        if (!explicitLayer.isBlank()) {
            LayerRecord l = layers.get(explicitLayer);
            if (l != null && l.cell.equals(cell) && layerUsableForNew(l)) {
                WorkerRecord w = workers.get(l.workerId);
                if (w != null) return new Plan(l, w, "explicit-layer-target");
            }
        }

        if (cfg.getBoolean("layer.partyAffinity", true) && partyId != null && !partyId.isBlank()) {
            for (LayerAssignment a : assignments.values()) {
                if (!partyId.equals(a.partyId) || !cell.equals(a.cell)) continue;
                LayerRecord l = layers.get(a.layerId);
                if (l != null && layerUsableForNew(l)) {
                    WorkerRecord w = workers.get(l.workerId);
                    if (w != null) return new Plan(l, w, "party-layer-affinity");
                }
            }
        }

        if (existing != null) {
            LayerRecord old = layers.get(existing.layerId);
            if (old != null) {
                LayerRecord sameSlot = layersInCell(cell).stream()
                        .filter(l -> l.slot == old.slot && layerUsableForNew(l)).findFirst().orElse(null);
                if (sameSlot != null) {
                    WorkerRecord w = workers.get(sameSlot.workerId);
                    if (w != null) return new Plan(sameSlot, w, "sticky-layer-slot");
                }
            }
        }

        List<LayerRecord> candidates = layersInCell(cell).stream().filter(this::layerUsableForNew).toList();
        if (candidates.isEmpty()) candidates = List.of(createLayer(cell, null, "auto-initial"));

        LayerRecord selected = candidates.stream()
                .min(Comparator.comparingInt(this::population).thenComparingInt(l -> l.slot)).orElseThrow();
        WorkerRecord w = workers.get(selected.workerId);
        if (w == null || !w.routableForNewSession()) {
            markUnavailable(selected, "worker not routable");
            selected = createLayer(cell, null, "worker-replacement");
            w = workers.get(selected.workerId);
        }
        return new Plan(selected, w, "layer-capacity-score");
    }

    void commit(String sessionId, String playerId, String partyId, LayerRecord layer, WorkerRecord worker) {
        if (layer == null) return;
        assignments.compute(sessionId, (k, old) -> {
            LayerAssignment a = old == null ? new LayerAssignment(sessionId, playerId, partyId, layer.id, worker.id, layer.cell) : old;
            a.partyId = partyId == null ? "" : partyId;
            a.layerId = layer.id; a.workerId = worker.id; a.cell = layer.cell; a.updatedAtMillis = System.currentTimeMillis();
            return a;
        });
        layer.lastPopulationChangeMillis = System.currentTimeMillis();
    }

    void release(String sessionId) {
        LayerAssignment a = assignments.remove(sessionId);
        if (a != null) {
            LayerRecord l = layers.get(a.layerId);
            if (l != null) l.lastPopulationChangeMillis = System.currentTimeMillis();
        }
    }

    LayerRecord createLayer(LayerCellKey cell, String preferredWorkerId, String note) {
        if (layersInCell(cell).stream().filter(l -> l.state != LayerState.RETIRED).count() >= maxLayersPerCell())
            throw new IllegalStateException("maximum layers reached for cell " + cell.display());
        WorkerRecord w = preferredWorkerId == null || preferredWorkerId.isBlank() ? chooseWorker(cell) : workers.get(preferredWorkerId);
        if (w == null || !w.routableForNewSession()) throw new IllegalStateException("no READY/ACTIVE worker available for new layer");
        int slot = nextSlot(cell);
        String id = String.format(Locale.ROOT, "layer-%06d", nextLayer.getAndIncrement());
        long now = System.currentTimeMillis();
        LayerRecord l = new LayerRecord(id, cell, slot, w.id, UUID.randomUUID().toString(), now + leaseMillis());
        l.note = note == null ? "" : note;
        layers.put(id, l);
        log.info("Layer created " + id + " cell=" + cell.display() + " slot=" + slot + " worker=" + w.id + " reason=" + l.note);
        return l;
    }

    void freeze(String id) { transition(id, LayerState.FROZEN, "manual-freeze"); }
    void unfreeze(String id) { LayerRecord l = require(id); if (l.state != LayerState.FROZEN) throw new IllegalStateException("layer is not frozen"); transition(id, LayerState.ACTIVE, "manual-unfreeze"); }
    void drain(String id) { LayerRecord l=require(id); if(l.state==LayerState.RETIRED)throw new IllegalStateException("layer is retired"); l.drainStartedAtMillis=System.currentTimeMillis(); transition(id,LayerState.DRAINING,"manual-drain"); }
    void undrain(String id) { LayerRecord l=require(id); if(l.state!=LayerState.DRAINING)throw new IllegalStateException("layer is not draining"); l.drainStartedAtMillis=0; transition(id,LayerState.ACTIVE,"manual-undrain"); }
    void retire(String id) {
        LayerRecord l = require(id);
        int pop = population(l);
        if (pop > 0) throw new IllegalStateException("layer still has " + pop + " assigned session(s); retirement is blocked until population reaches zero");
        transition(id, LayerState.RETIRED, "manual-retire");
    }

    int population(LayerRecord layer) {
        int count = 0;
        for (LayerAssignment a : assignments.values()) if (layer.id.equals(a.layerId)) count++;
        return count;
    }

    int activeLayerCountForWorker(String workerId) {
        if (workerId == null || workerId.isBlank()) return 0;
        int count = 0;
        for (LayerRecord l : layers.values()) if (workerId.equals(l.workerId) && l.state != LayerState.RETIRED) count++;
        return count;
    }

    int populationForWorker(String workerId) {
        if (workerId == null || workerId.isBlank()) return 0;
        int count = 0;
        for (LayerAssignment a : assignments.values()) {
            LayerRecord l = layers.get(a.layerId);
            if (l != null && workerId.equals(l.workerId) && l.state != LayerState.RETIRED) count++;
        }
        return count;
    }

    int drainWorkerLayers(String workerId) {
        int changed = 0;
        for (LayerRecord l : layers.values()) {
            if (!workerId.equals(l.workerId) || l.state == LayerState.RETIRED || l.state == LayerState.DRAINING) continue;
            l.drainStartedAtMillis = System.currentTimeMillis();
            transition(l.id, LayerState.DRAINING, "worker-retire-drain");
            changed++;
        }
        return changed;
    }

    int retireEmptyWorkerLayers(String workerId) {
        int retired = 0;
        for (LayerRecord l : new ArrayList<>(layers.values())) {
            if (!workerId.equals(l.workerId) || l.state == LayerState.RETIRED || population(l) > 0) continue;
            transition(l.id, LayerState.RETIRED, "worker-retire-complete");
            retired++;
        }
        return retired;
    }

    int forceRetireWorkerLayers(String workerId) {
        int retired = 0;
        Set<String> affected = new HashSet<>();
        for (LayerRecord l : layers.values()) {
            if (!workerId.equals(l.workerId) || l.state == LayerState.RETIRED) continue;
            affected.add(l.id);
            transition(l.id, LayerState.RETIRED, "worker-force-delete");
            retired++;
        }
        if (!affected.isEmpty()) assignments.entrySet().removeIf(e -> affected.contains(e.getValue().layerId));
        return retired;
    }

    List<LayerRecord> layersInCell(LayerCellKey cell) {
        return layers.values().stream().filter(l -> l.cell.equals(cell) && l.state != LayerState.RETIRED)
                .sorted(Comparator.comparingInt(l -> l.slot)).toList();
    }

    List<LayerCellKey> activeCells() {
        TreeMap<String,LayerCellKey> out = new TreeMap<>();
        for (LayerRecord l : layers.values()) if (l.state != LayerState.RETIRED) out.put(l.cell.groupId(), l.cell);
        return new ArrayList<>(out.values());
    }

    private void tickSafe() { try { tick(); } catch (Throwable t) { log.warn("Layer Planner tick failed: " + t.getMessage()); } }
    private void tick() {
        if (cfg.lifecycle.shuttingDown()) return;

        if (!enabled()) return;
        long now = System.currentTimeMillis();
        for (LayerRecord l : layers.values()) {
            if (l.state == LayerState.RETIRED) continue;
            WorkerRecord w = workers.get(l.workerId);
            if (w == null || !w.usableForExistingSession()) markUnavailable(l, "worker unavailable");
            else {
                l.leaseExpiresAtMillis = now + leaseMillis();
                if (l.state == LayerState.UNAVAILABLE && w.routableForNewSession()) transition(l.id, LayerState.ACTIVE, "worker-recovered");
            }
        }
        for (LayerCellKey cell : activeCells()) evaluateCell(cell, now);
    }

    private void evaluateCell(LayerCellKey cell, long now) {
        List<LayerRecord> current = layersInCell(cell);
        if (current.isEmpty()) return;
        int total = current.stream().mapToInt(this::population).sum();
        int maxPopulation = current.stream().filter(l -> l.state == LayerState.ACTIVE).mapToInt(this::population).max().orElse(0);
        double splitMspt = cfg.getDouble("layer.splitMspt", 45.0);
        double worstMspt = current.stream().map(l -> workers.get(l.workerId)).filter(Objects::nonNull).mapToDouble(w -> w.mspt).max().orElse(0);
        int desiredByTarget = Math.max(1, (int)Math.ceil(total / (double)Math.max(1, targetPlayers())));
        boolean hasSpare = current.stream().anyMatch(l -> l.state == LayerState.ACTIVE && population(l) < targetPlayers()
                && Optional.ofNullable(workers.get(l.workerId)).map(w -> w.routableForNewSession() && w.mspt < splitMspt).orElse(false));
        boolean populationPressure = current.size() < desiredByTarget || (maxPopulation >= splitPlayers() && !hasSpare);
        boolean performancePressure = worstMspt >= splitMspt && !hasSpare;
        boolean splitPressure = populationPressure || performancePressure;
        String key = cell.groupId();
        if (splitPressure && current.size() < maxLayersPerCell() && cfg.getBoolean("layer.autoSplit", true)) {
            long since = splitSince.computeIfAbsent(key, k -> now);
            if (now - since >= Math.max(0, cfg.getLong("layer.splitSustainSeconds", 30)) * 1000L) {
                try { createLayer(cell, null, populationPressure ? "auto-split-population" : "auto-split-mspt"); splitSince.remove(key); }
                catch (Exception ex) { log.warn("Layer auto-split deferred for " + cell.display() + ": " + ex.getMessage()); }
            }
        } else splitSince.remove(key);

        List<LayerRecord> active = current.stream().filter(l -> l.state == LayerState.ACTIVE).toList();
        if (current.size() > 1 && total <= mergePlayers() && cfg.getBoolean("layer.autoMerge", true)) {
            long since = mergeSince.computeIfAbsent(key, k -> now);
            if (now - since >= Math.max(0, cfg.getLong("layer.mergeSustainSeconds", 300)) * 1000L) {
                LayerRecord empty = current.stream().filter(l -> population(l) == 0 && l.slot != 1)
                        .max(Comparator.comparingInt(l -> l.slot)).orElse(null);
                if (empty != null) {
                    transition(empty.id, LayerState.RETIRED, "auto-merge-empty-layer");
                    log.info("Layer merged/retired " + empty.id + " cell=" + cell.display());
                    mergeSince.remove(key);
                } else if (active.size() > 1) {
                    LayerRecord low = active.stream().min(Comparator.comparingInt(this::population).thenComparingInt(l -> -l.slot)).orElse(null);
                    if (low != null && low.slot != 1) { low.drainStartedAtMillis = now; transition(low.id, LayerState.DRAINING, "auto-merge-drain"); }
                }
            }
        } else mergeSince.remove(key);

        for (LayerRecord l : current) {
            if (l.state == LayerState.DRAINING && population(l) == 0 && l.note.startsWith("auto-merge")) transition(l.id, LayerState.RETIRED, "auto-merge-complete");
        }
    }

    private LayerCellKey parseCell(Map<String,String> m) {
        String world = m.getOrDefault("worldId", cfg.get("layer.defaultWorld", "foundations"));
        String dim = m.getOrDefault("dimensionId", cfg.get("layer.defaultDimension", "minecraft:overworld"));
        int x = parseInt(m.get("cellX"), 0), z = parseInt(m.get("cellZ"), 0);
        return new LayerCellKey(world, dim, x, z);
    }

    LayerCellKey cellFrom(Map<String,String> m) { return parseCell(m); }

    private boolean layerUsableForNew(LayerRecord l) {
        if (!l.acceptsNew()) return false;
        WorkerRecord w = workers.get(l.workerId);
        return w != null && w.routableForNewSession();
    }
    private boolean layerUsableForExisting(LayerRecord l) {
        if (!l.acceptsExisting()) return false;
        WorkerRecord w = workers.get(l.workerId);
        return w != null && w.usableForExistingSession();
    }

    private WorkerRecord chooseWorker(LayerCellKey cell) {
        Map<String,Integer> globalLayerCounts = new HashMap<>(), cellLayerCounts = new HashMap<>();
        for (LayerRecord l : layers.values()) if (l.state != LayerState.RETIRED) {
            globalLayerCounts.merge(l.workerId, 1, Integer::sum);
            if (l.cell.equals(cell)) cellLayerCounts.merge(l.workerId, 1, Integer::sum);
        }
        return workers.all().stream().filter(WorkerRecord::routableForNewSession)
                .min(Comparator.comparingDouble(w -> w.players * 10.0 + Math.max(0,w.mspt)
                        + globalLayerCounts.getOrDefault(w.id,0) * 5.0 + cellLayerCounts.getOrDefault(w.id,0) * 100.0))
                .orElse(null);
    }

    private int nextSlot(LayerCellKey cell) {
        BitSet used = new BitSet(); for (LayerRecord l : layersInCell(cell)) used.set(l.slot);
        int slot = 1; while (used.get(slot)) slot++; return slot;
    }

    private void markUnavailable(LayerRecord l, String why) {
        if (l.state == LayerState.RETIRED || l.state == LayerState.UNAVAILABLE) return;
        l.note = why; l.state = LayerState.UNAVAILABLE; l.leaseExpiresAtMillis = System.currentTimeMillis();
        log.warn("Layer unavailable " + l.id + " worker=" + l.workerId + " reason=" + why);
    }

    private void transition(String id, LayerState target, String note) {
        LayerRecord l=require(id); l.state=target; l.note=note; l.lastPopulationChangeMillis=System.currentTimeMillis();
        log.info("Layer state " + id + " -> " + target + " reason=" + note);
    }
    private LayerRecord require(String id) { LayerRecord l=layers.get(id); if(l==null)throw new IllegalArgumentException("unknown layer: "+id); return l; }
    private static int parseInt(String v, int d) { try { return Integer.parseInt(v); } catch (Exception e) { return d; } }
    public void close() { planner.shutdownNow(); }
}
