package com.foundations.cluster.replication;

/** Spike C serialization boundary. Never rewrites a level's local game time. */
public final class ContinuationTime {
    private ContinuationTime() {}
    public static long exportRemaining(long dueAt, long sourceGameTime) {
        if (dueAt < 0 || sourceGameTime < 0) throw new IllegalArgumentException("Negative game time");
        return dueAt <= sourceGameTime ? 0 : dueAt - sourceGameTime;
    }
    public static long importDueAt(long remaining, long destinationGameTime) {
        if (remaining < 0 || destinationGameTime < 0) throw new IllegalArgumentException("Negative delay/time");
        return Math.addExact(destinationGameTime, remaining);
    }
}
