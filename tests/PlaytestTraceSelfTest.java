package com.foundations.cluster.playtest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Disposable diagnostic trace tests. No game servers or user reports are touched. */
public final class PlaytestTraceSelfTest {
    private static int checks;
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); checks++; }
    private interface Attempt { void run() throws Exception; }
    private static void rejects(Attempt a) throws Exception {
        try { a.run(); } catch (IOException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected refusal");
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("foundations-playtest-trace-");
        try {
            var trace = new PlaytestTrace(root.resolve("basic"), "test_1", "control", Map.of("playerKey", "hashed"));
            check(trace.record(42, "MARK", Map.of("label", "quote\" slash\\ newline\n snowman ☃")), "Valid event accepted");
            trace.finish("PLAYER_STOP"); trace.completion().get(5, TimeUnit.SECONDS); trace.close();
            var lines = Files.readAllLines(trace.path());
            check(lines.size() == 3, "Header/event/footer complete");
            check(lines.get(0).contains("SESSION_START") && lines.get(2).contains("SESSION_END"), "Lifecycle delimiters");
            check(lines.get(1).contains("\\\"") && lines.get(1).contains("\\u000a"), "JSON escaping preserves one-line framing");
            check(lines.get(2).contains("\"complete\":\"true\""), "No dropped records means complete telemetry");
            check(!trace.record(43, "MARK", Map.of()), "Finished trace never resumes");
            check(trace.records() == 2, "Footer is not a submitted gameplay event");
            byte[] before = Files.readAllBytes(trace.path()); trace.close();
            check(Arrays.equals(before, Files.readAllBytes(trace.path())), "Repeated close never appends duplicate footer");
            for (String name : List.of("../escape", "bad/name", "", "space name", "a".repeat(33), "x\\y", "x\n"))
                rejects(() -> PlaytestTrace.validateLabel(name));
            rejects(() -> new PlaytestTrace(root.resolve("badmode"), "ok", "unknown", Map.of()));
            try (var t = new PlaytestTrace(root.resolve("limits"), "burst", "seam", Map.of())) {
                for (int i = 0; i < 10000; i++) t.record(i, "SAMPLE", Map.of("data", "x".repeat(1024)));
                check(t.dropped() > 0 && t.records() <= PlaytestTrace.MAX_RECORDS, "Queue/count/byte limits stop excess telemetry");
                t.finish("CAPTURE_LIMIT_OR_IO_FAILURE"); t.completion().get(5, TimeUnit.SECONDS);
                check(Files.size(t.path()) <= PlaytestTrace.MAX_BYTES, "File budget includes terminal summary");
                check(Files.readAllLines(t.path()).getLast().contains("\"complete\":\"false\""), "Dropped records cannot look complete");
            }
            try (var t = new PlaytestTrace(root.resolve("validation"), "validation", "control", Map.of())) {
                rejects(() -> t.record(1, "SESSION_END", Map.of()));
                rejects(() -> t.record(1, "bad\nkind", Map.of()));
                rejects(() -> t.record(1, "SAMPLE", Map.of("a", "x".repeat(8193))));
                var oversized = Map.of("a", "x".repeat(8192), "b", "x".repeat(8192));
                check(!t.record(1, "SAMPLE", oversized) && t.dropped() == 1, "Oversized record is flagged, never partially written");
            }
            Path capped = Files.createDirectory(root.resolve("retention"));
            Files.writeString(capped.resolve("unrelated.txt"), "keep");
            for (int i = 0; i < PlaytestTrace.MAX_FILES; i++) Files.writeString(capped.resolve("playtest-old-" + i + ".jsonl"), "keep");
            rejects(() -> new PlaytestTrace(capped, "new", "control", Map.of()));
            check(Files.readString(capped.resolve("unrelated.txt")).equals("keep"), "No unrelated files removed");
            check(Files.readString(capped.resolve("playtest-old-0.jsonl")).equals("keep"), "No automatic report deletion");
            Path linked = root.resolve("linked"); Files.createSymbolicLink(linked, capped);
            rejects(() -> new PlaytestTrace(linked, "unsafe", "control", Map.of())); Files.delete(linked);
            var interrupted = new PlaytestTrace(root.resolve("interrupt"), "interrupt", "control", Map.of());
            var writerField = PlaytestTrace.class.getDeclaredField("writer"); writerField.setAccessible(true);
            ((Thread) writerField.get(interrupted)).interrupt();
            try { interrupted.completion().get(5, TimeUnit.SECONDS); throw new AssertionError("Interrupted writer completed normally"); }
            catch (ExecutionException expected) { checks++; }
            check(!interrupted.failure().isEmpty() && !interrupted.record(1, "MARK", Map.of()), "I/O-thread failure disables capture");
            check(Files.readAllLines(interrupted.path()).stream().noneMatch(s -> s.contains("SESSION_END")), "Failed writer has no success footer");
            check(PlaytestTrace.quote("\uD83D\uDC3B").equals("\"\\ud83d\\udc3b\""), "Supplementary Unicode remains valid JSON");
            System.out.println("Playtest trace: PASS (" + checks + " checks; bounded disposable diagnostics)");
        } finally {
            try (var paths = Files.walk(root)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
}
