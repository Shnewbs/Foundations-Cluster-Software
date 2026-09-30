package com.foundations.cluster.manager;

import java.lang.management.ManagementFactory;
import java.util.Collection;
import java.util.Locale;

/**
 * Host/JVM memory guardrail used by both manual worker creation and the 0.0.6a autoscaler.
 * The budget is intentionally conservative: -Xmx is not treated as the JVM's whole process size.
 */
final class CapacityBudget {
    static final long GIB = 1024L * 1024L * 1024L;

    record Snapshot(long physicalBytes,
                    long configuredBudgetBytes,
                    long usableBytes,
                    long systemReserveBytes,
                    long workerReservationBytes,
                    int configuredMaxWorkers,
                    int memoryMaxWorkers,
                    int effectiveMaxWorkers,
                    int runningWorkers,
                    int availableSlots,
                    boolean hardLimit,
                    String source) {}

    private CapacityBudget() {}

    static Snapshot snapshot(ManagerConfig cfg, Collection<WorkerRecord> workers) {
        long physical = physicalMemoryBytes();
        double configuredBudgetGb = Math.max(0, cfg.getDouble("scaling.memory.totalBudgetGb", 0));
        long configuredBudget = configuredBudgetGb > 0 ? gib(configuredBudgetGb) : physical;
        String source = configuredBudgetGb > 0 ? "configured" : (physical > 0 ? "physical" : "unknown");

        double reserveGb = Math.max(0, cfg.getDouble("scaling.memory.systemReserveGb", 16));
        long reserve = gib(reserveGb);

        double explicitWorkerGb = Math.max(0, cfg.getDouble("scaling.memory.workerReservationGb", 0));
        long workerReservation;
        if (explicitWorkerGb > 0) {
            workerReservation = gib(explicitWorkerGb);
        } else {
            long heap = parseJvmMemory(cfg.get("worker.xmx", "12G"));
            double overheadPct = Math.max(0, cfg.getDouble("scaling.memory.jvmOverheadPercent", 20));
            long overhead = Math.max(GIB, (long)Math.ceil(heap * (overheadPct / 100.0)));
            workerReservation = Math.max(GIB, heap + overhead);
        }

        int configuredMax = Math.max(1, cfg.getInt("scaling.maxWorkers", 64));
        long usable = configuredBudget > 0 ? Math.max(0, configuredBudget - reserve) : 0;
        int memoryMax;
        if (configuredBudget <= 0 || workerReservation <= 0) memoryMax = Integer.MAX_VALUE;
        else memoryMax = (int)Math.min(Integer.MAX_VALUE, usable / workerReservation);
        int effective = memoryMax == Integer.MAX_VALUE ? configuredMax : Math.max(0, Math.min(configuredMax, memoryMax));

        int running = 0;
        for (WorkerRecord w : workers) if (consumesMemory(w)) running++;
        int available = effective == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, effective - running);
        boolean hard = cfg.getBoolean("scaling.memory.enforceHardLimit", true);
        return new Snapshot(physical, configuredBudget, usable, reserve, workerReservation, configuredMax, memoryMax, effective, running, available, hard, source);
    }

    static boolean consumesMemory(WorkerRecord w) {
        if (w == null) return false;
        return switch (w.state) {
            case OFFLINE, RETIRED, CRASHED, KILLED, ERROR -> false;
            default -> true;
        };
    }

    static void requireSpawnCapacity(ManagerConfig cfg, Collection<WorkerRecord> workers) {
        Snapshot s = snapshot(cfg, workers);
        if (!s.hardLimit()) return;
        if (s.effectiveMaxWorkers() <= 0) {
            throw new IllegalStateException("Worker creation blocked by memory budget: no JVM slot remains after the configured system reserve.");
        }
        if (s.runningWorkers() >= s.effectiveMaxWorkers()) {
            throw new IllegalStateException("Worker creation blocked by memory budget: running=" + s.runningWorkers()
                    + " effectiveMax=" + s.effectiveMaxWorkers()
                    + " workerReservation=" + formatGiB(s.workerReservationBytes())
                    + " usable=" + formatGiB(s.usableBytes()) + ".");
        }
    }

    static long parseJvmMemory(String value) {
        if (value == null || value.isBlank()) return 0;
        String v = value.trim().toUpperCase(Locale.ROOT);
        try {
            long mult = 1;
            if (v.endsWith("G")) { mult = GIB; v = v.substring(0, v.length()-1); }
            else if (v.endsWith("M")) { mult = 1024L * 1024L; v = v.substring(0, v.length()-1); }
            else if (v.endsWith("K")) { mult = 1024L; v = v.substring(0, v.length()-1); }
            return Math.max(0, Long.parseLong(v) * mult);
        } catch (Exception e) { return 0; }
    }

    private static long gib(double gb) {
        if (!Double.isFinite(gb) || gb <= 0) return 0;
        double b = gb * GIB;
        return b >= Long.MAX_VALUE ? Long.MAX_VALUE : (long)Math.floor(b);
    }

    static String formatGiB(long bytes) {
        if (bytes <= 0) return "0 GiB";
        return String.format(Locale.ROOT, "%.1f GiB", bytes / (double)GIB);
    }

    private static long physicalMemoryBytes() {
        try {
            var bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
                long n = os.getTotalMemorySize();
                if (n > 0) return n;
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}
