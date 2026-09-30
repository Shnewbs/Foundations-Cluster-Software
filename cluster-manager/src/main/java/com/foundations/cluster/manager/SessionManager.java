package com.foundations.cluster.manager;

import java.util.*;
import java.util.concurrent.*;

final class SessionManager implements AutoCloseable {
    record Placement(SessionRecord session, WorkerRecord worker, LayerRecord layer, String reason) {}

    private final ManagerConfig cfg;
    private final WorkerManager workers;
    private final LayerPlanner layers;
    private final RegionAuthorityManager regions;
    private final PlayerStateManager playerStates;
    private final ClusterLog log;
    private final ConcurrentHashMap<String, SessionRecord> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanup = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "foundations-session-cleanup"); t.setDaemon(true); return t;
    });

    SessionManager(ManagerConfig cfg, WorkerManager workers, LayerPlanner layers, RegionAuthorityManager regions, PlayerStateManager playerStates, ClusterLog log) {
        this.cfg = cfg;
        this.workers = workers;
        this.layers = layers;
        this.regions = regions;
        this.playerStates = playerStates;
        this.log = log;
        cleanup.scheduleAtFixedRate(this::expireOld, 60, 60, TimeUnit.SECONDS);
    }

    Collection<SessionRecord> all() { return new ArrayList<>(sessions.values()); }
    SessionRecord get(String sessionId) { return sessionId == null ? null : sessions.get(sessionId); }

    Placement place(Map<String,String> m) {
        String playerId = m.getOrDefault("playerId", "").trim();
        if (playerId.isBlank()) throw new IllegalArgumentException("playerId is required");
        String sessionId = m.getOrDefault("sessionId", playerId).trim();
        if (sessionId.isBlank()) sessionId = playerId;
        String partyId = m.getOrDefault("partyId", "").trim();
        if (partyId.isBlank() && !m.containsKey("partyId")) {
            for (SessionRecord prior : sessions.values()) {
                if (prior.playerId.equalsIgnoreCase(playerId) && !prior.partyId.isBlank()) { partyId = prior.partyId; break; }
            }
        }

        if (!m.containsKey("cellX") && !m.containsKey("cellZ")) playerStates.enrichPlacement(playerId, m, layers.cellChunks());
        if (!layers.enabled()) return legacyPlace(m, sessionId, playerId, partyId);

        LayerPlanner.Plan plan = layers.plan(m, sessionId, playerId, partyId);
        WorkerRecord selected = plan.worker();
        LayerRecord layer = plan.layer();
        if (selected == null) throw new IllegalStateException("no worker available for layer placement");

        SessionRecord s = sessions.get(sessionId);
        LayerCellKey cell = layer == null ? layers.cellFrom(m) : layer.cell;
        if (s == null) {
            s = new SessionRecord(sessionId, playerId, partyId, selected.id,
                    cell.worldId(), cell.dimensionId(), cell.cellX(), cell.cellZ(), layer == null ? "" : layer.id);
            sessions.put(sessionId, s);
        } else {
            s.partyId = partyId;
            s.workerId = selected.id;
            s.anchorWorkerId = selected.id;
            s.simulationWorkerId = selected.id;
            s.worldId = cell.worldId(); s.dimensionId = cell.dimensionId(); s.cellX = cell.cellX(); s.cellZ = cell.cellZ();
            s.layerId = layer == null ? "" : layer.id;
            s.lastSeenMillis = System.currentTimeMillis();
        }
        layers.commit(sessionId, playerId, partyId, layer, selected);
        RegionRecord region = regions.ensureForCell(cell, layers.cellChunks(), selected.id);
        s.regionId = region.key.id(); s.regionX = region.key.regionX(); s.regionZ = region.key.regionZ();
        s.authorityWorkerId = region.ownerWorkerId; s.authorityEpoch = region.epoch;
        log.info("Session placement player=" + playerId + " session=" + sessionId + " -> " + selected.id
                + (layer == null ? "" : " layer=" + layer.id + " cell=" + layer.cell.display()) + " region=" + s.regionId + " authority=" + s.authorityWorkerId + "@" + s.authorityEpoch + " reason=" + plan.reason());
        return new Placement(s, selected, layer, plan.reason());
    }

    private Placement legacyPlace(Map<String,String> m, String sessionId, String playerId, String partyId) {
        SessionRecord existing = sessions.get(sessionId);
        if (existing != null) {
            WorkerRecord current = workers.get(existing.workerId);
            if (current != null && current.usableForExistingSession()) {
                existing.lastSeenMillis = System.currentTimeMillis();
                existing.partyId = partyId;
                return new Placement(existing, current, null, "existing-session-affinity");
            }
        }
        WorkerRecord preferred = null;
        if (!partyId.isBlank()) {
            for (SessionRecord s : sessions.values()) {
                if (partyId.equals(s.partyId)) {
                    WorkerRecord w = workers.get(s.workerId);
                    if (w != null && w.routableForNewSession()) { preferred = w; break; }
                }
            }
        }
        WorkerRecord selected = preferred != null ? preferred : workers.routeCandidate();
        if (selected == null) throw new IllegalStateException("no READY/ACTIVE non-draining worker available");
        LayerCellKey cell = layers.cellFrom(m);
        SessionRecord s = new SessionRecord(sessionId, playerId, partyId, selected.id,
                cell.worldId(), cell.dimensionId(), cell.cellX(), cell.cellZ(), "");
        sessions.put(sessionId, s);
        RegionRecord region = regions.ensureForCell(cell, layers.cellChunks(), selected.id);
        s.regionId = region.key.id(); s.regionX = region.key.regionX(); s.regionZ = region.key.regionZ();
        s.authorityWorkerId = region.ownerWorkerId; s.authorityEpoch = region.epoch;
        String reason = preferred != null ? "party-affinity" : "health-capacity-score";
        return new Placement(s, selected, null, reason);
    }

    void release(String sessionId) {
        SessionRecord removed = sessions.remove(sessionId);
        layers.release(sessionId);
        if (removed != null) log.info("Session released: " + sessionId + " from " + removed.workerId + (removed.layerId.isBlank()?"":" layer="+removed.layerId));
    }


    SessionRecord findByPlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) return null;
        for (SessionRecord s : sessions.values()) if (s.playerId.equalsIgnoreCase(playerId)) return s;
        return null;
    }

    synchronized SessionRecord commitHandoff(String sessionId, String destinationWorkerId, String targetLayerId) {
        SessionRecord s = sessions.get(sessionId);
        if (s == null) throw new IllegalArgumentException("Unknown session: " + sessionId);
        WorkerRecord dest = workers.get(destinationWorkerId);
        if (dest == null || !dest.usableForExistingSession()) throw new IllegalStateException("Destination worker is unavailable: " + destinationWorkerId);
        LayerRecord layer = layers.allLayers().stream().filter(l -> l.id.equals(targetLayerId)).findFirst().orElse(null);
        if (layer == null) throw new IllegalStateException("Target layer not found: " + targetLayerId);
        s.workerId = destinationWorkerId;
        s.anchorWorkerId = destinationWorkerId;
        s.simulationWorkerId = destinationWorkerId;
        s.layerId = layer.id;
        s.worldId = layer.cell.worldId(); s.dimensionId = layer.cell.dimensionId(); s.cellX = layer.cell.cellX(); s.cellZ = layer.cell.cellZ();
        s.lastSeenMillis = System.currentTimeMillis();
        layers.commit(s.sessionId, s.playerId, s.partyId, layer, dest);
        RegionRecord region = regions.ensureForCell(layer.cell, layers.cellChunks(), dest.id);
        s.regionId = region.key.id(); s.regionX = region.key.regionX(); s.regionZ = region.key.regionZ();
        s.authorityWorkerId = region.ownerWorkerId; s.authorityEpoch = region.epoch;
        log.info("Session handoff committed player=" + s.playerId + " session=" + s.sessionId + " -> " + destinationWorkerId + " layer=" + layer.id);
        return s;
    }

    /**
     * Commit an invisible simulation-target transition while preserving the
     * client-facing anchor worker. This is the normal 0.0.5a.R5 handoff path.
     * No Velocity backend switch occurs here.
     */
    synchronized SessionRecord commitSimulationTarget(String sessionId, String destinationWorkerId, String targetLayerId) {
        SessionRecord s = sessions.get(sessionId);
        if (s == null) throw new IllegalArgumentException("Unknown session: " + sessionId);
        WorkerRecord dest = workers.get(destinationWorkerId);
        if (dest == null || !dest.usableForExistingSession()) throw new IllegalStateException("Destination simulation worker is unavailable: " + destinationWorkerId);
        LayerRecord layer = layers.allLayers().stream().filter(l -> l.id.equals(targetLayerId)).findFirst().orElse(null);
        if (layer == null) throw new IllegalStateException("Target layer not found: " + targetLayerId);

        // Keep workerId as the backwards-compatible session anchor alias.
        s.workerId = s.anchorWorkerId;
        s.simulationWorkerId = destinationWorkerId;
        s.layerId = layer.id;
        s.worldId = layer.cell.worldId(); s.dimensionId = layer.cell.dimensionId(); s.cellX = layer.cell.cellX(); s.cellZ = layer.cell.cellZ();
        s.lastSeenMillis = System.currentTimeMillis();
        layers.commit(s.sessionId, s.playerId, s.partyId, layer, dest);
        RegionRecord region = regions.ensureForCell(layer.cell, layers.cellChunks(), dest.id);
        s.regionId = region.key.id(); s.regionX = region.key.regionX(); s.regionZ = region.key.regionZ();
        s.authorityWorkerId = region.ownerWorkerId; s.authorityEpoch = region.epoch;
        log.info("Invisible session transition committed player=" + s.playerId + " session=" + s.sessionId
                + " anchor=" + s.anchorWorkerId + " simulation=" + destinationWorkerId + " layer=" + layer.id
                + " authority=" + s.authorityWorkerId + "@" + s.authorityEpoch);
        return s;
    }

    /**
     * R11 crash-only physical re-anchor. The source backend is already gone, so
     * there is no source snapshot phase. Re-plan the existing session in the
     * same world/cell after the failed worker has been quarantined. If a prior
     * invisible handoff already moved simulation to another worker, the layer
     * assignment naturally keeps that healthy simulation target.
     */
    synchronized Placement emergencyFailover(String sessionId, String playerId, String failedWorkerId) {
        cfg.lifecycle.requireRunning();
        SessionRecord s = sessionId == null || sessionId.isBlank() ? null : sessions.get(sessionId);
        if (s == null && playerId != null && !playerId.isBlank()) s = findByPlayer(playerId);
        if (s == null) throw new IllegalArgumentException("No active session exists for emergency failover");
        if (failedWorkerId != null && !failedWorkerId.isBlank() && !failedWorkerId.equals(s.anchorWorkerId))
            throw new IllegalStateException("Reported failed backend is not the current session anchor: anchor=" + s.anchorWorkerId);

        Map<String,String> request = new HashMap<>();
        request.put("playerId", s.playerId);
        request.put("sessionId", s.sessionId);
        request.put("partyId", s.partyId);
        request.put("worldId", s.worldId);
        request.put("dimensionId", s.dimensionId);
        request.put("cellX", Integer.toString(s.cellX));
        request.put("cellZ", Integer.toString(s.cellZ));
        Placement placed = place(request);
        if (failedWorkerId != null && !failedWorkerId.isBlank() && failedWorkerId.equals(placed.worker().id))
            throw new IllegalStateException("Emergency failover replanned onto the failed backend");
        log.warn("Emergency session failover player=" + placed.session().playerId + " session=" + placed.session().sessionId
                + " failedAnchor=" + failedWorkerId + " -> " + placed.worker().id
                + (placed.layer() == null ? "" : " layer=" + placed.layer().id));
        return placed;
    }

    int count() { return sessions.size(); }

    int countForWorker(String workerId) {
        if (workerId == null || workerId.isBlank()) return 0;
        int count = 0;
        for (SessionRecord s : sessions.values()) {
            if (workerId.equals(s.anchorWorkerId) || workerId.equals(s.simulationWorkerId)) count++;
        }
        return count;
    }

    int anchorCountForWorker(String workerId) {
        if (workerId == null || workerId.isBlank()) return 0;
        int count = 0;
        for (SessionRecord s : sessions.values()) if (workerId.equals(s.anchorWorkerId)) count++;
        return count;
    }

    int simulationCountForWorker(String workerId) {
        if (workerId == null || workerId.isBlank()) return 0;
        int count = 0;
        for (SessionRecord s : sessions.values()) if (workerId.equals(s.simulationWorkerId)) count++;
        return count;
    }

    int releaseForWorker(String workerId) {
        if (workerId == null || workerId.isBlank()) return 0;
        List<String> ids = new ArrayList<>();
        for (var e : sessions.entrySet()) {
            SessionRecord s=e.getValue();
            if (workerId.equals(s.anchorWorkerId) || workerId.equals(s.simulationWorkerId)) ids.add(e.getKey());
        }
        for (String id : ids) release(id);
        return ids.size();
    }

    private void expireOld() {
        long ttl = Math.max(60, cfg.getLong("session.ttlSeconds", 1800)) * 1000L;
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (var e : sessions.entrySet()) if (now - e.getValue().lastSeenMillis > ttl) expired.add(e.getKey());
        for (String id : expired) release(id);
    }

    public void close() { cleanup.shutdownNow(); }
}
