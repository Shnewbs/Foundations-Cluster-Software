package com.foundations.cluster.manager;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Local host measurements. Unknown platform counters are null, never invented. */
final class HostTelemetry {
    static String snapshot(Path root) {
        var os = ManagementFactory.getOperatingSystemMXBean();
        long total = -1, free = -1, swap = -1, freeSwap = -1;
        double cpu = -1;
        if (os instanceof com.sun.management.OperatingSystemMXBean host) {
            total = host.getTotalMemorySize(); free = host.getFreeMemorySize();
            swap = host.getTotalSwapSpaceSize(); freeSwap = host.getFreeSwapSpaceSize();
            cpu = host.getCpuLoad() * 100;
        }
        long diskTotal = -1, diskFree = -1;
        try { var store = Files.getFileStore(root); diskTotal = store.getTotalSpace(); diskFree = store.getUsableSpace(); }
        catch (Exception ignored) {}
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return "{\"id\":\"local\",\"scope\":\"local-host\",\"os\":" + Json.q(os.getName())
            + ",\"arch\":" + Json.q(os.getArch()) + ",\"processors\":" + os.getAvailableProcessors()
            + ",\"cpuPercent\":" + number(cpu) + ",\"memoryTotal\":" + number(total)
            + ",\"memoryFree\":" + number(free) + ",\"swapTotal\":" + number(swap)
            + ",\"swapFree\":" + number(freeSwap) + ",\"diskTotal\":" + number(diskTotal)
            + ",\"diskFree\":" + number(diskFree) + ",\"managerHeapUsed\":" + heap.getUsed()
            + ",\"managerHeapMax\":" + heap.getMax() + ",\"managerThreads\":" + ManagementFactory.getThreadMXBean().getThreadCount()
            + ",\"managerUptimeMillis\":" + ManagementFactory.getRuntimeMXBean().getUptime()
            + ",\"javaVersion\":" + Json.q(System.getProperty("java.version"))
            + ",\"networkBytesPerSecond\":null,\"diskLatencyMillis\":null,\"temperatureC\":null}";
    }
    private static String number(long value) { return value < 0 ? "null" : Long.toString(value); }
    private static String number(double value) { return !Double.isFinite(value) || value < 0 ? "null" : String.format(Locale.ROOT,"%.2f",value); }
}
