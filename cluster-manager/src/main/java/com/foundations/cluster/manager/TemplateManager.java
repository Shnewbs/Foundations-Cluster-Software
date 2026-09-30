package com.foundations.cluster.manager;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class TemplateManager {
    private final ManagerConfig cfg;
    private final ClusterLog log;
    private final AtomicBoolean installing = new AtomicBoolean(false);
    private volatile String lastError = "";

    TemplateManager(ManagerConfig cfg, ClusterLog log) throws IOException {
        this.cfg = cfg;
        this.log = log;
        Files.createDirectories(templateDir());
    }

    Path templateDir() { return cfg.resolve("worker.templateDir", "server-template"); }
    String neoForgeVersion() { return cfg.get("template.neoforgeVersion", "21.1.250"); }
    boolean installing() { return installing.get(); }

    boolean hasNeoForgeArgs() {
        Path base = templateDir().resolve("libraries/net/neoforged/neoforge");
        if (!Files.isDirectory(base)) return false;
        String argsName = isWindows() ? "win_args.txt" : "unix_args.txt";
        try (var s = Files.walk(base, 4)) {
            return s.anyMatch(p -> p.getFileName().toString().equals(argsName));
        } catch (IOException e) {
            return false;
        }
    }

    boolean eulaAccepted() {
        Path eula = templateDir().resolve("eula.txt");
        if (!Files.isRegularFile(eula)) return false;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(eula)) {
            p.load(in);
            return Boolean.parseBoolean(p.getProperty("eula", "false").trim());
        } catch (IOException e) {
            return false;
        }
    }

    boolean ready() {
        return hasNeoForgeArgs() && (!cfg.get("template.requireEula", "true").equalsIgnoreCase("true") || eulaAccepted());
    }

    String state() {
        if (installing()) return "INSTALLING";
        if (hasNeoForgeArgs()) {
            if (cfg.get("template.requireEula", "true").equalsIgnoreCase("true") && !eulaAccepted()) return "EULA_REQUIRED";
            return "READY";
        }
        if (!lastError.isBlank()) return "ERROR";
        return "MISSING";
    }

    String statusJson() {
        return "{\"state\":" + Json.q(state()) +
                ",\"neoForgeVersion\":" + Json.q(neoForgeVersion()) +
                ",\"installed\":" + hasNeoForgeArgs() +
                ",\"eulaAccepted\":" + eulaAccepted() +
                ",\"ready\":" + ready() +
                ",\"installing\":" + installing() +
                ",\"autoBootstrap\":" + cfg.getBoolean("template.autoBootstrap", true) +
                ",\"lastError\":" + Json.q(lastError) + "}";
    }

    void bootstrapAsync() {
        if (installing.getAndSet(true)) throw new IllegalStateException("Template installation is already running.");
        lastError = "";
        Thread.ofVirtual().name("foundations-template-bootstrap").start(() -> {
            try {
                bootstrapNow();
                log.info("NeoForge server template installed successfully: " + neoForgeVersion());
            } catch (Exception e) {
                lastError = e.getMessage() == null ? e.toString() : e.getMessage();
                log.warn("NeoForge template install failed: " + lastError);
            } finally {
                installing.set(false);
            }
        });
    }

    private void bootstrapNow() throws Exception {
        if (hasNeoForgeArgs()) {
            ensureEulaFile();
            return;
        }
        Path template = templateDir();
        Files.createDirectories(template);
        Files.createDirectories(template.resolve("mods"));
        Files.createDirectories(template.resolve(".bootstrap"));

        String version = neoForgeVersion();
        String base = cfg.get("template.installerBaseUrl", "https://maven.neoforged.net/releases/net/neoforged/neoforge");
        String fileName = "neoforge-" + version + "-installer.jar";
        URI uri = URI.create(base + "/" + version + "/" + fileName);
        Path installer = template.resolve(".bootstrap").resolve(fileName);
        Path tmp = installer.resolveSibling(fileName + ".part");

        if (!Files.isRegularFile(installer)) {
            log.info("Downloading NeoForge installer " + version + " from official Maven");
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest req = HttpRequest.newBuilder(uri).GET().build();
            HttpResponse<Path> res = client.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                Files.deleteIfExists(tmp);
                throw new IOException("NeoForge installer download failed: HTTP " + res.statusCode());
            }
            Files.move(tmp, installer, StandardCopyOption.REPLACE_EXISTING);
        }

        String javaExe = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        ProcessBuilder pb = new ProcessBuilder(javaExe, "-jar", installer.toAbsolutePath().toString(), "--installServer");
        pb.directory(template.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Path installLog = cfg.home().resolve("logs/template-bootstrap.log");
        Files.createDirectories(installLog.getParent());
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = Files.newBufferedWriter(installLog, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            String line;
            while ((line = r.readLine()) != null) {
                out.write(line); out.newLine(); out.flush();
            }
        }
        int exit = p.waitFor();
        if (exit != 0) throw new IOException("NeoForge installer exited with code " + exit + ". See logs/template-bootstrap.log");
        if (!hasNeoForgeArgs()) throw new IOException("NeoForge installer finished but generated launch arguments were not found.");
        ensureEulaFile();
    }

    void acceptEula() throws IOException {
        if (!hasNeoForgeArgs()) throw new IOException("Install the NeoForge server template before accepting the EULA.");
        Path eula = templateDir().resolve("eula.txt");
        Files.writeString(eula,
                "# Accepted through Foundations Cluster Runtime dashboard.\n# You confirm you agree to the Minecraft EULA.\neula=true\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Minecraft EULA marked accepted for server-template by administrator action.");
    }

    private void ensureEulaFile() throws IOException {
        Path eula = templateDir().resolve("eula.txt");
        if (!Files.exists(eula)) {
            Files.writeString(eula, "# Review https://aka.ms/MinecraftEULA before changing this value.\neula=false\n", StandardCharsets.UTF_8);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
