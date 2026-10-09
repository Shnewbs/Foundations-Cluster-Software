package com.foundations.cluster.replication;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.foundations.cluster.replication.PlayerOperationLedger.*;

/** Real journal files and killed JVMs, not a Minecraft worker/chunk/entity crash certification. */
public final class PlayerLedgerRecoverySelfTest {
    private static final UUID CLUSTER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final String CELL = "overworld/cell/0/0";
    private static final long EPOCH = 7;
    private static final Reservation SLOT = new Reservation(12, 0, "fixture-tool/components", 1);
    private static final byte[] EFFECTS = "opaque-world-and-player-effect-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final int MAGIC = 0x46435032;
    private static int checks;
    private enum Boundary { PREPARED, WORLD_EXECUTED, AUTHORITY_ONLY,
        WORLD_RESULT_DURABLE, PLAYER_COMMITTED, CLIENT_ACKED }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--crash-child")) {
            child(Path.of(args[1]), Boundary.valueOf(args[2]));
            return;
        }
        Path root = Files.createTempDirectory("foundations-ledger-recovery-");
        try {
            framedTails(root);
            framingCorruption(root);
            legacyCompatibility(root);
            unavailableGuards(root);
            for (Boundary boundary : Boundary.values()) crashAndRecover(root, boundary);
            System.out.println("Player ledger recovery: PASS (" + checks + " checks; 6 forcibly terminated JVMs; not live Minecraft crash certification)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static PlayerOperationLedger open(Path path) throws IOException {
        return new PlayerOperationLedger(path, CLUSTER, PLAYER);
    }
    private static OperationId id() { return new OperationId(CLUSTER, PLAYER, 0); }
    private static byte[] prepared(Path path) throws IOException {
        try (var ledger = open(path)) { ledger.prepare(SLOT); }
        return Files.readAllBytes(path);
    }
    private static void framedTails(Path root) throws Exception {
        Path good = root.resolve("tail-good");
        byte[] prefix = prepared(good);
        try (var ledger = open(good)) { ledger.advance(id(), Stage.WORLD_EXECUTED, null); }
        byte[] complete = Files.readAllBytes(good);
        int frameLength = complete.length - prefix.length;
        for (int cut = 0; cut < frameLength; cut++) {
            Path torn = root.resolve("tail-" + cut);
            Files.write(torn, Arrays.copyOf(complete, prefix.length + cut));
            try (var ledger = open(torn)) {
                check(ledger.get(id()).stage() == Stage.PREPARED, "partial frame must not publish a transition");
                check(Arrays.equals(Files.readAllBytes(torn), prefix), "only interrupted tail is removed");
                check(ledger.slotReserved(0), "durable reservation retained after interrupted frame");
                ledger.advance(id(), Stage.ABORTED, null);
                check(ledger.prepare(SLOT).id().counter() == 1, "counter preserved after tail repair");
            }
            try (var ledger = open(torn)) {
                check(ledger.get(id()).stage() == Stage.ABORTED && ledger.unfinished().size() == 1,
                    "repaired file remains appendable and reopens consistently");
            }
        }
    }
    private static void framingCorruption(Path root) throws Exception {
        byte[] good = prepared(root.resolve("framing-good"));
        check(ByteBuffer.wrap(good).getInt() == MAGIC, "new records use protected FCP2 framing");
        int length = ByteBuffer.wrap(good, 4, 4).getInt();
        check(ByteBuffer.wrap(good, 8, 4).getInt() == ~length, "record length complement");
        for (int index = 0; index < 12; index++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] bad = good.clone(); bad[index] ^= (byte) (1 << bit);
                preservedRejection(root.resolve("bad-header-" + index + "-" + bit), bad);
            }
        }
        for (int index = 12; index < good.length; index++) {
            byte[] bad = good.clone(); bad[index] ^= 1;
            preservedRejection(root.resolve("bad-payload-" + index), bad);
        }
        // Arbitrary trailing garbage must not be treated as a known interrupted header.
        for (byte[] tail : new byte[][] {{0}, {0, 0, 0}, {0, 0, 0, 20, 1, 2, 3}}) {
            var out = new ByteArrayOutputStream(); out.write(good); out.write(tail);
            preservedRejection(root.resolve("garbage-" + tail.length), out.toByteArray());
        }
    }
    private static void legacyCompatibility(Path root) throws Exception {
        byte[] framed = prepared(root.resolve("legacy-source"));
        int length = ByteBuffer.wrap(framed, 4, 4).getInt();
        byte[] legacy = ByteBuffer.allocate(length + 8).putInt(length)
            .put(framed, 12, length + 4).array();
        Path path = root.resolve("legacy-mixed"); Files.write(path, legacy);
        try (var ledger = open(path)) {
            check(ledger.get(id()).stage() == Stage.PREPARED, "complete HF15 legacy record remains readable");
            check(Arrays.equals(legacy, Files.readAllBytes(path)), "opening legacy data does not rewrite it");
            ledger.advance(id(), Stage.WORLD_EXECUTED, null);
        }
        byte[] mixed = Files.readAllBytes(path);
        check(Arrays.equals(legacy, Arrays.copyOf(mixed, legacy.length)), "legacy prefix remains byte-for-byte unchanged");
        check(ByteBuffer.wrap(mixed, legacy.length, 4).getInt() == MAGIC, "new append upgrades framing without migration");
        try (var ledger = open(path)) {
            check(ledger.get(id()).stage() == Stage.WORLD_EXECUTED && ledger.slotReserved(0),
                "mixed legacy/protected history recovers exactly");
        }
        byte[] badLength = legacy.clone(); ByteBuffer.wrap(badLength).putInt(60000);
        preservedRejection(root.resolve("legacy-length-corruption"), badLength);
        for (int cut = 1; cut < legacy.length; cut++)
            preservedRejection(root.resolve("legacy-incomplete-" + cut), Arrays.copyOf(legacy, cut));
        var out = new ByteArrayOutputStream(); out.write(framed); out.write(legacy);
        preservedRejection(root.resolve("framing-downgrade"), out.toByteArray());
        // A rejected constructor must release its OS lock as well as its channel.
        Path restored = root.resolve("restore-after-rejection");
        preservedRejection(restored, badLength); Files.write(restored, legacy);
        try (var ledger = open(restored)) { check(ledger.slotReserved(0), "failed recovery releases exclusive writer lock"); }
    }
    private static void unavailableGuards(Path root) throws Exception {
        Path path = root.resolve("closed");
        var ledger = open(path);
        OperationId operation = ledger.prepare(SLOT).id();
        ledger.close(); ledger.close();
        reject(() -> ledger.get(operation), "closed ledger does not serve cached operations");
        reject(ledger::unfinished, "closed ledger does not serve recovery decisions");
        reject(() -> ledger.slotReserved(0), "closed ledger does not serve reservation decisions");
        reject(() -> ledger.advance(operation, Stage.PREPARED, null), "idempotent advance checks availability");
        reject(() -> ledger.prepare(new Reservation(13, 1, "another-tool", 1)), "closed prepare checks availability");
        try (var player = open(root.resolve("closed-ack"));
             var authority = new AuthorityResultJournal(root.resolve("closed-ack-authority"), CLUSTER, CELL)) {
            var op = player.prepare(SLOT).id();
            player.advance(op, Stage.WORLD_EXECUTED, null);
            Result receipt = authority.commit(op, EPOCH, EFFECTS).receipt();
            player.advance(op, Stage.WORLD_RESULT_DURABLE, receipt);
            player.advance(op, Stage.PLAYER_COMMITTED, receipt);
            player.acknowledge(op, CELL, EPOCH, receipt.frame());
            player.close();
            reject(() -> player.acknowledge(op, CELL, EPOCH, receipt.frame()), "cached acknowledgement cannot succeed after close");
        }
        // White-box failure-state fixture; the process-kill tests below are separate real JVM failures.
        try (var poisoned = open(root.resolve("poisoned"))) {
            var op = poisoned.prepare(SLOT).id();
            var field = PlayerOperationLedger.class.getDeclaredField("failed"); field.setAccessible(true); field.setBoolean(poisoned, true);
            reject(() -> poisoned.get(op), "failed ledger cannot expose stale cached operation");
            reject(poisoned::unfinished, "failed ledger cannot drive recovery from cache");
            reject(() -> poisoned.slotReserved(0), "failed ledger cannot authorize a slot change");
            reject(() -> poisoned.advance(op, Stage.PREPARED, null), "failed idempotent call requires reopen");
        }
        try (var recovered = open(root.resolve("poisoned"))) {
            check(recovered.get(id()).stage() == Stage.PREPARED, "reopen revalidates durable state after failure");
        }
    }

    private static void child(Path root, Boundary boundary) throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.writeString(root.resolve("graceful-shutdown"), "hook ran"); }
            catch (IOException ignored) { }
        }));
        try (var player = open(root.resolve("player"));
             var authority = new AuthorityResultJournal(root.resolve("authority"), CLUSTER, CELL)) {
            var op = player.prepare(SLOT).id();
            Result receipt = null;
            if (boundary.ordinal() >= Boundary.WORLD_EXECUTED.ordinal())
                player.advance(op, Stage.WORLD_EXECUTED, null);
            if (boundary.ordinal() >= Boundary.AUTHORITY_ONLY.ordinal())
                receipt = authority.commit(op, EPOCH, EFFECTS).receipt();
            if (boundary.ordinal() >= Boundary.WORLD_RESULT_DURABLE.ordinal())
                player.advance(op, Stage.WORLD_RESULT_DURABLE, receipt);
            if (boundary.ordinal() >= Boundary.PLAYER_COMMITTED.ordinal())
                player.advance(op, Stage.PLAYER_COMMITTED, receipt);
            if (boundary == Boundary.CLIENT_ACKED) player.acknowledge(op, CELL, EPOCH, receipt.frame());
            System.out.println("READY " + boundary); System.out.flush();
            new CountDownLatch(1).await(); // Parent terminates without close/finally/shutdown hooks.
        }
    }
    private static void crashAndRecover(Path root, Boundary boundary) throws Exception {
        Path directory = Files.createDirectory(root.resolve("killed JVM " + boundary));
        Path java = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
            PlayerLedgerRecoverySelfTest.class.getName(), "--crash-child", directory.toString(), boundary.name())
            .redirectErrorStream(true).start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<String> readiness = reader.submit(() -> {
                StringBuilder log = new StringBuilder();
                try (BufferedReader output = process.inputReader()) {
                    String line;
                    while ((line = output.readLine()) != null) {
                        log.append(line).append('\n');
                        if (line.equals("READY " + boundary)) return line;
                    }
                }
                throw new IOException("Child exited before durability boundary: " + log);
            });
            check(readiness.get(45, TimeUnit.SECONDS).equals("READY " + boundary), "child reached forced journal boundary");
            check(process.isAlive(), "child holds journals open before forced termination");
            process.destroyForcibly();
            check(process.waitFor(15, TimeUnit.SECONDS), "terminated child exited within timeout");
            check(process.exitValue() != 0, "child did not exit normally");
            check(!Files.exists(directory.resolve("graceful-shutdown")), "no graceful JVM shutdown hook ran");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                if (!process.waitFor(15, TimeUnit.SECONDS)) throw new IOException("Could not terminate journal fixture JVM");
            }
            reader.shutdownNow();
        }
        Stage expected = switch (boundary) {
            case PREPARED -> Stage.PREPARED;
            case WORLD_EXECUTED, AUTHORITY_ONLY -> Stage.WORLD_EXECUTED;
            case WORLD_RESULT_DURABLE -> Stage.WORLD_RESULT_DURABLE;
            case PLAYER_COMMITTED -> Stage.PLAYER_COMMITTED;
            case CLIENT_ACKED -> Stage.CLIENT_ACKED;
        };
        try (var player = open(directory.resolve("player"));
             var authority = new AuthorityResultJournal(directory.resolve("authority"), CLUSTER, CELL)) {
            check(player.get(id()).stage() == expected, "forced termination recovers last durable player stage");
            check(player.slotReserved(0) == (boundary.ordinal() < Boundary.PLAYER_COMMITTED.ordinal()),
                "slot reservation tracks durable commit, not child liveness");
            var durable = authority.find(id());
            check(durable.isPresent() == (boundary.ordinal() >= Boundary.AUTHORITY_ONLY.ordinal()),
                "authority receipt survives exactly the committed boundaries");
            if (durable.isPresent()) {
                var result = durable.orElseThrow(); Result receipt = result.receipt();
                check(Arrays.equals(result.payload(), EFFECTS), "complete effect bytes survive killed JVM");
                long before = Files.size(directory.resolve("authority"));
                check(authority.commit(id(), EPOCH, EFFECTS).receipt().equals(receipt), "retry reuses exact durable receipt");
                check(Files.size(directory.resolve("authority")) == before && authority.retained().size() == 1,
                    "retry appends no duplicate authority effect");
                if (boundary == Boundary.AUTHORITY_ONLY) {
                    player.advance(id(), Stage.WORLD_RESULT_DURABLE, receipt);
                    check(player.get(id()).result().equals(receipt), "authority-only recovery repairs player receipt without re-execution");
                } else check(player.get(id()).result().equals(receipt), "player and authority durable receipts agree");
                if (player.get(id()).stage() == Stage.WORLD_RESULT_DURABLE)
                    player.advance(id(), Stage.PLAYER_COMMITTED, receipt);
                reject(() -> player.acknowledge(id(), CELL, EPOCH, receipt.frame() - 1), "stale replica frame cannot acknowledge recovery");
                player.acknowledge(id(), CELL, EPOCH, receipt.frame());
                check(!player.slotReserved(0), "recovered commit releases reservation");
            } else check(player.slotReserved(0), "uncertain world action remains reserved without authoritative receipt");
            check(player.prepare(new Reservation(13, 1, "next-tool", 1)).id().counter() == 1,
                "operation counter is not reused after process death");
        }
    }
    private static void preservedRejection(Path path, byte[] bytes) throws Exception {
        Files.write(path, bytes);
        reject(() -> { try (var ignored = open(path)) { } }, "corrupt or ambiguous record must fail closed");
        check(Arrays.equals(bytes, Files.readAllBytes(path)), "failure preserves every original byte");
    }
    private interface Throwing { void run() throws Exception; }
    private static void reject(Throwing action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IOException | IllegalStateException e) { rejected = true; }
        check(rejected, message);
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
