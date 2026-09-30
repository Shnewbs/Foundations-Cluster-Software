package com.foundations.cluster.manager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Explicit allowlist export: no vaults, worlds, profiles, raw configs, or command lines. */
final class DiagnosticBundle {
    static String redact(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)(token|password|secret|authorization|api[-_]?key)([\\s\"':=]+)[^\\s,;\"}]+", "$1$2[REDACTED]")
            .replaceAll("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "[UUID]");
    }
    static byte[] create(ManagerConfig cfg, WorkerManager workers, TransferTrace trace) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            add(zip,"README.txt","Foundations " + ClusterVersion.VERSION + " diagnostic summary\n"
                + "Contains component health, host metrics and transfer-stage metadata.\n"
                + "No raw logs, player names/profiles, vaults, tokens, network endpoints or world files are included.\n"
                + "Seamless NeoForge acceptance still requires a connected game client.\n");
            add(zip,"host.json",HostTelemetry.snapshot(cfg.home()));
            add(zip,"transfer-trace.json",trace.json());
            StringBuilder csv = new StringBuilder("worker,state,port,players,mspt,heapUsed,heapMax,nodeVersion,heartbeatAgeSeconds,pinned\n");
            for (WorkerRecord w : workers.all()) csv.append(w.id).append(',').append(w.state).append(',').append(w.port).append(',').append(w.players).append(',').append(w.mspt).append(',').append(w.heapUsed).append(',').append(w.heapMax).append(',').append(w.nodeVersion.replaceAll("[^0-9a-zA-Z._-]","_")).append(',').append(w.heartbeatAgeSeconds()).append(',').append(w.pinned).append('\n');
            add(zip,"workers.csv",csv.toString());
            add(zip,"runtime.json","{\"managerVersion\":"+Json.q(ClusterVersion.VERSION)+",\"nodeExpected\":"+Json.q(ClusterVersion.NODE_VERSION)+",\"lifecycle\":"+Json.q(cfg.lifecycle.state())+",\"generatedAt\":"+System.currentTimeMillis()+"}");
        }
        return bytes.toByteArray();
    }
    private static void add(ZipOutputStream zip,String name,String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
