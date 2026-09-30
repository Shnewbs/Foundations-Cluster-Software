package com.foundations.cluster.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class NodeAgentManager {
    private final ManagerConfig cfg;
    private final TemplateManager templates;
    private final ClusterLog log;
    private final AtomicBoolean building = new AtomicBoolean(false);
    private volatile String lastError = "";
    private volatile long lastAttemptMillis;
    private volatile int consecutiveFailures;

    NodeAgentManager(ManagerConfig cfg, TemplateManager templates, ClusterLog log) {
        this.cfg = cfg;
        this.templates = templates;
        this.log = log;
    }

    boolean required() { return cfg.getBoolean("nodeAgent.required", true); }
    boolean building() { return building.get(); }
    String version() { return ClusterVersion.NODE_VERSION; }

    Path installedJar() {
        Path mods = templates.templateDir().resolve("mods");
        if (!Files.isDirectory(mods)) return null;
        Path exact = mods.resolve("foundations_cluster_node-" + version() + ".jar");
        if (NodeArtifact.isVersion(exact, version())) return exact;
        return null;
    }

    Path anyInstalledJar() {
        Path mods = templates.templateDir().resolve("mods");
        if (!Files.isDirectory(mods)) return null;
        try (var s = Files.list(mods)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("foundations_cluster_node-"))
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .findFirst().orElse(null);
        } catch (IOException e) { return null; }
    }

    Path builtJar() {
        Path exact = cfg.home().resolve("minecraft-agent/build/libs/foundations_cluster_node-" + version() + ".jar");
        return NodeArtifact.isVersion(exact, version()) ? exact : null;
    }


    boolean installed() { return installedJar() != null; }
    boolean ready() { return !required() || installed(); }

    String state() {
        if (!required()) return "OPTIONAL";
        if (building()) return "BUILDING";
        if (installed()) return "READY";
        if (anyInstalledJar() != null) return "OUTDATED";
        if (!lastError.isBlank()) return "ERROR";
        return "MISSING";
    }

    String statusJson() {
        Path jar = installedJar();
        Path any = anyInstalledJar();
        boolean outdated = jar == null && any != null;
        return "{\"state\":" + Json.q(state()) +
                ",\"version\":" + Json.q(version()) +
                ",\"required\":" + required() +
                ",\"building\":" + building() +
                ",\"installed\":" + installed() +
                ",\"ready\":" + ready() +
                ",\"outdated\":" + outdated +
                ",\"jar\":" + Json.q(jar == null ? "" : templates.templateDir().relativize(jar).toString().replace('\\','/')) +
                ",\"outdatedJar\":" + Json.q(outdated ? templates.templateDir().relativize(any).toString().replace('\\','/') : "") +
                ",\"buildFailures\":" + consecutiveFailures +
                ",\"automaticBuildPaused\":" + (consecutiveFailures >= 3) +
                ",\"lastError\":" + Json.q(lastError) + "}";
    }

    synchronized void installBuiltJarIfPresent() throws IOException {
        Path built = builtJar();
        if (built == null) throw new IOException(artifactDiagnostic());
        Path mods = templates.templateDir().resolve("mods");
        Files.createDirectories(mods);
        // Validate and stage the replacement before touching an installed Node.
        Path target = mods.resolve("foundations_cluster_node-" + version() + ".jar");
        Path staged = Files.createTempFile(mods, ".foundations-node-", ".pending");
        try {
            Files.copy(built, staged, StandardCopyOption.REPLACE_EXISTING);
            NodeArtifact.requireVersion(staged, version());
            try { Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING); }
            try (var entries = Files.list(mods)) {
                for (Path old : entries.filter(Files::isRegularFile)
                        .filter(x -> x.getFileName().toString().startsWith("foundations_cluster_node-")
                            && x.getFileName().toString().endsWith(".jar") && !x.equals(target)).toList()) {
                    Path backup = cfg.home().resolve(".foundations_update_backups/node-template-" + UUID.randomUUID());
                    Files.createDirectories(backup);
                    Files.copy(old, backup.resolve(old.getFileName()));
                    Files.delete(old);
                }
            }
        } finally { Files.deleteIfExists(staged); }
        consecutiveFailures = 0;
        lastError = "";
        log.info("Installed Foundations Cluster Node " + version() + " into " + mods.toAbsolutePath());
    }

    void buildAndInstallAsync() {
        if (installed()) return;
        if (building.getAndSet(true)) throw new IllegalStateException("Cluster Node build is already running.");
        lastError = "";
        lastAttemptMillis = System.currentTimeMillis();
        Thread.ofVirtual().name("foundations-node-agent-build").start(() -> {
            try {
                Path existing = builtJar();
                if (existing == null) runBuildScript();
                installBuiltJarIfPresent();
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                consecutiveFailures++;
                log.warn("Cluster Node build/install failed: " + lastError);
                if (consecutiveFailures >= 3) log.warn("Cluster Node automatic builds paused after three failures. Fix the reported artifact/build issue, then use Build/Install Node to retry. A valid built Node is still detected automatically.");
            } finally {
                building.set(false);
            }
        });
    }

    boolean mayAutoRetry() {
        if (building() || installed()) return false;
        if (builtJar() != null) return true;
        if (consecutiveFailures >= 3) return false;
        // Do not spam a generic Windows path error every minute when an upgrade
        // package is missing the Node build payload. If the missing files are
        // overlaid later, this immediately becomes eligible for retry again.
        if (!buildPrerequisitesAvailable()) {
            if (lastError.isBlank()) {
                lastError = missingBuildPrerequisitesMessage();
                log.warn("Cluster Node build unavailable: " + lastError);
            }
            return false;
        }
        return System.currentTimeMillis() - lastAttemptMillis > 60_000;
    }

    private boolean buildPrerequisitesAvailable() {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path script = cfg.home().resolve(win ? "minecraft-agent/build_mod.bat" : "minecraft-agent/build_mod.sh");
        Path gradleBootstrap = cfg.home().resolve("tools/ensure_gradle.ps1");
        if (!Files.isRegularFile(script)) return false;
        return !win || Files.isRegularFile(gradleBootstrap);
    }

    private String missingBuildPrerequisitesMessage() {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> missing = new ArrayList<>();
        Path script = cfg.home().resolve(win ? "minecraft-agent/build_mod.bat" : "minecraft-agent/build_mod.sh");
        if (!Files.isRegularFile(script)) missing.add(cfg.home().relativize(script).toString());
        if (win) {
            Path gradleBootstrap = cfg.home().resolve("tools/ensure_gradle.ps1");
            if (!Files.isRegularFile(gradleBootstrap)) missing.add(cfg.home().relativize(gradleBootstrap).toString());
        }
        return "Required Cluster Node build file(s) missing: " + String.join(", ", missing) + ". Overlay the complete 0.0.6a package/upgrade onto this runtime.";
    }

    private String artifactDiagnostic() {
        Path expected = cfg.home().resolve("minecraft-agent/build/libs/foundations_cluster_node-" + version() + ".jar");
        String detail;
        try { NodeArtifact.requireVersion(expected, version()); detail = "artifact changed while installing"; }
        catch (IOException e) { detail = e.getMessage(); }
        List<String> found = new ArrayList<>();
        Path dir = expected.getParent();
        if (Files.isDirectory(dir)) try (var paths = Files.list(dir)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(x -> x.getFileName().toString().startsWith("foundations_cluster_node-")
                        && x.getFileName().toString().endsWith(".jar")).sorted().limit(12).toList()) {
                found.add(path.getFileName().toString());
            }
        } catch (IOException e) { found.add("directory unreadable: " + e.getMessage()); }
        return "Expected Cluster Node " + version() + " at " + expected + "; " + detail
                + ". Found build artifacts: " + found + ". See logs/node-agent-build.log.";
    }

    private void runBuildScript() throws Exception {
        if (!buildPrerequisitesAvailable()) throw new IOException(missingBuildPrerequisitesMessage());
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        List<String> command = win
                ? List.of("cmd", "/c", "minecraft-agent\\build_mod.bat")
                : List.of("bash", "./minecraft-agent/build_mod.sh");
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cfg.home().toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Path outFile = cfg.home().resolve("logs/node-agent-build.log");
        Files.createDirectories(outFile.getParent());
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            String line;
            while ((line = r.readLine()) != null) { out.write(line); out.newLine(); out.flush(); }
        }
        int exit = p.waitFor();
        if (exit != 0) throw new IOException("Cluster Node build exited with code " + exit + ". See logs/node-agent-build.log");
    }
}
