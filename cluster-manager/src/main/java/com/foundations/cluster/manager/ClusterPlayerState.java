package com.foundations.cluster.manager;

final class ClusterPlayerState {
    final String uuid;
    volatile String name;
    volatile String workerId;
    volatile String dimensionId;
    volatile double x;
    volatile double y;
    volatile double z;
    volatile float yaw;
    volatile float pitch;
    volatile String gameMode;
    volatile boolean op;
    volatile long revision;
    volatile long updatedAtMillis;

    ClusterPlayerState(String uuid) {
        this.uuid = uuid;
        this.updatedAtMillis = System.currentTimeMillis();
    }
}
