package com.foundations.cluster.manager;

import java.util.*;

/** Bounded metadata-only trace; never accepts packet bytes or player profiles. */
final class TransferTrace {
    private final Deque<Entry> entries = new ArrayDeque<>();
    private static final Set<String> STAGES = Set.of("SWITCH_REQUEST", "BACKEND_CONNECTED", "SWITCH_ACCEPTED", "SWITCH_REJECTED", "COMMIT_ACK", "ABORT", "CONFIG_PAYLOAD", "CONFIG_FINISH", "BACKEND_DISCONNECT");
    record Entry(long time, String handoffId, String stage, long elapsedMillis, String detail) {}
    synchronized void add(String handoffId, String stage, long elapsed, String detail) {
        if (!STAGES.contains(stage)) throw new IllegalArgumentException("Unknown trace stage");
        if (handoffId == null || !handoffId.matches("handoff-[a-zA-Z0-9-]{1,80}")) throw new IllegalArgumentException("Invalid handoff ID");
        entries.addLast(new Entry(System.currentTimeMillis(), handoffId, stage, Math.max(0,elapsed), DiagnosticBundle.redact(detail == null ? "" : detail).substring(0, Math.min(512,DiagnosticBundle.redact(detail == null ? "" : detail).length()))));
        while (entries.size() > 1000) entries.removeFirst();
    }
    synchronized String json() {
        var out = new StringJoiner(",", "{\"events\":[", "]}");
        for (Entry e : entries) out.add("{\"time\":"+e.time()+",\"handoffId\":"+Json.q(e.handoffId())+",\"stage\":"+Json.q(e.stage())+",\"elapsedMillis\":"+e.elapsedMillis()+",\"detail\":"+Json.q(e.detail())+"}");
        return out.toString();
    }
}
