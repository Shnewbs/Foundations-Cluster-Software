package com.foundations.cluster.manager;

import java.io.BufferedWriter;

final class WorkerRecord {
    volatile int pendingWrites,failedWrites;
    volatile String persistenceInstance="";
    volatile long lastDurableWrite;
    final String id;
    final int port;
    final boolean managed;
    final long createdAtMillis = System.currentTimeMillis();

    volatile WorkerState state = WorkerState.STARTING;
    volatile boolean draining;
    // An evacuation/stop fence is temporary; a manual drain is an operator policy.
    // Null means no lifecycle fence is pending. Access under this worker's monitor.
    Boolean drainBeforeStop;

    synchronized void fenceForStop() {
        if(drainBeforeStop==null)drainBeforeStop=draining;
        draining=true;
    }
    synchronized void manualDrain(boolean value) {
        if(drainBeforeStop!=null)drainBeforeStop=value; // Update intent without lifting an active stop fence.
        else draining=value;
    }
    synchronized boolean drainAfterRestart() { return drainBeforeStop==null?draining:drainBeforeStop; }
    synchronized void releaseStopFence() {
        if(drainBeforeStop!=null){draining=drainBeforeStop;drainBeforeStop=null;}
    }

    volatile boolean warm;
    volatile boolean pinned;
    volatile boolean maintenance;
    volatile boolean ready;
    // R9: true when a stored worker is alive/heartbeating but its JVM is not attached to this Manager process.
    volatile boolean controlDetached;

    volatile long registeredAtMillis;
    volatile long lastHeartbeatMillis;
    volatile int players;
    volatile int maxPlayers;
    volatile double mspt;
    volatile double msptP95;
    volatile double msptP99;
    volatile long heapUsed;
    volatile long heapMax;
    volatile int loadedChunks;
    volatile int dimensions;
    volatile int threadCount;
    volatile long uptimeMillis;
    volatile String javaVersion = "";
    volatile String minecraftVersion = "";
    volatile String nodeVersion = "";
    volatile boolean environmentReady;
    volatile long environmentLeaseUntilMillis;

    // 0.0.6a world identity telemetry. New workers are created from the locked cluster seed.
    volatile long worldSeed;
    volatile boolean worldSeedKnown;
    volatile boolean worldSeedMatch = true;
    volatile String worldIdentity = "";
    volatile int worldIdentitySchema = 1;
    volatile boolean worldIdentityMatch = true;

    volatile double cpuPercent;
    volatile long pid = -1;
    volatile Process process;
    volatile BufferedWriter stdin;
    volatile long lastCpuNanos;
    volatile long lastCpuSampleNanos;

    // R7.R6 graceful-stop/evacuation status exposed to the dashboard.
    volatile boolean gracefulStopPending;
    volatile long gracefulStopStartedAtMillis;
    volatile long gracefulStopDeadlineMillis;
    volatile int gracefulStopInitialPlayers;
    volatile int gracefulStopMigratedPlayers;
    volatile String gracefulStopMessage = "";

    WorkerRecord(String id, int port, boolean managed) {
        this.id = id;
        this.port = port;
        this.managed = managed;
    }

    long heartbeatAgeSeconds() {
        long t = lastHeartbeatMillis;
        return t == 0 ? -1 : Math.max(0, (System.currentTimeMillis() - t) / 1000);
    }

    boolean routableForNewSession() {
        return ready && !draining && !maintenance && (state == WorkerState.READY || state == WorkerState.ACTIVE);
    }

    boolean usableForExistingSession() {
        return ready && state != WorkerState.OFFLINE && state != WorkerState.CRASHED && state != WorkerState.ERROR
                && state != WorkerState.UNHEALTHY && state != WorkerState.STOPPING && state != WorkerState.KILLED && state != WorkerState.RETIRED;
    }
}
