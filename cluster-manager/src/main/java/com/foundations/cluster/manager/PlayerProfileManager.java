package com.foundations.cluster.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * R7.R6 cluster-wide player file profile store.
 *
 * Minecraft keeps several important player-scoped records outside the live
 * ServerPlayer object.  Before an actual Velocity backend switch we checkpoint
 * those files from the authoritative source worker into runtime/player-profiles
 * and stage the newest copy into the destination worker before login.
 *
 * Defaults intentionally cover vanilla player NBT, advancements and stats.
 * Additional per-player mod files can be added with playerProfile.sharedFiles
 * using {uuid} and {name} placeholders.  Paths are always relative to the
 * worker's active level directory and may not escape it.
 */
final class PlayerProfileManager {
    private final ManagerConfig cfg;
    private final ClusterLog log;
    private final Path centralRoot;

    PlayerProfileManager(ManagerConfig cfg, ClusterLog log) throws IOException {
        this.cfg = cfg;
        this.log = log;
        this.centralRoot = cfg.home().resolve("runtime/player-profiles");
        Files.createDirectories(centralRoot);
        log.info("Cluster Player Profile store initialized: sharedFiles=" + sharedPatterns().size());
    }

    synchronized int capture(String workerId, String uuid, String name) {
        if (!enabled() || blank(workerId) || blank(uuid)) return 0;
        try {
            Path world = workerWorld(workerId);
            if (world == null) return 0;
            Path profile = profileDir(uuid);
            Files.createDirectories(profile);
            int copied = 0;
            for (String pattern : sharedPatterns()) {
                Path relative = safeRelative(resolvePattern(pattern, uuid, name));
                Path source = world.resolve(relative).normalize();
                if (!source.startsWith(world) || !Files.isRegularFile(source)) continue;
                Path target = profile.resolve(relative).normalize();
                if (!target.startsWith(profile)) continue;
                copyAtomic(source, target);
                copied++;
            }
            if (copied > 0) {
                Files.writeString(profile.resolve("profile.meta"),
                        "uuid=" + uuid + "\nname=" + (name == null ? "" : name) + "\nsourceWorker=" + workerId
                                + "\nupdatedAtMillis=" + System.currentTimeMillis() + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                log.info("Captured cluster player profile uuid=" + uuid + " name=" + safeName(name) + " from " + workerId + " files=" + copied);
            }
            return copied;
        } catch (Exception e) {
            log.warn("Could not capture cluster player profile " + uuid + " from " + workerId + ": " + e.getMessage());
            return 0;
        }
    }

    synchronized int prepare(String workerId, String uuid, String name) {
        if (!enabled() || blank(workerId) || blank(uuid)) return 0;
        try {
            Path world = workerWorld(workerId);
            Path profile = profileDir(uuid);
            if (world == null || !Files.isDirectory(profile)) return 0;
            int copied = 0;
            for (String pattern : sharedPatterns()) {
                Path relative = safeRelative(resolvePattern(pattern, uuid, name));
                Path source = profile.resolve(relative).normalize();
                if (!source.startsWith(profile) || !Files.isRegularFile(source)) continue;
                Path target = world.resolve(relative).normalize();
                if (!target.startsWith(world)) continue;
                copyAtomic(source, target);
                copied++;
            }
            if (copied > 0) log.info("Prepared cluster player profile uuid=" + uuid + " name=" + safeName(name) + " for " + workerId + " files=" + copied);
            return copied;
        } catch (Exception e) {
            log.warn("Could not prepare cluster player profile " + uuid + " for " + workerId + ": " + e.getMessage());
            return 0;
        }
    }

    synchronized int transfer(String sourceWorkerId, String destinationWorkerId, String uuid, String name) {
        int captured = capture(sourceWorkerId, uuid, name);
        int prepared = prepare(destinationWorkerId, uuid, name);
        if (captured == 0 && prepared == 0) log.info("No persisted player profile files were available yet for uuid=" + uuid + " during " + sourceWorkerId + " -> " + destinationWorkerId);
        return prepared;
    }

    boolean enabled() { return cfg.getBoolean("playerProfile.enabled", true); }

    private List<String> sharedPatterns() {
        String raw = cfg.get("playerProfile.sharedFiles", "playerdata/{uuid}.dat;advancements/{uuid}.json;stats/{uuid}.json");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : raw.split("[;,]")) {
            String p = token.trim().replace('\\','/');
            if (!p.isBlank()) result.add(p);
        }
        return List.copyOf(result);
    }

    private Path workerWorld(String workerId) throws IOException {
        Path worker = cfg.resolve("worker.instancesDir", "workers").resolve(workerId).normalize();
        if (!Files.isDirectory(worker)) return null;
        String levelName = "world-" + workerId;
        Path props = worker.resolve("server.properties");
        if (Files.isRegularFile(props)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(props)) { p.load(in); }
            String configured = p.getProperty("level-name", "").trim();
            if (!configured.isBlank()) levelName = configured;
        }
        Path world = worker.resolve(levelName).normalize();
        if (!world.startsWith(worker)) throw new IOException("Unsafe level-name for " + workerId + ": " + levelName);
        Files.createDirectories(world);
        return world;
    }

    private Path profileDir(String uuid) throws IOException {
        String safe = uuid.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-f-]", "");
        if (safe.isBlank()) throw new IOException("Invalid player UUID");
        Path p = centralRoot.resolve(safe).normalize();
        if (!p.startsWith(centralRoot)) throw new IOException("Unsafe profile path");
        return p;
    }

    private static String resolvePattern(String pattern, String uuid, String name) {
        return pattern.replace("{uuid}", uuid).replace("{name}", name == null ? "" : name);
    }

    private static Path safeRelative(String text) throws IOException {
        if (text == null || text.isBlank()) throw new IOException("Blank shared player profile path");
        Path p = Path.of(text).normalize();
        if (p.isAbsolute() || p.startsWith("..")) throw new IOException("Unsafe shared player profile path: " + text);
        return p;
    }

    private static void copyAtomic(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String safeName(String s) { return s == null || s.isBlank() ? "?" : s; }
}
