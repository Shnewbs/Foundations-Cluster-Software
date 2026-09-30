package com.foundations.cluster.manager;

import java.util.concurrent.atomic.AtomicReference;

/** One monotonic shutdown gate shared by every coordinator, including JVM exit. */
final class ClusterLifecycle {
    record Shutdown(long at, String reason) {}
    private final AtomicReference<Shutdown> shutdown = new AtomicReference<>();
    boolean beginShutdown(String reason) {
        return shutdown.compareAndSet(null, new Shutdown(System.currentTimeMillis(), reason));
    }
    boolean shuttingDown() { return shutdown.get() != null; }
    String state() { return shuttingDown() ? "SHUTTING_DOWN" : "RUNNING"; }
    void requireRunning() {
        if (shuttingDown()) throw new IllegalStateException("Cluster is shutting down; topology changes are closed");
    }
}
