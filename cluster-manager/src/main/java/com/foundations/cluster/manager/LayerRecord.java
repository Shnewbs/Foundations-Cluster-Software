package com.foundations.cluster.manager;

final class LayerRecord {
    final String id;
    final LayerCellKey cell;
    final int slot;
    final long createdAtMillis = System.currentTimeMillis();

    volatile String workerId;
    volatile LayerState state = LayerState.ACTIVE;
    volatile String leaseId;
    volatile long leaseEpoch = 1;
    volatile long leaseExpiresAtMillis;
    volatile long lastPopulationChangeMillis = createdAtMillis;
    volatile long drainStartedAtMillis;
    volatile String note = "";

    LayerRecord(String id, LayerCellKey cell, int slot, String workerId, String leaseId, long leaseExpiresAtMillis) {
        this.id = id;
        this.cell = cell;
        this.slot = slot;
        this.workerId = workerId;
        this.leaseId = leaseId;
        this.leaseExpiresAtMillis = leaseExpiresAtMillis;
    }

    boolean acceptsNew() { return state == LayerState.ACTIVE; }
    boolean acceptsExisting() { return state == LayerState.ACTIVE || state == LayerState.FROZEN || state == LayerState.DRAINING; }
}
