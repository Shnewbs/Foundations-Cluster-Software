package com.foundations.cluster.manager;

final class HandoffRecord {
    final String id;
    final String sessionId;
    final String playerId;
    final String sourceWorkerId;
    final String destinationWorkerId;
    final String targetLayerId;
    final String reason;
    volatile boolean proxySwitchRequired;
    final long createdAtMillis = System.currentTimeMillis();
    volatile long updatedAtMillis = createdAtMillis;
    volatile HandoffState state = HandoffState.PREPARING_SOURCE;
    volatile String snapshotJson = "";
    volatile String error = "";
    volatile long sourcePreparedAtMillis;
    volatile long destinationReadyAtMillis;
    volatile long committedAtMillis;

    HandoffRecord(String id, String sessionId, String playerId, String sourceWorkerId,
                  String destinationWorkerId, String targetLayerId, String reason, boolean proxySwitchRequired) {
        this.id=id; this.sessionId=sessionId; this.playerId=playerId;
        this.sourceWorkerId=sourceWorkerId; this.destinationWorkerId=destinationWorkerId;
        this.targetLayerId=targetLayerId == null ? "" : targetLayerId;
        this.reason=reason == null ? "" : reason;
        this.proxySwitchRequired=proxySwitchRequired;
    }
}
