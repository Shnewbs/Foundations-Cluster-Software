package com.foundations.cluster.playtest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, asynchronous diagnostics, NOT a durability journal. No game objects cross
 * to the writer thread. A missing footer or dropped records makes a trace incomplete.
 * Does not delete old reports, follow symlinks, overwrite files or upload anything. */
public final class PlaytestTrace implements AutoCloseable {
    public static final int MAX_RECORDS = 5000, MAX_RECORD_BYTES = 16384, MAX_FILES = 32;
    public static final long MAX_BYTES = 4L * 1024 * 1024;
    private final Path path;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(256);
    private final Thread writer;
    private final CompletableFuture<Path> completion = new CompletableFuture<>();
    private final long started = System.nanoTime();
    private long sequence, bytes;
    private int dropped;
    private volatile String failure = "";
    private volatile boolean finishing;
    private String finishReason = "STOP";
    private final Map<String, Long> counts = new TreeMap<>();

    public PlaytestTrace(Path directory, String label, String mode, Map<String, String> metadata) throws IOException {
        validateLabel(label);
        if (!Set.of("control", "seam").contains(mode)) throw new IllegalArgumentException("Unknown mode");
        directory = directory.toAbsolutePath().normalize();
        for (Path p = directory; p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("Report directory cannot contain symbolic links");
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid report directory");
        try (var existing = Files.list(directory)) {
            if (existing.filter(p -> p.getFileName().toString().startsWith("playtest-")).limit(MAX_FILES).count() >= MAX_FILES)
                throw new IOException("Report limit reached; move old playtest reports before recording again");
        }
        path = directory.resolve("playtest-" + label + "-" + UUID.randomUUID() + ".jsonl");
        var fields = new TreeMap<>(metadata);
        fields.put("schema", "1"); fields.put("label", label); fields.put("mode", mode);
        fields.put("startedUtc", Instant.now().toString());
        fields.put("scope", "server-observation-only; no client-ack timing or durability certification");
        // Validate header before creating a file or starting the thread.
        String header = encode(1, 0, -1, "SESSION_START", fields);
        if (header.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES) throw new IOException("Oversized metadata");
        var stream = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        sequence = 1; bytes = header.getBytes(StandardCharsets.UTF_8).length; counts.put("SESSION_START", 1L); queue.add(header);
        writer = new Thread(() -> write(stream), "foundations-playtest-writer");
        writer.setDaemon(true); writer.start();
    }
    public static void validateLabel(String label) {
        if (label == null || !label.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}"))
            throw new IllegalArgumentException("Use 1-32 letters, digits, underscores or hyphens");
    }
    public synchronized boolean record(long tick, String kind, Map<String, String> fields) {
        if (finishing || !failure.isEmpty()) return false;
        if (kind == null || !kind.matches("[A-Z][A-Z0-9_]{0,47}") || kind.startsWith("SESSION_"))
            throw new IllegalArgumentException("Invalid event kind");
        String line = encode(sequence + 1, Math.max(0, System.nanoTime() - started), tick, kind, fields);
        int size = line.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_RECORD_BYTES || sequence >= MAX_RECORDS || bytes + size > MAX_BYTES - MAX_RECORD_BYTES || !queue.offer(line)) {
            dropped++;
            return false;
        }
        sequence++; bytes += size; counts.merge(kind, 1L, Long::sum);
        return true;
    }
    public synchronized void finish(String reason) {
        if (finishing) return;
        if (reason == null || !reason.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid stop reason");
        finishReason = reason; finishing = true;
    }
    public synchronized int dropped() { return dropped; }
    public synchronized long records() { return sequence; }
    public long elapsedNanos() { return Math.max(0, System.nanoTime() - started); }
    public String failure() { return failure; }
    public Path path() { return path; }
    public CompletableFuture<Path> completion() { return completion; }
    private synchronized String footer() {
        var fields = new TreeMap<String, String>();
        fields.put("reason", finishReason); fields.put("dropped", Integer.toString(dropped));
        fields.put("complete", Boolean.toString(dropped == 0));
        counts.forEach((kind, count) -> fields.put("count." + kind, count.toString()));
        return encode(sequence + 1, elapsedNanos(), -1, "SESSION_END", fields);
    }
    private void write(OutputStream stream) {
        try (var out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8))) {
            while (!finishing || !queue.isEmpty()) {
                String line = queue.poll(100, TimeUnit.MILLISECONDS);
                if (line != null) out.write(line);
                if (line == null || queue.isEmpty()) out.flush();
            }
            out.write(footer());
            out.flush();
        } catch (IOException | InterruptedException | RuntimeException ex) {
            failure = "REPORT_WRITE_FAILED";
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            completion.completeExceptionally(new IOException(failure, ex));
            return;
        }
        completion.complete(path); // Only after the stream has closed successfully.
    }
    static String encode(long sequence, long nanos, long tick, String kind, Map<String, String> fields) {
        if (fields.size() > 96) throw new IllegalArgumentException("Too many event fields");
        StringBuilder b = new StringBuilder("{\"seq\":").append(sequence).append(",\"elapsedNanos\":").append(nanos)
            .append(",\"tick\":").append(tick).append(",\"kind\":").append(quote(kind)).append(",\"fields\":{");
        boolean first = true;
        for (var e : new TreeMap<>(fields).entrySet()) {
            if (e.getKey().length() > 80 || e.getValue() == null || e.getValue().length() > 8192)
                throw new IllegalArgumentException("Invalid event field");
            if (!first) b.append(','); first = false;
            b.append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        }
        return b.append("}}\n").toString();
    }
    public static String quote(String text) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 32 || Character.isSurrogate(c)) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    @Override public void close() throws IOException {
        finish("SERVER_STOP");
        try { completion.get(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Interrupted closing report", ex); }
        catch (ExecutionException | TimeoutException ex) { throw new IOException("Report close failed", ex); }
    }
}
