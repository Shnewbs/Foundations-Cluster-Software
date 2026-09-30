package com.foundations.cluster.manager;

enum RegionState {
    ACTIVE,
    SUSPECT,
    EXPIRED,
    RECOVERING,
    TRANSFERRING,
    FROZEN,
    UNASSIGNED
}
