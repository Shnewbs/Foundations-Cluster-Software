package com.foundations.cluster.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class ClusterLog {
    private final Path file;
    private final int maxLines;
    private final ArrayDeque<String> tail = new ArrayDeque<>();

    ClusterLog(Path file, int maxLines) throws IOException {
        this.file = file; this.maxLines = Math.max(50, maxLines);
        Files.createDirectories(file.getParent());
    }

    synchronized void info(String message) { write("INFO", message); }
    synchronized void warn(String message) { write("WARN", message); }
    synchronized void error(String message) { write("ERROR", message); }

    private void write(String level, String message) {
        String line = "%s [%s] %s".formatted(Instant.now(), level, message);
        System.out.println(line);
        tail.addLast(line);
        while (tail.size() > maxLines) tail.removeFirst();
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    synchronized List<String> tail(int limit) {
        List<String> all = new ArrayList<>(tail);
        int from = Math.max(0, all.size() - Math.max(1, limit));
        return all.subList(from, all.size());
    }
}
