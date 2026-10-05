package com.foundations.cluster.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.security.SecureRandom;

final class WorkerManager implements AutoCloseable {
    record OrphanProcess(long pid, String workerId, int port, String command) {}
    private final ManagerConfig cfg;
    private final TokenStore tokens;
    private final ClusterLog log;
    private final TemplateManager templates;
    private final NodeAgentManager nodeAgent;
    private final ConcurrentHashMap<String, WorkerRecord> workers = new ConcurrentHashMap<>();
    private final AtomicInteger sequence;
    private final Path canonicalSeedFile;
    private final Path canonicalIdentityFile;
    private final boolean existingWorldAtStartup;
    private volatile Long canonicalWorldSeed;
    private volatile String canonicalWorldIdentity = "";
    private volatile int canonicalWorldIdentitySchema = 1;
    private volatile List<OrphanProcess> orphanCache = List.of();
    private volatile long orphanCacheMillis;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "foundations-cluster-monitor"); t.setDaemon(true); return t;
    });

    WorkerManager(ManagerConfig cfg, TokenStore tokens, ClusterLog log, TemplateManager templates, NodeAgentManager nodeAgent) throws IOException {
        this.cfg = cfg; this.tokens = tokens; this.log = log; this.templates = templates; this.nodeAgent = nodeAgent;
        Path instances = cfg.resolve("worker.instancesDir", "workers");
        Files.createDirectories(instances);
        loadStoredWorkers(instances);
        loadPins();
        this.sequence = new AtomicInteger(findNextSequence(instances));
        this.canonicalSeedFile = cfg.home().resolve("runtime/world-seed.txt");
        this.canonicalIdentityFile = cfg.home().resolve("runtime/world-identity.txt");
        this.existingWorldAtStartup = detectExistingWorld(instances);
        initializeCanonicalSeed();
        initializeCanonicalIdentity();
        scheduler.scheduleAtFixedRate(this::monitor, 1, 1, TimeUnit.SECONDS);
    }

    Collection<WorkerRecord> all() { return new ArrayList<>(workers.values()); }
    WorkerRecord get(String id) { return workers.get(id); }

    synchronized WorkerRecord spawn(boolean warm) throws IOException {
        cfg.lifecycle.requireRunning();
        Path template = cfg.resolve("worker.templateDir", "server-template");
        if (!Files.isDirectory(template)) throw new IOException("Server template missing: " + template);
        if (templates.installing()) throw new IOException("NeoForge server template is still installing.");
        if (!templates.hasNeoForgeArgs()) throw new IOException("NeoForge server template is not installed yet.");
        if (!templates.eulaAccepted() && cfg.getBoolean("template.requireEula", true)) throw new IOException("Minecraft EULA has not been accepted for server-template.");
        if (nodeAgent.required() && !nodeAgent.installed()) throw new IOException("Foundations Cluster Node is not installed in server-template/mods yet.");
        if (cfg.getBoolean("world.lockSeed", true) && !worldSeedReady()) throw new IOException("Cluster seed is waiting to be adopted from an existing worker. Start one stored worker first; 0.0.6a will lock its actual seed for all future JVMs.");
        CapacityBudget.requireSpawnCapacity(cfg, workers.values());
        ensureVelocityForwardingReady(null); // Validate before copying a template or reserving a worker ID.

        int number = nextFreeSequence();
        String id = "worker-%03d".formatted(number);
        int port = nextAvailablePort();
        Path dir = cfg.resolve("worker.instancesDir", "workers").resolve(id);
        copyTree(template, dir);
        syncClusterRuntimeFiles(dir);
        ensureVelocityForwardingReady(dir);
        configureServerProperties(dir.resolve("server.properties"), port, id);
        writeJvmArgs(dir.resolve("user_jvm_args.txt"));

        WorkerRecord w = new WorkerRecord(id, port, true);
        w.warm = warm;
        w.state = WorkerState.BOOTING;
        workers.put(id, w);
        try {
            List<String> cmd = buildLaunchCommand(dir, w.id, w.port);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Map<String,String> env = pb.environment();
            env.put("FOUNDATIONS_CLUSTER_MANAGER_URL", cfg.get("manager.publicUrl", "http://127.0.0.1:" + cfg.getInt("web.port", 8765)));
            env.put("FOUNDATIONS_CLUSTER_AGENT_TOKEN", tokens.agentToken);
            env.put("FOUNDATIONS_CLUSTER_WORKER_ID", id);
            env.put("FOUNDATIONS_CLUSTER_WORKER_PORT", Integer.toString(port));
            env.put("FOUNDATIONS_CLUSTER_WARM", Boolean.toString(warm));
            env.put("FOUNDATIONS_EXPECTED_WORLD_SEED", Long.toString(expectedWorldSeed()));
            env.put("FOUNDATIONS_EXPECTED_WORLD_SEED_KNOWN", Boolean.toString(worldSeedReady()));
            env.put("FOUNDATIONS_EXPECTED_WORLD_IDENTITY", expectedWorldIdentity());
            env.put("FOUNDATIONS_EXPECTED_WORLD_IDENTITY_SCHEMA", Integer.toString(expectedWorldIdentitySchema()));
            env.put("FOUNDATIONS_WORLD_IDENTITY_KEYWORDS", cfg.get("world.identity.configKeywords", "world,worldgen,terrain,biome,generation,climate,tfc"));
            env.put("FOUNDATIONS_DATAPLANE_POLL_MILLIS", cfg.get("dataplane.pollMillis", "50"));
            env.put("FOUNDATIONS_REMOTE_BLOCK_PROTOTYPE", cfg.get("dataplane.remoteBlockPrototype", "true"));
            env.put("FOUNDATIONS_CHUNK_REPLICATION_ENABLED", cfg.get("dataplane.chunkReplicationEnabled", "true"));
            env.put("FOUNDATIONS_AUTHORITY_RANDOM_TICKS", cfg.get("dataplane.authorityRandomTicks.enabled", "true"));
            env.put("FOUNDATIONS_REMOTE_TICK_TTL_MILLIS", cfg.get("dataplane.authorityRandomTicks.ttlMillis", "20000"));
            env.put("FOUNDATIONS_REMOTE_TICK_LIMIT", cfg.get("dataplane.authorityRandomTicks.maxChunks", "128"));
            env.put("FOUNDATIONS_CHUNK_SNAPSHOT_RADIUS", cfg.get("dataplane.chunkSnapshotRadius", "1"));
            env.put("FOUNDATIONS_CHUNK_VERTICAL_RADIUS", cfg.get("dataplane.chunkVerticalRadius", "96"));
            env.put("FOUNDATIONS_CHUNK_REQUEST_INTERVAL_MILLIS", cfg.get("dataplane.chunkRequestIntervalMillis", "1000"));
            env.put("FOUNDATIONS_CHUNK_CACHE_TTL_MILLIS", cfg.get("dataplane.chunkCacheTtlMillis", "5000"));
            env.put("FOUNDATIONS_MAX_CHUNK_REQUESTS_PER_CYCLE", cfg.get("dataplane.maxChunkRequestsPerCycle", "2"));
            env.put("FOUNDATIONS_REGION_CHUNKS", cfg.get("region.regionChunks", "32"));
            env.put("FOUNDATIONS_PERSISTENCE_ENABLED", cfg.get("persistence.blockJournalEnabled", "true"));
            env.put("FOUNDATIONS_BLOCK_ENTITY_PERSISTENCE_ENABLED", cfg.get("persistence.blockEntityJournalEnabled", "true"));
            env.put("FOUNDATIONS_BLOCK_ENTITY_SCAN_INTERVAL_TICKS", cfg.get("persistence.blockEntityScanIntervalTicks", "40"));
            env.put("FOUNDATIONS_BLOCK_ENTITY_SCAN_BUDGET", cfg.get("persistence.blockEntityScanBudget", "256"));
            env.put("FOUNDATIONS_BLOCK_ENTITY_MAX_PAYLOAD_BYTES", cfg.get("persistence.blockEntityMaxPayloadBytes", "8388608"));
            env.put("FOUNDATIONS_BLOCK_ENTITY_COALESCE_MILLIS", cfg.get("persistence.blockEntityCoalesceMillis", "5000"));
            env.put("FOUNDATIONS_BLOCK_ENTITY_COALESCE_MAX_MILLIS", cfg.get("persistence.blockEntityCoalesceMaxMillis", "300000"));
            env.put("FOUNDATIONS_PERSISTENCE_CHECKPOINT_SECONDS", cfg.get("persistence.checkpointSeconds", "30"));
            Process p = pb.start();
            w.process = p; w.pid = p.pid();
            w.controlDetached = false;
            w.stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            pumpOutput(w, p.getInputStream());
            p.onExit().thenAccept(proc -> {
                if (w.state == WorkerState.KILLED || w.state == WorkerState.RETIRED) {
                    w.ready = false;
                    log.warn(id + " process exited after " + w.state + " with code " + proc.exitValue());
                } else if (!cfg.lifecycle.shuttingDown() && w.state != WorkerState.STOPPING && w.state != WorkerState.OFFLINE) {
                    w.ready = false;
                    w.state = proc.exitValue() == 0 ? WorkerState.OFFLINE : WorkerState.CRASHED;
                    log.warn(id + " exited with code " + proc.exitValue());
                } else {
                    w.ready = false;
                    w.state = WorkerState.OFFLINE;
                    log.info(id + " stopped cleanly");
                }
            });
            log.info("Spawned " + id + " pid=" + w.pid + " port=" + port + (warm ? " warm=true" : ""));
            return w;
        } catch (IOException e) {
            workers.remove(id);
            throw e;
        }
    }

    private void loadStoredWorkers(Path instances) {
        try (var stream = Files.list(instances)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String id = dir.getFileName().toString();
                if (!id.matches("worker-\\d+")) continue;
                int port = readStoredPort(dir.resolve("server.properties"), -1);
                if (port < 1) continue;
                WorkerRecord w = new WorkerRecord(id, port, true);
                w.state = WorkerState.OFFLINE;
                w.ready = false;
                workers.putIfAbsent(id, w);
                log.info("Discovered stored worker " + id + " port=" + port + " state=OFFLINE");
            }
        } catch (IOException e) { log.warn("Could not scan stored workers: " + e.getMessage()); }
    }

    private int readStoredPort(Path properties, int fallback) {
        if (!Files.isRegularFile(properties)) return fallback;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(properties)) { p.load(in); return Integer.parseInt(p.getProperty("server-port", Integer.toString(fallback)).trim()); }
        catch (Exception e) { return fallback; }
    }

    private int findNextSequence(Path instances) {
        int max = 0;
        try (var s = Files.list(instances)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.matches("worker-\\d+")) {
                    try { max = Math.max(max, Integer.parseInt(n.substring(7))); } catch (Exception ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return max + 1;
    }

    private int nextFreeSequence() {
        Path base = cfg.resolve("worker.instancesDir", "workers");
        while (true) {
            int n = sequence.getAndIncrement();
            String id = "worker-%03d".formatted(n);
            if (!Files.exists(base.resolve(id)) && !workers.containsKey(id)) return n;
        }
    }

    private int nextAvailablePort() throws IOException {
        int p = cfg.getInt("worker.startPort", 25566);
        Set<Integer> used = new HashSet<>();
        for (WorkerRecord w : workers.values()) used.add(w.port);
        used.add(cfg.getInt("web.port", 8765));
        if (cfg.getBoolean("gateway.enabled", true)) used.add(cfg.getInt("gateway.port", 25565));

        while (p <= 65535) {
            if (used.contains(p)) {
                p++;
                continue;
            }
            if (isPortBindable(p)) return p;
            log.warn("Worker port " + p + " is already in use by another process; skipping it.");
            p++;
        }
        throw new IOException("No free worker port is available from worker.startPort through 65535.");
    }

    static boolean isPortBindable(int port) {
        if (port < 1 || port > 65535) return false;
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private List<String> buildLaunchCommand(Path dir, String workerId, int port) throws IOException {
        Path base = dir.resolve("libraries/net/neoforged/neoforge");
        String argsName = isWindows() ? "win_args.txt" : "unix_args.txt";
        if (!Files.isDirectory(base)) throw new IOException("NeoForge launch libraries missing in worker clone.");
        Path args;
        try (var s = Files.walk(base, 4)) {
            args = s.filter(p -> p.getFileName().toString().equals(argsName)).findFirst()
                    .orElseThrow(() -> new IOException(argsName + " not found in worker clone"));
        }
        String javaExe = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        String rel = dir.relativize(args).toString();
        return List.of(javaExe,
                "-Dfoundations.cluster.managed=true",
                "-Dfoundations.cluster.workerId=" + workerId,
                "-Dfoundations.cluster.port=" + port,
                "-Dfoundations.cluster.home=" + cfg.home().toAbsolutePath().normalize(),
                "@user_jvm_args.txt", "@" + rel, "nogui");
    }

    private void writeJvmArgs(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("-Xms" + cfg.get("worker.xms", "4G"));
        lines.add("-Xmx" + cfg.get("worker.xmx", "12G"));
        for (String s : cfg.get("worker.extraJvmArgs", "").split("\\|")) if (!s.isBlank()) lines.add(s.trim());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private void configureServerProperties(Path file, int port, String id) throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(file)) try (InputStream in = Files.newInputStream(file)) { p.load(in); }
        p.setProperty("server-port", Integer.toString(port));
        p.setProperty("server-ip", cfg.get("worker.bindAddress", cfg.getBoolean("gateway.enabled", true) ? "127.0.0.1" : ""));
        if (cfg.get("gateway.mode","velocity").equalsIgnoreCase("velocity")) p.setProperty("online-mode", "false");
        p.setProperty("level-name", "world-" + id);
        if (cfg.getBoolean("world.lockSeed", true) && worldSeedReady()) p.setProperty("level-seed", Long.toString(expectedWorldSeed()));
        p.setProperty("motd", "Foundations backend " + id + " — connect through gateway :" + cfg.getInt("gateway.port", 25565));
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "Managed by Foundations Cluster Runtime 0.0.6a. Backend workers are internal; connect through the Foundations Gateway.");
        }
    }

    private void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                if (!rel.toString().isEmpty() && (rel.startsWith(".bootstrap") || rel.startsWith("logs") || rel.startsWith("crash-reports"))) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.resolve(rel));
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void pumpOutput(WorkerRecord w, InputStream in) {
        Thread.ofVirtual().name("worker-log-" + w.id).start(() -> {
            Path f = cfg.home().resolve("logs/workers/" + w.id + ".log");
            try {
                Files.createDirectories(f.getParent());
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                     BufferedWriter out = Files.newBufferedWriter(f, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        out.write(line); out.newLine(); out.flush();
                        if (line.contains("ERROR") || line.contains("FATAL")) log.warn("[" + w.id + "] " + line);
                    }
                }
            } catch (IOException e) { log.warn("Log pump ended for " + w.id + ": " + e.getMessage()); }
        });
    }

    void drain(String id, boolean value) {
        WorkerRecord w = require(id);
        synchronized (w) {
        w.manualDrain(value);
        updateHealthyState(w);
        log.info((value ? "Draining " : "Undraining ") + id + (w.drainBeforeStop!=null?" (preference saved; stop fence remains active)":""));
        }
    }

    void drainForStop(String id) {
        WorkerRecord w=require(id);
        synchronized (w) {
        w.fenceForStop();
        updateHealthyState(w);
        log.info("Evacuation drain enabled for "+id);
        }
    }

    void releaseStopDrain(String id) {
        WorkerRecord w=require(id);
        synchronized (w) {
        w.releaseStopFence();
        updateHealthyState(w);
        log.info("Evacuation drain released for "+id+"; operator drain="+w.draining);
        }
    }

    void maintenance(String id, boolean value) {
        WorkerRecord w = require(id);
        synchronized (w) {
        w.maintenance = value;
        updateHealthyState(w);
        log.info((value ? "Maintenance enabled for " : "Maintenance disabled for ") + id);
        }
    }

    void stop(String id) {
        WorkerRecord w = require(id);
        synchronized (w) {
        if (w.state == WorkerState.OFFLINE || w.state == WorkerState.CRASHED) return;
        w.fenceForStop();
        w.ready = false;
        w.gracefulStopPending = false;
        w.state = WorkerState.STOPPING;
        log.info("Stop requested for " + id);
        if (w.managed && w.process != null && w.process.isAlive()) {
            Process target = w.process;
            BufferedWriter targetStdin = w.stdin;
            try {
                if (targetStdin != null) {
                    targetStdin.write("stop");
                    targetStdin.newLine();
                    targetStdin.flush();
                }
            } catch (IOException e) { log.warn("Could not send stop to " + id + ": " + e.getMessage()); }
            long grace = cfg.getLong("worker.stopGraceSeconds", 30);
            scheduler.schedule(() -> {
                if (target.isAlive()) {
                    log.warn("Force-stopping " + id + " after grace period");
                    target.destroy();
                    scheduler.schedule(() -> { if (target.isAlive()) target.destroyForcibly(); }, 5, TimeUnit.SECONDS);
                }
            }, grace, TimeUnit.SECONDS);
        } else if (w.managed && orphanProcesses().stream().anyMatch(o -> id.equals(o.workerId()))) {
            w.state = WorkerState.UNHEALTHY;
            throw new IllegalStateException(id + " is running as an orphaned JVM from an earlier manager process. Use Kill JVM, then Restart.");
        } else if (!w.managed) {
            log.warn(id + " is externally managed; marked STOPPING but no local process can be terminated by this manager.");
        } else {
            w.state = WorkerState.OFFLINE;
        }
        }
    }

    void sendConsoleCommand(String id, String command) throws IOException {
        WorkerRecord w = require(id);
        String cmd = command == null ? "" : command.trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1).trim();
        if (cmd.isBlank()) throw new IllegalArgumentException("Console command may not be blank.");
        if (cmd.indexOf('\n') >= 0 || cmd.indexOf('\r') >= 0) throw new IllegalArgumentException("Only one console command may be sent at a time.");
        if (!w.managed) throw new IllegalStateException(id + " is externally managed; its stdin is not controlled by this Cluster Manager.");
        if (w.controlDetached) throw new IllegalStateException(id + " is alive but detached from this Cluster Manager. Restart the full cluster so the Manager owns its JVM before using console/destructive tests.");
        if (w.process == null || !w.process.isAlive() || w.stdin == null) {
            if (orphanProcesses().stream().anyMatch(o -> id.equals(o.workerId())))
                throw new IllegalStateException(id + " is an orphaned JVM. Its stdin cannot be recovered after the old manager process exited; restart the worker first.");
            throw new IllegalStateException(id + " is not running under this Cluster Manager.");
        }
        synchronized (w) {
            w.stdin.write(cmd);
            w.stdin.newLine();
            w.stdin.flush();
        }
        log.info("Console command sent to " + id + ": " + cmd);
    }

    void kill(String id) {
        WorkerRecord w = require(id);
        synchronized (w) {
        if (!w.managed) throw new IllegalArgumentException(id + " is externally managed and cannot be killed by this manager.");
        Process p = w.process;
        w.fenceForStop();
        w.ready = false;
        w.gracefulStopPending = false;
        w.state = WorkerState.KILLED;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            log.warn("Emergency kill requested for " + id + " pid=" + p.pid());
            return;
        }
        OrphanProcess orphan = orphanProcesses().stream().filter(o -> id.equals(o.workerId())).findFirst().orElse(null);
        if (orphan != null) {
            killOrphan(orphan.pid());
            log.warn("Emergency kill recovered orphan process for " + id + " pid=" + orphan.pid());
            return;
        }
        log.warn("Kill requested for " + id + " but no local/orphan process was alive");
        }
    }

    boolean processAlive(String id) {
        WorkerRecord w = require(id);
        if (w.process != null && w.process.isAlive()) return true;
        return orphanProcesses().stream().anyMatch(o -> id.equals(o.workerId()));
    }

    boolean managerControlled(String id) {
        WorkerRecord w = require(id);
        return w.managed && !w.controlDetached && w.process != null && w.process.isAlive() && w.stdin != null;
    }

    long effectivePid(String id) {
        WorkerRecord w = require(id);
        if (w.process != null && w.process.isAlive()) return w.process.pid();
        return orphanProcesses().stream().filter(o -> id.equals(o.workerId())).mapToLong(OrphanProcess::pid).findFirst().orElse(w.pid);
    }

    void markRetired(String id) {
        WorkerRecord w = require(id);
        synchronized (w) {
        if (w.process != null && w.process.isAlive()) throw new IllegalStateException(id + " is still running");
        w.ready = false;
        w.draining = true;
        w.drainBeforeStop = null;
        w.state = WorkerState.RETIRED;
        log.info("Worker retired: " + id);
        }
    }

    synchronized void delete(String id) throws IOException {
        WorkerRecord w = require(id);
        if (processAlive(id)) throw new IllegalStateException(id + " is still running or orphaned; stop or kill it first");
        Path base = cfg.resolve("worker.instancesDir", "workers").toAbsolutePath().normalize();
        Path dir = base.resolve(id).normalize();
        if (!dir.startsWith(base)) throw new IOException("Refusing to delete worker path outside worker.instancesDir");
        if (Files.exists(dir)) deleteTree(dir);
        workers.remove(id, w);
        log.warn("Worker deleted from disk and cluster inventory: " + id);
    }

    List<OrphanProcess> orphanProcesses() {
        long now = System.currentTimeMillis();
        if (now - orphanCacheMillis < 750) return new ArrayList<>(orphanCache);
        Set<Long> known = new HashSet<>();
        for (WorkerRecord w : workers.values()) if (w.process != null) known.add(w.process.pid());
        String home = cfg.home().toAbsolutePath().normalize().toString();
        List<OrphanProcess> out = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(ph -> {
            if (known.contains(ph.pid()) || !ph.isAlive()) return;
            String[] args = ph.info().arguments().orElse(new String[0]);
            boolean managed = false, sameHome = false; String wid = ""; int port = -1;
            for (String a : args) {
                if ("-Dfoundations.cluster.managed=true".equals(a)) managed = true;
                else if (a.startsWith("-Dfoundations.cluster.home=")) sameHome = home.equals(a.substring("-Dfoundations.cluster.home=".length()));
                else if (a.startsWith("-Dfoundations.cluster.workerId=")) wid = a.substring("-Dfoundations.cluster.workerId=".length());
                else if (a.startsWith("-Dfoundations.cluster.port=")) try { port = Integer.parseInt(a.substring("-Dfoundations.cluster.port=".length())); } catch (Exception ignored) {}
            }
            if (managed && sameHome && !wid.isBlank()) out.add(new OrphanProcess(ph.pid(), wid, port, ph.info().command().orElse("java")));
        });
        out.sort(Comparator.comparingLong(OrphanProcess::pid));
        orphanCache = List.copyOf(out); orphanCacheMillis = now;
        return new ArrayList<>(out);
    }

    void killOrphan(long pid) {
        OrphanProcess orphan = orphanProcesses().stream().filter(o -> o.pid() == pid).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No Foundations orphan process with PID " + pid + " belongs to this cluster runtime."));
        ProcessHandle ph = ProcessHandle.of(pid).orElseThrow(() -> new IllegalArgumentException("Process " + pid + " no longer exists."));
        if (!ph.destroyForcibly()) throw new IllegalStateException("Could not terminate orphan PID " + pid);
        orphanCacheMillis = 0;
        log.warn("Killed orphan Foundations worker process pid=" + pid + " workerId=" + orphan.workerId() + " port=" + orphan.port());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.deleteIfExists(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException { if (exc != null) throw exc; Files.deleteIfExists(dir); return FileVisitResult.CONTINUE; }
        });
    }

    boolean startStoredOffline(String id) throws IOException {
        WorkerRecord w = require(id);
        if (!w.managed) return false;
        synchronized (w) {
            if (w.state != WorkerState.OFFLINE) return false;
            if (w.process != null && w.process.isAlive()) return false;
            if (orphanProcesses().stream().anyMatch(o -> id.equals(o.workerId()))) return false;
            if (!isPortBindable(w.port)) {
                log.warn("Stored worker " + id + " cannot be reused at startup because port " + w.port + " is occupied; leaving it OFFLINE.");
                return false;
            }
            Path dir = cfg.resolve("worker.instancesDir", "workers").resolve(w.id);
            if (!Files.isDirectory(dir)) return false;
            CapacityBudget.requireSpawnCapacity(cfg, workers.values());
            syncClusterRuntimeFiles(dir);

            w.draining = false;
            w.drainBeforeStop = null;
            w.maintenance = false;
            w.ready = false;
            w.lastHeartbeatMillis = 0;
            w.registeredAtMillis = 0;
            w.players = 0;
            w.mspt = 0;
            w.msptP95 = 0;
            w.msptP99 = 0;
            w.heapUsed = 0;
            w.heapMax = 0;
            w.loadedChunks = 0;
            w.dimensions = 0;
            w.threadCount = 0;
            w.uptimeMillis = 0;
            w.cpuPercent = 0;
            w.lastCpuNanos = 0;
            w.lastCpuSampleNanos = 0;
            w.state = WorkerState.BOOTING;
            w.controlDetached = false;
            launchExistingWorker(w, dir);
            log.info("Reused stored worker " + w.id + " as startup baseline pid=" + w.pid + " port=" + w.port);
            return true;
        }
    }

    void restart(String id) {
        cfg.lifecycle.requireRunning();
        WorkerRecord w = require(id);
        if (!w.managed) throw new IllegalArgumentException(id + " is externally managed and cannot be restarted by this manager.");
        Thread.ofVirtual().name("worker-restart-" + id).start(() -> {
            try { restartManagedBlocking(w); }
            catch (Exception e) {
                w.ready = false;
                w.state = WorkerState.ERROR;
                log.warn("Restart failed for " + id + ": " + e.getMessage());
            }
        });
    }

    int restartAllManagedRolling() {
        List<WorkerRecord> managed = workers.values().stream()
                .filter(w -> w.managed)
                .sorted(Comparator.comparing(w -> w.id))
                .toList();
        Thread.ofVirtual().name("cluster-rolling-restart").start(() -> {
            log.info("Rolling restart requested for " + managed.size() + " managed worker(s)");
            for (WorkerRecord w : managed) {
                try {
                    restartManagedBlocking(w);
                    waitForReady(w, cfg.getLong("worker.registrationTimeoutSeconds", 180) * 1000L);
                } catch (Exception e) {
                    w.ready = false;
                    w.state = WorkerState.ERROR;
                    log.warn("Rolling restart failed for " + w.id + ": " + e.getMessage());
                }
            }
            log.info("Rolling restart sequence complete");
        });
        return managed.size();
    }

    private void restartManagedBlocking(WorkerRecord w) throws Exception {
        synchronized (w) {
            boolean preserveDrain = w.drainAfterRestart();
            boolean preserveMaintenance = w.maintenance;
            Process old = w.process;

            if (old != null && old.isAlive()) {
                stop(w.id);
                long waitMillis = Math.max(10_000L, (cfg.getLong("worker.stopGraceSeconds", 30) + 10L) * 1000L);
                if (!old.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                    old.destroyForcibly();
                    old.waitFor(5, TimeUnit.SECONDS);
                }
            }

            Path dir = cfg.resolve("worker.instancesDir", "workers").resolve(w.id);
            if (!Files.isDirectory(dir)) throw new IOException("Worker directory missing: " + dir);
            syncClusterRuntimeFiles(dir);

            w.draining = preserveDrain;
            w.maintenance = preserveMaintenance;
            w.ready = false;
            w.lastHeartbeatMillis = 0;
            w.registeredAtMillis = 0;
            w.players = 0;
            w.mspt = 0;
            w.msptP95 = 0;
            w.msptP99 = 0;
            w.heapUsed = 0;
            w.heapMax = 0;
            w.loadedChunks = 0;
            w.dimensions = 0;
            w.threadCount = 0;
            w.uptimeMillis = 0;
            w.cpuPercent = 0;
            w.lastCpuNanos = 0;
            w.lastCpuSampleNanos = 0;
            w.state = WorkerState.BOOTING;

            if (!isPortBindable(w.port)) {
                w.state = WorkerState.ERROR;
                throw new IOException("Cannot restart " + w.id + ": port " + w.port + " is still occupied by another process.");
            }
            launchExistingWorker(w, dir);
            w.drainBeforeStop=null;
            log.info("Restarted " + w.id + " pid=" + w.pid + " port=" + w.port);
        }
    }

    private void waitForReady(WorkerRecord w, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMillis);
        while (System.currentTimeMillis() < deadline) {
            if (w.ready && (w.state == WorkerState.READY || w.state == WorkerState.ACTIVE || w.state == WorkerState.MAINTENANCE || w.state == WorkerState.DRAINED)) return;
            if (w.state == WorkerState.CRASHED || w.state == WorkerState.KILLED || w.state == WorkerState.RETIRED || w.state == WorkerState.ERROR) return;
            Thread.sleep(500);
        }
        log.warn("Timed out waiting for " + w.id + " to become ready during rolling restart");
    }

    void ensureVelocityForwardingReady(Path workerDir) throws IOException {
        if (!cfg.getBoolean("gateway.enabled", true) || !cfg.get("gateway.mode", "velocity").equalsIgnoreCase("velocity")) return;

        Path template = cfg.resolve("worker.templateDir", "server-template");
        Path templateMods = template.resolve("mods");
        Path templateConfig = template.resolve("config/proxy-compatible-forge.toml");
        Path velocitySecret = cfg.home().resolve("velocity/forwarding.secret");
        if (!Files.isDirectory(templateMods)) throw new IOException("Velocity forwarding prerequisite missing: server-template/mods");
        Path pcfJar;
        try (var stream = Files.list(templateMods)) {
            pcfJar = stream.filter(Files::isRegularFile)
                    .filter(x -> x.getFileName().toString().startsWith("proxy-compatible-forge-") && x.getFileName().toString().endsWith(".jar"))
                    .findFirst().orElseThrow(() -> new IOException("Velocity forwarding prerequisite missing: Proxy Compatible Forge JAR in server-template/mods. Complete gateway setup first; inspect logs/velocity-bootstrap.log and restart the cluster after repair."));
        }
        if (!Files.isRegularFile(templateConfig)) throw new IOException("Velocity forwarding prerequisite missing: server-template/config/proxy-compatible-forge.toml");
        if (!Files.isRegularFile(velocitySecret)) throw new IOException("Velocity forwarding prerequisite missing: velocity/forwarding.secret");

        String secret = Files.readString(velocitySecret, StandardCharsets.UTF_8).trim();
        if (secret.isBlank()) throw new IOException("Velocity forwarding secret is blank.");
        String templateToml = Files.readString(templateConfig, StandardCharsets.UTF_8);
        if (!templateToml.contains("mode = \"MODERN\"") || !templateToml.contains("secret = \"" + secret.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")) {
            throw new IOException("Proxy Compatible Forge template config does not match the active Velocity forwarding secret.");
        }

        if (workerDir == null) return;
        Path workerMods = workerDir.resolve("mods");
        Path workerConfig = workerDir.resolve("config");
        Files.createDirectories(workerMods);
        Files.createDirectories(workerConfig);
        try (var old = Files.list(workerMods)) {
            for (Path x : old.filter(Files::isRegularFile).filter(x -> x.getFileName().toString().startsWith("proxy-compatible-forge-")).toList()) {
                if (!x.getFileName().equals(pcfJar.getFileName())) Files.deleteIfExists(x);
            }
        }
        Files.copy(pcfJar, workerMods.resolve(pcfJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(templateConfig, workerConfig.resolve("proxy-compatible-forge.toml"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(velocitySecret, workerDir.resolve("forwarding.secret"), StandardCopyOption.REPLACE_EXISTING);

        Path workerPcf = workerConfig.resolve("proxy-compatible-forge.toml");
        String workerToml = Files.readString(workerPcf, StandardCharsets.UTF_8);
        if (!workerToml.contains("enabled = true") || !workerToml.contains("mode = \"MODERN\"") || !workerToml.contains(secret)) {
            throw new IOException("Worker Velocity forwarding validation failed for " + workerDir.getFileName() + ": Proxy Compatible Forge config is missing/incorrect.");
        }
    }

    private void syncClusterRuntimeFiles(Path workerDir) throws IOException {
        Path template=cfg.resolve("worker.templateDir","server-template");
        if(!Files.isDirectory(template))return;
        Path templateMods=template.resolve("mods"),workerMods=workerDir.resolve("mods");Files.createDirectories(workerMods);
        String expectedNode="foundations_cluster_node-"+nodeAgent.version()+".jar";
        if(nodeAgent.required()&&!nodeAgent.installed())
            throw new IOException("Required Cluster Node "+nodeAgent.version()+" is missing from template; repair Node installation before restarting workers");
        if(Files.isDirectory(templateMods)){
            try(var stream=Files.list(templateMods)){for(Path src:stream.filter(Files::isRegularFile).toList()){String n=src.getFileName().toString();
                if(n.startsWith("foundations_cluster_node-")&&!n.equals(expectedNode))continue;if(n.startsWith("foundations_cluster_node-")||n.startsWith("proxy-compatible-forge-")||n.startsWith("velocity-support-")){
                if(n.startsWith("foundations_cluster_node-"))try(var old=Files.list(workerMods)){for(Path x:old.filter(f->f.getFileName().toString().startsWith("foundations_cluster_node-")).toList())Files.deleteIfExists(x);}
                if(n.startsWith("proxy-compatible-forge-"))try(var old=Files.list(workerMods)){for(Path x:old.filter(f->f.getFileName().toString().startsWith("proxy-compatible-forge-")).toList())Files.deleteIfExists(x);}
                if(n.startsWith("velocity-support-"))try(var old=Files.list(workerMods)){for(Path x:old.filter(f->f.getFileName().toString().startsWith("velocity-support-")).toList())Files.deleteIfExists(x);}
                Files.copy(src,workerMods.resolve(src.getFileName()),StandardCopyOption.REPLACE_EXISTING);
            }}}
        }
        for(String rel:List.of("forwarding.secret","config/proxy-compatible-forge.toml")){Path src=template.resolve(rel),dst=workerDir.resolve(rel);if(Files.isRegularFile(src)){Files.createDirectories(dst.getParent());Files.copy(src,dst,StandardCopyOption.REPLACE_EXISTING);}}
        writeJvmArgs(workerDir.resolve("user_jvm_args.txt"));
    }

    private void launchExistingWorker(WorkerRecord w, Path dir) throws IOException {
        ensureVelocityForwardingReady(dir);
        configureServerProperties(dir.resolve("server.properties"), w.port, w.id);
        List<String> cmd = buildLaunchCommand(dir, w.id, w.port);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Map<String,String> env = pb.environment();
        env.put("FOUNDATIONS_CLUSTER_MANAGER_URL", cfg.get("manager.publicUrl", "http://127.0.0.1:" + cfg.getInt("web.port", 8765)));
        env.put("FOUNDATIONS_CLUSTER_AGENT_TOKEN", tokens.agentToken);
        env.put("FOUNDATIONS_CLUSTER_WORKER_ID", w.id);
        env.put("FOUNDATIONS_CLUSTER_WORKER_PORT", Integer.toString(w.port));
        env.put("FOUNDATIONS_CLUSTER_WARM", Boolean.toString(w.warm));
        env.put("FOUNDATIONS_EXPECTED_WORLD_SEED", Long.toString(expectedWorldSeed()));
        env.put("FOUNDATIONS_EXPECTED_WORLD_SEED_KNOWN", Boolean.toString(worldSeedReady()));
        env.put("FOUNDATIONS_EXPECTED_WORLD_IDENTITY", expectedWorldIdentity());
        env.put("FOUNDATIONS_EXPECTED_WORLD_IDENTITY_SCHEMA", Integer.toString(expectedWorldIdentitySchema()));
        env.put("FOUNDATIONS_WORLD_IDENTITY_KEYWORDS", cfg.get("world.identity.configKeywords", "world,worldgen,terrain,biome,generation,climate,tfc"));
        env.put("FOUNDATIONS_DATAPLANE_POLL_MILLIS", cfg.get("dataplane.pollMillis", "50"));
        env.put("FOUNDATIONS_REMOTE_BLOCK_PROTOTYPE", cfg.get("dataplane.remoteBlockPrototype", "true"));
            env.put("FOUNDATIONS_CHUNK_REPLICATION_ENABLED", cfg.get("dataplane.chunkReplicationEnabled", "true"));
            env.put("FOUNDATIONS_AUTHORITY_RANDOM_TICKS", cfg.get("dataplane.authorityRandomTicks.enabled", "true"));
            env.put("FOUNDATIONS_REMOTE_TICK_TTL_MILLIS", cfg.get("dataplane.authorityRandomTicks.ttlMillis", "20000"));
            env.put("FOUNDATIONS_REMOTE_TICK_LIMIT", cfg.get("dataplane.authorityRandomTicks.maxChunks", "128"));
            env.put("FOUNDATIONS_CHUNK_SNAPSHOT_RADIUS", cfg.get("dataplane.chunkSnapshotRadius", "1"));
            env.put("FOUNDATIONS_CHUNK_VERTICAL_RADIUS", cfg.get("dataplane.chunkVerticalRadius", "96"));
            env.put("FOUNDATIONS_CHUNK_REQUEST_INTERVAL_MILLIS", cfg.get("dataplane.chunkRequestIntervalMillis", "1000"));
            env.put("FOUNDATIONS_CHUNK_CACHE_TTL_MILLIS", cfg.get("dataplane.chunkCacheTtlMillis", "5000"));
            env.put("FOUNDATIONS_MAX_CHUNK_REQUESTS_PER_CYCLE", cfg.get("dataplane.maxChunkRequestsPerCycle", "2"));
            env.put("FOUNDATIONS_REGION_CHUNKS", cfg.get("region.regionChunks", "32"));
            env.put("FOUNDATIONS_PERSISTENCE_ENABLED", cfg.get("persistence.blockJournalEnabled", "true"));
            env.put("FOUNDATIONS_BLOCK_ENTITY_PERSISTENCE_ENABLED", cfg.get("persistence.blockEntityJournalEnabled", "true"));
            env.put("FOUNDATIONS_BLOCK_ENTITY_SCAN_INTERVAL_TICKS", cfg.get("persistence.blockEntityScanIntervalTicks", "40"));
            env.put("FOUNDATIONS_BLOCK_ENTITY_SCAN_BUDGET", cfg.get("persistence.blockEntityScanBudget", "256"));
            env.put("FOUNDATIONS_BLOCK_ENTITY_MAX_PAYLOAD_BYTES", cfg.get("persistence.blockEntityMaxPayloadBytes", "8388608"));
            env.put("FOUNDATIONS_BLOCK_ENTITY_COALESCE_MILLIS", cfg.get("persistence.blockEntityCoalesceMillis", "5000"));
            env.put("FOUNDATIONS_BLOCK_ENTITY_COALESCE_MAX_MILLIS", cfg.get("persistence.blockEntityCoalesceMaxMillis", "300000"));
            env.put("FOUNDATIONS_PERSISTENCE_CHECKPOINT_SECONDS", cfg.get("persistence.checkpointSeconds", "30"));

        Process p = pb.start();
        w.process = p;
        w.pid = p.pid();
        w.stdin = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
        pumpOutput(w, p.getInputStream());
        p.onExit().thenAccept(proc -> {
            if (w.process != p) return;
            if (w.state == WorkerState.KILLED || w.state == WorkerState.RETIRED) {
                w.ready = false;
                log.warn(w.id + " process exited after " + w.state + " with code " + proc.exitValue());
            } else if (!cfg.lifecycle.shuttingDown() && w.state != WorkerState.STOPPING && w.state != WorkerState.OFFLINE) {
                w.ready = false;
                w.state = proc.exitValue() == 0 ? WorkerState.OFFLINE : WorkerState.CRASHED;
                log.warn(w.id + " exited with code " + proc.exitValue());
            } else {
                w.ready = false;
                w.state = WorkerState.OFFLINE;
                log.info(w.id + " stopped cleanly");
            }
        });
    }

    WorkerRecord register(Map<String,String> m) {
        String id = m.getOrDefault("workerId", "").trim();
        if (id.isBlank()) throw new IllegalArgumentException("workerId is required");
        int port = Json.i(m, "port", 25565);
        boolean warm = Json.b(m, "warm", false);
        WorkerRecord w = workers.computeIfAbsent(id, k -> new WorkerRecord(k, port, false));
        synchronized (w) {
        w.registeredAtMillis = System.currentTimeMillis();
        w.lastHeartbeatMillis = w.registeredAtMillis;
        w.warm = warm;
        w.ready = Json.b(m, "ready", true);
        w.nodeVersion = m.getOrDefault("nodeVersion", w.nodeVersion);
        w.minecraftVersion = m.getOrDefault("minecraftVersion", w.minecraftVersion);
        updateMetrics(w, m);
        if (w.managed && (w.process == null || !w.process.isAlive())) {
            w.controlDetached = true;
            w.ready = false;
            log.warn("Agent registered for " + id + " but its JVM is detached from this Cluster Manager; quarantining it from routing/control until a clean worker restart.");
        }
        updateHealthyState(w);
        log.info("Agent registered: " + id + " port=" + port + " state=" + w.state + (w.managed ? (w.controlDetached ? " managed-detached" : " managed") : " external"));
        return w;
        }
    }

    WorkerRecord heartbeat(Map<String,String> m) {
        String id = m.getOrDefault("workerId", "").trim();
        if (id.isBlank()) throw new IllegalArgumentException("workerId is required");
        WorkerRecord w = workers.computeIfAbsent(id, k -> new WorkerRecord(k, Json.i(m, "port", 25565), false));
        synchronized (w) {
        w.lastHeartbeatMillis = System.currentTimeMillis();
        w.ready = Json.b(m, "ready", true);
        w.warm = Json.b(m, "warm", w.warm);
        w.nodeVersion = m.getOrDefault("nodeVersion", w.nodeVersion);
        w.minecraftVersion = m.getOrDefault("minecraftVersion", w.minecraftVersion);
        updateMetrics(w, m);
        if (w.managed && (w.process == null || !w.process.isAlive())) {
            w.controlDetached = true;
            w.ready = false;
        }
        updateHealthyState(w);
        return w;
        }
    }

    private void updateMetrics(WorkerRecord w, Map<String,String> m) {
        w.players = Math.max(0, Json.i(m, "players", w.players));
        w.maxPlayers = Math.max(0, Json.i(m, "maxPlayers", w.maxPlayers));
        w.mspt = Math.max(0, Json.d(m, "mspt", w.mspt));
        w.msptP95 = Math.max(0, Json.d(m, "msptP95", w.msptP95));
        w.msptP99 = Math.max(0, Json.d(m, "msptP99", w.msptP99));
        w.heapUsed = Math.max(0, Json.l(m, "heapUsed", w.heapUsed));
        w.heapMax = Math.max(0, Json.l(m, "heapMax", w.heapMax));
        w.loadedChunks = Math.max(0, Json.i(m, "loadedChunks", w.loadedChunks));
        w.dimensions = Math.max(0, Json.i(m, "dimensions", w.dimensions));
        w.threadCount = Math.max(0, Json.i(m, "threadCount", w.threadCount));
        w.uptimeMillis = Math.max(0, Json.l(m, "uptimeMillis", w.uptimeMillis));
        w.javaVersion = m.getOrDefault("javaVersion", w.javaVersion);
        if (m.containsKey("worldSeed")) {
            w.worldSeed = Json.l(m, "worldSeed", w.worldSeed);
            w.worldSeedKnown = true;
            if (!worldSeedReady() && cfg.get("world.seed", "AUTO").equalsIgnoreCase("AUTO")) adoptCanonicalSeed(w.worldSeed, w.id);
        }
        w.worldSeedMatch = !w.worldSeedKnown || !worldSeedReady() || w.worldSeed == expectedWorldSeed();
        String reportedIdentity = m.getOrDefault("worldIdentity", "").trim();
        w.worldIdentitySchema = Math.max(1, Json.i(m, "worldIdentitySchema", w.worldIdentitySchema));
        if (!reportedIdentity.isBlank()) {
            w.worldIdentity = reportedIdentity;
            if (!worldIdentityReady() && w.worldSeedMatch) {
                adoptCanonicalIdentity(reportedIdentity, w.worldIdentitySchema, w.id);
            } else if (w.worldSeedMatch && w.worldIdentitySchema > canonicalWorldIdentitySchema
                    && w.worldIdentitySchema == 2 && ClusterVersion.NODE_VERSION.equals(w.nodeVersion)) {
                migrateCanonicalIdentity(reportedIdentity, w.worldIdentitySchema, w.id);
            }
        }
        w.worldIdentityMatch = w.worldIdentity.isBlank() || !worldIdentityReady()
                || (w.worldIdentitySchema == canonicalWorldIdentitySchema && expectedWorldIdentity().equals(w.worldIdentity));
    }

    private void updateHealthyState(WorkerRecord w) {
        if (w.state == WorkerState.STOPPING || w.state == WorkerState.KILLED || w.state == WorkerState.RETIRED) {
            w.ready = false;
            return;
        }
        if (w.managed && w.controlDetached) {
            w.ready = false;
            w.state = WorkerState.UNHEALTHY;
            return;
        }
        if (cfg.getBoolean("world.identity.enforce", false)) {
            boolean seedMismatch = worldSeedReady() && w.worldSeedKnown && !w.worldSeedMatch;
            boolean identityMismatch = worldIdentityReady() && !w.worldIdentity.isBlank() && !w.worldIdentityMatch;
            if (seedMismatch || identityMismatch) {
                w.ready = false;
                w.state = WorkerState.ERROR;
                return;
            }
        }
        if (w.maintenance) { w.state = WorkerState.MAINTENANCE; return; }
        if (w.draining) { w.state = w.players == 0 ? WorkerState.DRAINED : WorkerState.DRAINING; return; }
        if (!w.ready) { w.state = WorkerState.BOOTING; return; }
        w.state = w.players > 0 ? WorkerState.ACTIVE : WorkerState.READY;
    }

    void offline(String id) {
        WorkerRecord w = workers.get(id);
        if (w != null) {
            w.ready = false;
            w.state = WorkerState.OFFLINE;
            log.info("Agent offline: " + id);
        }
    }

    /**
     * R11 emergency backend-loss guard. Velocity may observe a dead backend a
     * few milliseconds before the heartbeat monitor does. Only quarantine the
     * worker when the Manager can independently see that it is no longer a
     * healthy owned JVM (or it is already stopping/unhealthy). This prevents a
     * normal in-game kick from being turned into an unintended failover.
     */
    synchronized boolean noteBackendFailure(String id, String reason) {
        if (cfg.lifecycle.shuttingDown()) return false;
        WorkerRecord w = workers.get(id);
        if (w == null) return false;
        boolean ownedAlive = w.managed && !w.controlDetached && w.process != null && w.process.isAlive();
        long age = w.heartbeatAgeSeconds();
        boolean explicitlyUnavailable = w.state == WorkerState.STOPPING || w.state == WorkerState.OFFLINE
                || w.state == WorkerState.CRASHED || w.state == WorkerState.KILLED || w.state == WorkerState.ERROR
                || w.state == WorkerState.UNHEALTHY || w.state == WorkerState.RETIRED;
        if (ownedAlive && !explicitlyUnavailable && age >= 0 && age <= 3) {
            log.warn("Emergency backend-loss report ignored for healthy " + id + " reason=" + (reason == null ? "" : reason));
            return false;
        }
        w.ready = false;
        w.draining = true;
        if (w.state != WorkerState.STOPPING && w.state != WorkerState.KILLED && w.state != WorkerState.RETIRED)
            w.state = ownedAlive ? WorkerState.UNHEALTHY : WorkerState.CRASHED;
        log.warn("Emergency backend-loss quarantine " + id + " state=" + w.state + " heartbeatAge=" + age
                + " reason=" + (reason == null ? "" : reason));
        return true;
    }

    boolean migrationEligible(WorkerRecord w) {
        if(cfg.getBoolean("world.environment.enabled",true)&&(!w.environmentReady||w.environmentLeaseUntilMillis<=System.currentTimeMillis()))return false;
        return w.routableForNewSession() && w.heartbeatAgeSeconds() >= 0
            && w.heartbeatAgeSeconds() <= cfg.getLong("worker.heartbeatTimeoutSeconds",10)
            && (w.heapMax <= 0 || 100.0 * w.heapUsed / w.heapMax < cfg.getDouble("placement.maxHeapPercent",85))
            && (w.maxPlayers <= 0 || w.players < w.maxPlayers)
            && w.mspt <= cfg.getDouble("placement.softMaxMspt",45);
    }

    private void loadPins() {
        Path file=cfg.home().resolve("runtime/worker-pins.properties");
        if (!Files.isRegularFile(file)) return;
        Properties pins=new Properties();
        try (var in=Files.newInputStream(file)) { pins.load(in); for (WorkerRecord w:workers.values()) w.pinned=Boolean.parseBoolean(pins.getProperty(w.id,"false")); }
        catch(IOException ex) { log.warn("Could not load worker pins: "+ex.getMessage()); }
    }

    synchronized void pin(String id, boolean pinned) throws IOException {
        cfg.lifecycle.requireRunning();
        WorkerRecord worker=require(id);
        Properties pins=new Properties();
        for(WorkerRecord w:workers.values()) if(w.pinned) pins.setProperty(w.id,"true");
        if(pinned) pins.setProperty(id,"true"); else pins.remove(id);
        Path file=cfg.home().resolve("runtime/worker-pins.properties"),tmp=file.resolveSibling("worker-pins.tmp");
        Files.createDirectories(file.getParent());
        try(var out=Files.newOutputStream(tmp)){pins.store(out,"Workers excluded from automatic scale-down; manual controls remain available");}
        try { Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException ex){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
        worker.pinned=pinned;
    }

    WorkerRecord routeCandidate() {
        if (cfg.lifecycle.shuttingDown()) return null;
        double maxMspt = cfg.getDouble("placement.softMaxMspt", 45.0);
        List<WorkerRecord> candidates = workers.values().stream().filter(WorkerRecord::routableForNewSession).toList();
        if (candidates.isEmpty()) return null;
        List<WorkerRecord> under = candidates.stream().filter(w -> w.mspt <= 0 || w.mspt <= maxMspt).toList();
        List<WorkerRecord> pool = under.isEmpty() ? candidates : under;
        return pool.stream().min(Comparator.comparingDouble(this::placementScore)).orElse(null);
    }

    private double placementScore(WorkerRecord w) {
        double heap = w.heapMax > 0 ? (double) w.heapUsed / w.heapMax : 0;
        double mspt = w.mspt <= 0 ? 5 : w.mspt;
        double score = mspt + (w.players * 0.35) + (heap * 12.0);
        if (w.state == WorkerState.READY) score -= 3.0;
        if (w.warm) score -= 1.0;
        return score;
    }

    int readyOrActiveCount() {
        return (int) workers.values().stream().filter(w -> w.state == WorkerState.READY || w.state == WorkerState.ACTIVE).count();
    }

    boolean worldSeedReady() { return canonicalWorldSeed != null; }

    long expectedWorldSeed() { return canonicalWorldSeed == null ? 0L : canonicalWorldSeed; }

    String worldSeedDisplay() {
        String configured = cfg.get("world.seed", "AUTO");
        if (configured.equalsIgnoreCase("AUTO")) return canonicalWorldSeed == null ? "AUTO (pending existing worker)" : "AUTO → " + canonicalWorldSeed;
        return configured;
    }

    boolean worldIdentityReady() { return canonicalWorldIdentity != null && !canonicalWorldIdentity.isBlank(); }

    String expectedWorldIdentity() { return worldIdentityReady() ? canonicalWorldIdentity : ""; }

    int expectedWorldIdentitySchema() { return canonicalWorldIdentitySchema; }

    String worldIdentityDisplay() { return worldIdentityReady() ? canonicalWorldIdentity : "PENDING FIRST HEALTHY WORKER"; }

    CapacityBudget.Snapshot capacityBudget() { return CapacityBudget.snapshot(cfg, workers.values()); }

    private void initializeCanonicalSeed() throws IOException {
        String configured = cfg.get("world.seed", "AUTO").trim();
        if (!configured.equalsIgnoreCase("AUTO")) {
            canonicalWorldSeed = seedTextToLong(configured);
            persistCanonicalSeed(canonicalWorldSeed);
            log.info("Cluster world seed locked from configuration: " + canonicalWorldSeed);
            return;
        }
        if (Files.isRegularFile(canonicalSeedFile)) {
            try {
                canonicalWorldSeed = Long.parseLong(Files.readString(canonicalSeedFile, StandardCharsets.UTF_8).trim());
                log.info("Cluster world seed loaded from runtime identity: " + canonicalWorldSeed);
                return;
            } catch (Exception e) { log.warn("Could not read runtime/world-seed.txt; rebuilding cluster seed identity."); }
        }
        if (existingWorldAtStartup) {
            canonicalWorldSeed = null;
            log.info("Existing worker world detected. Cluster seed will be adopted from the first 0.0.6a worker that registers.");
            return;
        }
        canonicalWorldSeed = new SecureRandom().nextLong();
        persistCanonicalSeed(canonicalWorldSeed);
        log.info("New cluster world seed generated and locked: " + canonicalWorldSeed);
    }

    private synchronized void adoptCanonicalSeed(long seed, String workerId) {
        if (canonicalWorldSeed != null) return;
        canonicalWorldSeed = seed;
        try { persistCanonicalSeed(seed); }
        catch (IOException e) { log.warn("Could not persist adopted cluster seed: " + e.getMessage()); }
        log.info("Adopted canonical cluster world seed " + seed + " from " + workerId + ". Future workers will use the same seed.");
    }

    private void initializeCanonicalIdentity() throws IOException {
        if (Files.isRegularFile(canonicalIdentityFile)) {
            try {
                List<String> lines = Files.readAllLines(canonicalIdentityFile, StandardCharsets.UTF_8);
                String fingerprint = ""; int schema = 1;
                for (String raw : lines) {
                    String line = raw.trim();
                    if (line.startsWith("schema=")) {
                        try { schema = Math.max(1, Integer.parseInt(line.substring("schema=".length()).trim())); } catch (Exception ignored) {}
                    } else if (line.startsWith("fingerprint=")) {
                        fingerprint = line.substring("fingerprint=".length()).trim();
                    } else if (!line.isBlank() && !line.startsWith("#") && fingerprint.isBlank()) {
                        // Legacy schema-1 file was a single bare fingerprint.
                        fingerprint = line; schema = 1;
                    }
                }
                if (!fingerprint.isBlank()) {
                    canonicalWorldIdentity = fingerprint;
                    canonicalWorldIdentitySchema = schema;
                    log.info("Cluster world identity loaded: schema=" + schema + " fingerprint=" + fingerprint);
                    return;
                }
            } catch (Exception e) {
                log.warn("Could not read runtime/world-identity.txt; identity will be re-adopted from a healthy worker.");
            }
        }
        canonicalWorldIdentity = "";
        canonicalWorldIdentitySchema = 1;
        log.info("Cluster world identity pending; the first healthy seed-matched 0.0.6a worker will establish the canonical fingerprint.");
    }

    private synchronized void adoptCanonicalIdentity(String identity, int schema, String workerId) {
        if (worldIdentityReady() || identity == null || identity.isBlank()) return;
        canonicalWorldIdentity = identity.trim();
        canonicalWorldIdentitySchema = Math.max(1, schema);
        persistCanonicalIdentity();
        log.info("Adopted canonical world identity schema=" + canonicalWorldIdentitySchema + " fingerprint=" + canonicalWorldIdentity + " from " + workerId + ".");
    }

    /** Controlled schema migration. Schema 2 intentionally removes Foundations runtime/transport
     * component versions from the fingerprint so routine Cluster Node upgrades cannot invalidate
     * an otherwise identical world. The seed must already match and the reporting node must be the
     * exact node version expected by this Manager before an older schema is advanced. */
    private synchronized void migrateCanonicalIdentity(String identity, int schema, String workerId) {
        if (identity == null || identity.isBlank() || schema <= canonicalWorldIdentitySchema) return;
        String previous = canonicalWorldIdentity; int previousSchema = canonicalWorldIdentitySchema;
        canonicalWorldIdentity = identity.trim();
        canonicalWorldIdentitySchema = schema;
        persistCanonicalIdentity();
        for (WorkerRecord other : workers.values()) {
            other.worldIdentityMatch = other.worldIdentity.isBlank()
                    || (other.worldIdentitySchema == canonicalWorldIdentitySchema && canonicalWorldIdentity.equals(other.worldIdentity));
        }
        log.info("Migrated canonical world identity schema " + previousSchema + " -> " + schema + " from " + workerId
                + "; previous=" + previous + " new=" + canonicalWorldIdentity);
    }

    private void persistCanonicalIdentity() {
        try {
            Files.createDirectories(canonicalIdentityFile.getParent());
            String body = "schema=" + canonicalWorldIdentitySchema + System.lineSeparator()
                    + "fingerprint=" + canonicalWorldIdentity + System.lineSeparator();
            Files.writeString(canonicalIdentityFile, body, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not persist canonical world identity: " + e.getMessage());
        }
    }

    private void persistCanonicalSeed(long seed) throws IOException {
        Files.createDirectories(canonicalSeedFile.getParent());
        Files.writeString(canonicalSeedFile, Long.toString(seed) + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static long seedTextToLong(String raw) {
        try { return Long.parseLong(raw.trim()); } catch (Exception ignored) { return raw.hashCode(); }
    }

    private static boolean detectExistingWorld(Path instances) {
        if (!Files.isDirectory(instances)) return false;
        try (var workers = Files.list(instances)) {
            for (Path worker : workers.filter(Files::isDirectory).toList()) {
                try (var children = Files.list(worker)) {
                    if (children.anyMatch(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("world-") && Files.isRegularFile(p.resolve("level.dat")))) return true;
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return false;
    }

    private WorkerRecord require(String id) {
        WorkerRecord w = workers.get(id);
        if (w == null) throw new IllegalArgumentException("Unknown worker: " + id);
        return w;
    }

    private void monitor() {
        if (cfg.lifecycle.shuttingDown()) return;
        long timeout = cfg.getLong("worker.heartbeatTimeoutSeconds", 10);
        long bootTimeout = cfg.getLong("worker.registrationTimeoutSeconds", 180);
        long now = System.currentTimeMillis();
        for (WorkerRecord w : workers.values()) {
            if (w.process != null && w.process.isAlive()) sampleCpu(w);
            if (w.state == WorkerState.STOPPING || w.state == WorkerState.OFFLINE || w.state == WorkerState.CRASHED || w.state == WorkerState.KILLED || w.state == WorkerState.RETIRED || w.state == WorkerState.ERROR) continue;
            if (w.lastHeartbeatMillis > 0 && now - w.lastHeartbeatMillis > timeout * 1000L) {
                w.ready = false;
                w.state = WorkerState.UNHEALTHY;
            } else if (w.managed && w.lastHeartbeatMillis == 0 && now - w.createdAtMillis > bootTimeout * 1000L) {
                w.ready = false;
                w.state = WorkerState.UNHEALTHY;
            }
        }
    }

    private void sampleCpu(WorkerRecord w) {
        Optional<java.time.Duration> d = w.process.toHandle().info().totalCpuDuration();
        if (d.isEmpty()) return;
        long cpu = d.get().toNanos(), t = System.nanoTime();
        if (w.lastCpuSampleNanos > 0) {
            long dc = cpu - w.lastCpuNanos, dt = t - w.lastCpuSampleNanos;
            int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
            w.cpuPercent = Math.max(0, Math.min(100.0, 100.0 * dc / dt / cpus));
        }
        w.lastCpuNanos = cpu; w.lastCpuSampleNanos = t;
    }

    void shutdownManaged(long maxWaitMillis) {
        cfg.lifecycle.beginShutdown("managed shutdown");
        List<WorkerRecord> managed = workers.values().stream().filter(w -> w.managed && w.process != null && w.process.isAlive()).toList();
        managed.forEach(w -> { try { stop(w.id); } catch (Exception ignored) {} });
        long deadline = System.currentTimeMillis() + Math.max(1000, maxWaitMillis);
        while (System.currentTimeMillis() < deadline && managed.stream().anyMatch(w -> w.process != null && w.process.isAlive())) {
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        for (WorkerRecord w : managed) if (w.process != null && w.process.isAlive()) w.process.destroyForcibly();

        // R9: do not leave recognized detached Foundations worker JVMs behind when the
        // Manager exits. They cannot retain stdin ownership across Manager processes.
        for (OrphanProcess orphan : orphanProcesses()) {
            try {
                ProcessHandle ph = ProcessHandle.of(orphan.pid()).orElse(null);
                if (ph != null && ph.isAlive()) {
                    log.warn("Stopping detached/orphan Foundations worker during cluster shutdown: " + orphan.workerId() + " pid=" + orphan.pid());
                    ph.destroy();
                    try { ph.onExit().get(5, TimeUnit.SECONDS); } catch (Exception ignored) {}
                    if (ph.isAlive()) ph.destroyForcibly();
                }
            } catch (Exception e) { log.warn("Could not stop detached worker " + orphan.workerId() + ": " + e.getMessage()); }
        }
        orphanCacheMillis = 0;
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    public void close() { scheduler.shutdownNow(); }
}
