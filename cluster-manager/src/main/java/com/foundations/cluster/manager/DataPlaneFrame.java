package com.foundations.cluster.manager;

final class DataPlaneFrame {
    final String id;
    final String kind;
    final String sourceWorkerId;
    final String targetWorkerId;
    final String sessionId;
    final String playerId;
    final String worldId;
    final String dimensionId;
    final String regionId;
    final long authorityEpoch;
    final String payload;
    final long createdAtMillis;
    volatile long lastDeliveryAtMillis;
    volatile int deliveries;

    DataPlaneFrame(String id, String kind, String sourceWorkerId, String targetWorkerId,
                   String sessionId, String playerId, String worldId, String dimensionId,
                   String regionId, long authorityEpoch, String payload) {
        this.id=id; this.kind=kind; this.sourceWorkerId=sourceWorkerId; this.targetWorkerId=targetWorkerId;
        this.sessionId=sessionId==null?"":sessionId; this.playerId=playerId==null?"":playerId;
        this.worldId=worldId==null?"foundations":worldId;
        this.dimensionId=dimensionId==null?"minecraft:overworld":dimensionId;
        this.regionId=regionId==null?"":regionId; this.authorityEpoch=authorityEpoch;
        this.payload=payload==null?"":payload; this.createdAtMillis=System.currentTimeMillis();
    }
}
