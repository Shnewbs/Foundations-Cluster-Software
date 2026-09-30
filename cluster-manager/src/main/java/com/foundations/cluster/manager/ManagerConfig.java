package com.foundations.cluster.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class ManagerConfig {
    private final Path home;
    private final Path file;
    private final Properties p = new Properties();
    final ClusterLifecycle lifecycle = new ClusterLifecycle();

    static final Set<String> EDITABLE_KEYS = Set.of(
            "cluster.name","world.environment.enabled",
            "support.onTestFailure","support.intervalMinutes","support.keepCount","support.maxLogMiB","support.maxRuntimeMiB","support.maxArchiveMiB",
            "web.bind","web.port","manager.publicUrl",
            "template.autoBootstrap","template.neoforgeVersion","template.requireEula",
            "nodeAgent.required",
            "startup.autoStartWorkers","startup.workers",
            "worker.startPort","worker.bindAddress","worker.xms","worker.xmx","worker.extraJvmArgs","worker.evacuationGraceSeconds","worker.evacuationMigrationTimeoutSeconds","worker.stopGraceSeconds",
            "worker.heartbeatTimeoutSeconds","worker.registrationTimeoutSeconds","worker.retireTimeoutSeconds",
            "gateway.enabled","gateway.bind","gateway.port","gateway.backendHost","gateway.motd","gateway.minecraftVersion","gateway.protocolVersion",
            "gateway.maxConnections","gateway.connectTimeoutSeconds","gateway.handshakeTimeoutSeconds","gateway.statusMaxPlayers",
            "gateway.mode","gateway.seamless.enabled","gateway.velocity.autoBootstrap","gateway.velocity.setupTimeoutSeconds","gateway.velocity.xms","gateway.velocity.xmx","gateway.velocity.extraJvmArgs",
            "handoff.enabled","handoff.mode","handoff.legacyVelocityBackendSwitch","handoff.timeoutSeconds","handoff.preloadRadiusChunks","handoff.autoOnCellCrossing","handoff.transitionBeltChunks","handoff.maxCommandsPerHeartbeat",
            "dataplane.enabled","dataplane.pollMillis","dataplane.maxQueuePerWorker","dataplane.maxPayloadBytes","dataplane.maxFramesPerPoll","dataplane.frameTtlMillis","dataplane.retryMillis","dataplane.remoteBlockPrototype",
            "dataplane.chunkReplicationEnabled","dataplane.chunkSnapshotRadius","dataplane.chunkVerticalRadius","dataplane.chunkRequestIntervalMillis","dataplane.chunkCacheTtlMillis","dataplane.maxChunkRequestsPerCycle",
            "session.ttlSeconds","placement.softMaxMspt","placement.maxHeapPercent",
            "layer.enabled","layer.cellChunks","layer.targetPlayers","layer.splitPlayers","layer.mergePlayers",
            "layer.maxLayersPerCell","layer.splitMspt","layer.checkSeconds","layer.splitSustainSeconds",
            "layer.mergeSustainSeconds","layer.leaseSeconds","layer.autoSplit","layer.autoMerge","layer.partyAffinity",
            "layer.defaultWorld","layer.defaultDimension",
            "region.enabled","region.regionChunks","region.leaseSeconds","region.checkSeconds","region.recoverySeconds","region.startupRecoverySeconds","region.autoFailover",
            "persistence.blockJournalEnabled","persistence.blockEntityJournalEnabled","persistence.blockEntityScanIntervalTicks","persistence.blockEntityScanBudget","persistence.blockEntityMaxPayloadBytes","persistence.blockEntityCoalesceMillis","persistence.blockEntityCoalesceMaxMillis","persistence.checkpointSeconds",
            "playerState.restoreBasic","playerState.retentionDays","playerProfile.enabled","playerProfile.sharedFiles",
            "scaling.mode","scaling.checkSeconds","scaling.minWorkers","scaling.maxWorkers",
            "scaling.targetUtilization","scaling.highPlayersPerWorker","scaling.lowPlayersPerWorker","scaling.highMspt","scaling.highCpuPercent","scaling.highHeapPercent",
            "scaling.scaleUpSustainSeconds","scaling.scaleDownSustainSeconds","scaling.scaleUpCooldownSeconds","scaling.scaleDownCooldownSeconds","scaling.warmWorkers","scaling.maxConcurrentDrains",
            "scaling.scaleDownMigratePlayers","scaling.memory.enforceHardLimit","scaling.memory.totalBudgetGb","scaling.memory.systemReserveGb","scaling.memory.workerReservationGb","scaling.memory.jvmOverheadPercent",
            "world.seed","world.lockSeed","world.identity.enforce","world.identity.configKeywords",
            "shutdown.workerWaitSeconds","logs.maxDashboardLines","metrics.retentionSamples",
            "console.maxCommandLength","console.outputLines","console.historyLines",
            "alerts.heapWarnPercent","alerts.msptWarn","alerts.msptCritical",
            "security.rememberDays","security.sessionHours","security.idleHours"
    );

    static final Set<String> RESTART_REQUIRED_KEYS = Set.of("web.bind","web.port","manager.publicUrl","scaling.checkSeconds","layer.checkSeconds","region.checkSeconds","region.startupRecoverySeconds","persistence.blockJournalEnabled","persistence.blockEntityJournalEnabled","gateway.enabled","gateway.bind","gateway.port","gateway.mode","gateway.seamless.enabled","gateway.velocity.xms","gateway.velocity.xmx","gateway.velocity.extraJvmArgs");
    static final Set<String> NEW_WORKERS_ONLY_KEYS = Set.of("worker.startPort","worker.xms","worker.xmx","worker.extraJvmArgs","world.seed","world.lockSeed");
    static final Set<String> WORKER_RESTART_REQUIRED_KEYS = Set.of("worker.bindAddress","gateway.backendHost","dataplane.pollMillis","dataplane.remoteBlockPrototype","dataplane.chunkReplicationEnabled","dataplane.chunkSnapshotRadius","dataplane.chunkVerticalRadius","dataplane.chunkRequestIntervalMillis","dataplane.chunkCacheTtlMillis","dataplane.maxChunkRequestsPerCycle","persistence.blockJournalEnabled","persistence.blockEntityJournalEnabled","persistence.blockEntityScanIntervalTicks","persistence.blockEntityScanBudget","persistence.blockEntityMaxPayloadBytes","persistence.blockEntityCoalesceMillis","persistence.blockEntityCoalesceMaxMillis","persistence.checkpointSeconds","world.identity.configKeywords");

    private ManagerConfig(Path home, Path file) { this.home = home; this.file = file; }

    static ManagerConfig load() throws IOException {
        String env = System.getenv("FOUNDATIONS_CLUSTER_HOME");
        Path home = (env == null || env.isBlank()) ? Path.of("").toAbsolutePath().normalize() : Path.of(env).toAbsolutePath().normalize();
        return load(home);
    }

    static ManagerConfig load(Path home) throws IOException {
        Path file = home.resolve("config/cluster.properties");
        ManagerConfig c = new ManagerConfig(home, file);
        Path defaults = home.resolve("config/cluster.properties.default");
        if (Files.isRegularFile(defaults)) try (InputStream in = Files.newInputStream(defaults)) { c.p.load(in); }
        if (!Files.isRegularFile(file)) throw new IOException("Missing config: " + file);
        try (InputStream in = Files.newInputStream(file)) { c.p.load(in); }
        return c;
    }

    Path home() { return home; }
    String get(String key, String def) { return p.getProperty(key, def).trim(); }
    int getInt(String key, int def) { try { return Integer.parseInt(get(key, Integer.toString(def))); } catch (Exception e) { return def; } }
    long getLong(String key, long def) { try { return Long.parseLong(get(key, Long.toString(def))); } catch (Exception e) { return def; } }
    double getDouble(String key, double def) { try { return Double.parseDouble(get(key, Double.toString(def))); } catch (Exception e) { return def; } }
    boolean getBoolean(String key, boolean def) { String v = get(key, Boolean.toString(def)); return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes"); }
    Path resolve(String key, String def) { return home.resolve(get(key, def)).normalize(); }

    synchronized Map<String,String> editableSnapshot() {
        TreeMap<String,String> out = new TreeMap<>();
        for (String key : EDITABLE_KEYS) if (p.containsKey(key)) out.put(key, get(key, ""));
        return out;
    }

    synchronized UpdateResult updateEditable(Map<String,String> changes) throws IOException {
        LinkedHashMap<String,String> accepted = new LinkedHashMap<>();
        LinkedHashSet<String> restart = new LinkedHashSet<>();
        LinkedHashSet<String> newWorkers = new LinkedHashSet<>();
        LinkedHashSet<String> workerRestart = new LinkedHashSet<>();
        Properties before = new Properties(); before.putAll(p);
        Map<String,String> validated = new LinkedHashMap<>();
        for (var e : changes.entrySet()) {
            if (!EDITABLE_KEYS.contains(e.getKey())) throw new IllegalArgumentException("Setting is not editable: " + e.getKey());
            validated.put(e.getKey(), validate(e.getKey(), e.getValue()));
        }
        for (var e : validated.entrySet()) {
            String key = e.getKey();
            if (!EDITABLE_KEYS.contains(key)) throw new IllegalArgumentException("Setting is not editable from the dashboard: " + key);
            String value = validate(key, e.getValue());
            String old = get(key, "");
            if (!Objects.equals(old, value)) {
                p.setProperty(key, value);
                accepted.put(key, value);
                if (RESTART_REQUIRED_KEYS.contains(key)) restart.add(key);
                if (NEW_WORKERS_ONLY_KEYS.contains(key)) newWorkers.add(key);
                if (WORKER_RESTART_REQUIRED_KEYS.contains(key)) workerRestart.add(key);
            }
        }
        if (!accepted.isEmpty()) {
            try { saveAtomic(); } catch (IOException ex) { p.clear(); p.putAll(before); throw ex; }
        }
        return new UpdateResult(accepted, restart, newWorkers, workerRestart);
    }

    private String validate(String key, String raw) {
        String v = raw == null ? "" : raw.trim();
        if(key.equals("support.intervalMinutes"))return intRange(key,v,0,10080);
        if(key.equals("support.keepCount"))return intRange(key,v,1,50);
        if(key.equals("support.maxLogMiB"))return intRange(key,v,1,32);
        if(key.equals("support.maxRuntimeMiB"))return intRange(key,v,1,128);
        if(key.equals("support.maxArchiveMiB"))return intRange(key,v,16,512);
        if (key.equals("cluster.name")) {
            if (v.isBlank() || v.length() > 64) throw new IllegalArgumentException("Cluster name must be 1-64 characters.");
            return v;
        }
        if (key.equals("gateway.mode")) {
            v=v.toLowerCase(Locale.ROOT);
            if(!Set.of("velocity","transparent").contains(v)) throw new IllegalArgumentException("gateway.mode must be velocity or transparent.");
            return v;
        }
        if (key.equals("handoff.mode")) {
            v=v.toLowerCase(Locale.ROOT);
            if(!Set.of("persistent-session","velocity-switch").contains(v)) throw new IllegalArgumentException("handoff.mode must be persistent-session or velocity-switch.");
            return v;
        }
        if (key.equals("scaling.mode")) {
            v = v.toLowerCase(Locale.ROOT);
            if (!Set.of("manual","assisted","automatic").contains(v)) throw new IllegalArgumentException("Scaling mode must be manual, assisted, or automatic.");
            return v;
        }
        if (key.equals("worker.xms") || key.equals("worker.xmx") || key.equals("gateway.velocity.xms") || key.equals("gateway.velocity.xmx")) {
            if (!v.matches("(?i)\\d+[mg]")) throw new IllegalArgumentException(key + " must look like 4096M or 12G.");
            return v.toUpperCase(Locale.ROOT);
        }
        if (key.equals("web.bind") || key.equals("gateway.bind") || key.equals("gateway.backendHost") || key.equals("worker.bindAddress") || key.equals("gateway.motd") || key.equals("gateway.minecraftVersion") || key.equals("manager.publicUrl") || key.equals("template.neoforgeVersion") || key.equals("worker.extraJvmArgs") || key.equals("gateway.velocity.extraJvmArgs") || key.equals("layer.defaultWorld") || key.equals("layer.defaultDimension") || key.equals("playerProfile.sharedFiles") || key.equals("world.seed") || key.equals("world.identity.configKeywords")) {
            if (v.length() > 512) throw new IllegalArgumentException(key + " is too long.");
            return v;
        }
        if (key.equals("support.onTestFailure") || key.equals("world.environment.enabled") || key.startsWith("template.") || key.startsWith("nodeAgent.") || key.startsWith("startup.auto") || key.equals("gateway.enabled") || key.equals("gateway.seamless.enabled") || key.equals("gateway.velocity.autoBootstrap") || key.equals("handoff.enabled") || key.equals("handoff.legacyVelocityBackendSwitch") || key.equals("handoff.autoOnCellCrossing") || key.equals("dataplane.enabled") || key.equals("dataplane.remoteBlockPrototype") || key.equals("dataplane.chunkReplicationEnabled") || key.equals("persistence.blockJournalEnabled") || key.equals("persistence.blockEntityJournalEnabled") || key.equals("playerProfile.enabled") || key.equals("layer.enabled") || key.equals("layer.autoSplit") || key.equals("layer.autoMerge") || key.equals("layer.partyAffinity") || key.equals("scaling.scaleDownMigratePlayers") || key.equals("scaling.memory.enforceHardLimit") || key.equals("world.lockSeed") || key.equals("world.identity.enforce")) {
            if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes")) return "true";
            if (v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("no")) return "false";
            throw new IllegalArgumentException(key + " must be true or false.");
        }
        if (key.equals("web.port") || key.equals("worker.startPort") || key.equals("gateway.port")) return intRange(key, v, 1, 65535);
        if (key.equals("gateway.protocolVersion")) return intRange(key, v, 1, 2_000_000_000);
        if (key.equals("gateway.maxConnections")) return intRange(key, v, 1, 100000);
        if (key.endsWith("Percent")) return doubleRange(key, v, 1, 100);
        if (key.equals("placement.softMaxMspt") || key.equals("scaling.highMspt") || key.equals("layer.splitMspt") || key.startsWith("alerts.mspt")) return doubleRange(key, v, 1, 10000);
        if (key.equals("scaling.targetUtilization")) return doubleRange(key, v, 0.50, 0.95);
        if (key.equals("scaling.memory.totalBudgetGb") || key.equals("scaling.memory.systemReserveGb") || key.equals("scaling.memory.workerReservationGb")) return doubleRange(key, v, 0, 1048576);
        if (key.equals("scaling.memory.jvmOverheadPercent")) return doubleRange(key, v, 0, 200);
        if (key.equals("scaling.maxWorkers")) return intRange(key, v, 1, 4096);
        if (key.equals("console.maxCommandLength")) return intRange(key, v, 64, 16384);
        if (key.equals("console.outputLines")) return intRange(key, v, 50, 5000);
        if (key.equals("console.historyLines")) return intRange(key, v, 10, 1000);
        if (key.equals("layer.cellChunks")) return intRange(key, v, 1, 1024);
        if (key.equals("layer.maxLayersPerCell")) return intRange(key, v, 1, 256);
        if (key.equals("layer.targetPlayers") || key.equals("layer.splitPlayers")) return intRange(key, v, 1, 100000);
        if (key.equals("layer.mergePlayers")) return intRange(key, v, 0, 100000);
        if (key.equals("scaling.minWorkers") || key.equals("scaling.warmWorkers") || key.equals("startup.workers")) return intRange(key, v, 0, 4096);
        if (key.equals("scaling.maxConcurrentDrains")) return intRange(key, v, 1, 128);
        if (key.equals("dataplane.pollMillis")) return intRange(key, v, 20, 5000);
        if (key.equals("dataplane.maxQueuePerWorker")) return intRange(key, v, 32, 1000000);
        if (key.equals("dataplane.maxPayloadBytes")) return intRange(key, v, 1024, 16777216);
        if (key.equals("dataplane.maxFramesPerPoll")) return intRange(key, v, 1, 256);
        if (key.equals("handoff.maxCommandsPerHeartbeat")) return intRange(key, v, 1, 1024);
        if (key.equals("dataplane.chunkSnapshotRadius")) return intRange(key, v, 0, 8);
        if (key.equals("dataplane.chunkVerticalRadius")) return intRange(key, v, 16, 384);
        if (key.equals("dataplane.maxChunkRequestsPerCycle")) return intRange(key, v, 1, 64);
        if (key.equals("dataplane.chunkRequestIntervalMillis") || key.equals("dataplane.chunkCacheTtlMillis")) return intRange(key, v, 100, 600000);
        if (key.equals("dataplane.frameTtlMillis") || key.equals("dataplane.retryMillis")) return intRange(key, v, 50, 600000);
        if (key.equals("persistence.blockEntityScanIntervalTicks")) return intRange(key, v, 1, 1200);
        if (key.equals("persistence.blockEntityScanBudget")) return intRange(key, v, 32, 100000);
        if (key.equals("persistence.blockEntityCoalesceMillis")) return intRange(key, v, 250, 600000);
        if (key.equals("persistence.blockEntityCoalesceMaxMillis")) return intRange(key, v, 1000, 3600000);
        if (key.equals("persistence.blockEntityMaxPayloadBytes")) return intRange(key, v, 65536, 16777216);
        if (key.contains("Seconds") || key.contains("Hours") || key.contains("Days") || key.contains("Players") || key.contains("Samples") || key.equals("session.ttlSeconds") || key.equals("logs.maxDashboardLines")) return intRange(key, v, 0, 2_000_000_000);
        if (v.isBlank()) throw new IllegalArgumentException(key + " may not be blank.");
        return v;
    }

    private static String intRange(String key, String v, int min, int max) {
        try { int n=Integer.parseInt(v); if(n<min||n>max) throw new NumberFormatException(); return Integer.toString(n); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key+" must be between "+min+" and "+max+"."); }
    }
    private static String doubleRange(String key, String v, double min, double max) {
        try { double n=Double.parseDouble(v); if(!Double.isFinite(n)||n<min||n>max) throw new NumberFormatException(); return Double.toString(n); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key+" must be between "+min+" and "+max+"."); }
    }

    private void saveAtomic() throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName()+".tmp");
        List<String> keys = p.stringPropertyNames().stream().sorted().toList();
        StringBuilder b = new StringBuilder("# Foundations Cluster Runtime dashboard-managed configuration\n");
        for (String k : keys) b.append(k).append('=').append(escape(p.getProperty(k))).append('\n');
        Files.writeString(tmp, b, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static String escape(String v) { return v.replace("\\","\\\\").replace("\n","\\n").replace("\r","\\r"); }

    record UpdateResult(Map<String,String> changed, Set<String> restartRequired, Set<String> newWorkersOnly, Set<String> workerRestartRequired) {}
}
