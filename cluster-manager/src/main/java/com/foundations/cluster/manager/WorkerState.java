package com.foundations.cluster.manager;

enum WorkerState {
    STARTING,
    BOOTING,
    READY,
    ACTIVE,
    DRAINING,
    DRAINED,
    MAINTENANCE,
    UNHEALTHY,
    STOPPING,
    OFFLINE,
    CRASHED,
    KILLED,
    RETIRED,
    ERROR
}
