package com.foundations.cluster.manager;

final class RegionRecord {
    final RegionKey key;
    volatile String ownerWorkerId;
    volatile String previousOwnerWorkerId = "";
    volatile String leaseId;
    volatile long epoch;
    volatile long leaseExpiresAtMillis;
    volatile RegionState state;
    volatile long stateChangedAtMillis;
    volatile long lastHealthyAtMillis;
    volatile long recoveryReadyAtMillis;
    volatile String note = "";

    RegionRecord(RegionKey key, String ownerWorkerId, String leaseId, long epoch, long leaseExpiresAtMillis, RegionState state) {
        this.key = key;
        this.ownerWorkerId = ownerWorkerId == null ? "" : ownerWorkerId;
        this.leaseId = leaseId == null ? "" : leaseId;
        this.epoch = Math.max(1, epoch);
        this.leaseExpiresAtMillis = leaseExpiresAtMillis;
        this.state = state == null ? RegionState.UNASSIGNED : state;
        this.stateChangedAtMillis = System.currentTimeMillis();
        this.lastHealthyAtMillis = this.state == RegionState.ACTIVE ? this.stateChangedAtMillis : 0;
    }
}
