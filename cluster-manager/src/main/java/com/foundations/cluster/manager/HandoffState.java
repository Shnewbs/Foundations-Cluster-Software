package com.foundations.cluster.manager;

enum HandoffState {
    PREPARING_SOURCE,
    PREPARING_DESTINATION,
    FINALIZING_SOURCE,
    PROXY_READY,
    COMMITTING,
    COMMITTED,
    ABORTED,
    FAILED
}
