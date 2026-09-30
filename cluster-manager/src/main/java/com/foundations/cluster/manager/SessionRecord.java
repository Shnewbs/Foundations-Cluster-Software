package com.foundations.cluster.manager;

final class SessionRecord {
    final String sessionId;
    final String playerId;
    volatile String partyId;

    /**
     * Backwards-compatible alias for the client-facing session anchor.
     * 0.0.5a.R5 keeps the Minecraft client attached to this worker during
     * normal region movement so the client never enters CONFIGURATION again.
     */
    volatile String workerId;
    volatile String anchorWorkerId;

    /**
     * Worker currently selected to own/serve simulation for the player's
     * logical cell. This may differ from anchorWorkerId without moving the
     * client connection.
     */
    volatile String simulationWorkerId;

    volatile String worldId;
    volatile String dimensionId;
    volatile int cellX;
    volatile int cellZ;
    volatile String layerId;
    volatile String regionId = "";
    volatile int regionX;
    volatile int regionZ;
    volatile String authorityWorkerId = "";
    volatile long authorityEpoch;
    final long createdAtMillis = System.currentTimeMillis();
    volatile long lastSeenMillis = createdAtMillis;

    SessionRecord(String sessionId, String playerId, String partyId, String workerId,
                  String worldId, String dimensionId, int cellX, int cellZ, String layerId) {
        this.sessionId = sessionId;
        this.playerId = playerId;
        this.partyId = partyId == null ? "" : partyId;
        this.workerId = workerId;
        this.anchorWorkerId = workerId;
        this.simulationWorkerId = workerId;
        this.worldId = worldId == null ? "foundations" : worldId;
        this.dimensionId = dimensionId == null ? "minecraft:overworld" : dimensionId;
        this.cellX = cellX;
        this.cellZ = cellZ;
        this.layerId = layerId == null ? "" : layerId;
    }
}
