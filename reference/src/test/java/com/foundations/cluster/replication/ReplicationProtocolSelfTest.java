package com.foundations.cluster.replication;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import com.foundations.cluster.replication.PlayerOperationLedger.*;

/** Restart/corruption tests for the isolated model; not evidence of Minecraft seam correctness. */
public final class ReplicationProtocolSelfTest {
    private static int checks;
    private static final UUID CLUSTER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Reservation SLOT = new Reservation(8, 0, "minecraft:stone/components-hash", 1);
    private static final Result RESULT = new Result("overworld/cell/0/0", 3, 42, "result-hash", "authority-journal:7");
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("foundations-v22-model-");
        try {
            restartMatrix(root); abortAndIdentity(root); tornTailAndCorruption(root); semanticCorruption(root); timeSkew();
            System.out.println("World Replication v2.2 protocol model: " + checks + " checks PASS");
            PlayerLedgerRecoverySelfTest.main(new String[0]);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
    private static void semanticCorruption(Path root) throws Exception {
        Path good = root.resolve("semantic-good");
        try (var ledger = new PlayerOperationLedger(good, CLUSTER, PLAYER)) {
            var id=ledger.prepare(SLOT).id();
            ledger.advance(id, Stage.WORLD_EXECUTED, null);
            ledger.advance(id, Stage.WORLD_RESULT_DURABLE, RESULT);
            ledger.advance(id, Stage.PLAYER_COMMITTED, RESULT);
        }
        byte[] data=Files.readAllBytes(good);
        var records=new java.util.ArrayList<byte[]>();
        for (int offset=0;offset<data.length;) {
            int length=java.nio.ByteBuffer.wrap(data,offset+4,4).getInt()+16;
            records.add(java.util.Arrays.copyOfRange(data,offset,offset+length));offset+=length;
        }
        int count=0;
        for (int[] order : new int[][] {{2}, {0,2}, {0,1,3}, {0,0}, {0,1,2,1}}) {
            var out=new java.io.ByteArrayOutputStream();
            for (int index:order) out.write(records.get(index));
            Path corrupt=root.resolve("semantic-corrupt-"+count++);Files.write(corrupt,out.toByteArray());
            reject(() -> {try(var ledger=new PlayerOperationLedger(corrupt,CLUSTER,PLAYER)) {}},
                "valid checksums cannot authorize skipped/replayed transitions");
            check(java.util.Arrays.equals(Files.readAllBytes(corrupt),out.toByteArray()), "semantic failure preserves evidence");
        }
    }
    private static void restartMatrix(Path root) throws Exception {
        Stage[] stages = {Stage.PREPARED, Stage.WORLD_EXECUTED, Stage.WORLD_RESULT_DURABLE,
            Stage.PLAYER_COMMITTED, Stage.CLIENT_ACKED};
        for (int stop = 0; stop < stages.length; stop++) {
            Path journal = root.resolve("restart-" + stop);
            OperationId id;
            try (var ledger = new PlayerOperationLedger(journal, CLUSTER, PLAYER)) {
                id = ledger.prepare(SLOT).id();
                check(id.counter() == 0, "initial persistent counter");
                reject(() -> ledger.prepare(SLOT), "slot swap/reservation collision");
                reject(() -> ledger.advance(id, Stage.PLAYER_COMMITTED, RESULT), "skip durability");
                for (int i = 1; i <= stop; i++) advance(ledger, id, stages[i]);
            }
            try (var ledger = new PlayerOperationLedger(journal, CLUSTER, PLAYER)) {
                check(ledger.get(id).stage() == stages[stop], "restart restores exact state");
                check(ledger.slotReserved(0) == (stop < 3), "reservation survives until commit/abort");
                check(ledger.unfinished().size() == (stop == 4 ? 0 : 1), "unfinished recovery set");
                if (stop >= 2) reject(() -> ledger.advance(id, Stage.ABORTED, RESULT), "no abort after durability");
                for (int i = stop + 1; i < stages.length; i++) advance(ledger, id, stages[i]);
                long before = Files.size(journal);
                ledger.acknowledge(id, RESULT.cell(), RESULT.epoch(), RESULT.frame());
                check(Files.size(journal) == before, "duplicate ack does not append");
                check(ledger.prepare(SLOT).id().counter() == 1, "reconnect allocates next counter");
                check(id.outputEntityId(0).equals(new OperationId(CLUSTER, PLAYER, 0).outputEntityId(0)),
                    "replay output identity deterministic");
                check(!id.outputEntityId(0).equals(id.outputEntityId(1)), "output index separates entities");
            }
        }
    }
    private static void advance(PlayerOperationLedger ledger, OperationId id, Stage stage) throws Exception {
        if (stage == Stage.CLIENT_ACKED) {
            reject(() -> ledger.acknowledge(id, RESULT.cell(), RESULT.epoch(), 41), "stale frame cannot ack");
            reject(() -> ledger.acknowledge(id, RESULT.cell(), 2, 100), "wrong epoch cannot ack");
            reject(() -> ledger.acknowledge(id, "wrong-cell", 3, 100), "wrong cell cannot ack");
            ledger.acknowledge(id, RESULT.cell(), 3, 42);
        } else ledger.advance(id, stage, stage == Stage.WORLD_EXECUTED ? null : RESULT);
    }
    private static void abortAndIdentity(Path root) throws Exception {
        Path journal = root.resolve("abort");
        try (var ledger = new PlayerOperationLedger(journal, CLUSTER, PLAYER)) {
            OperationId id = ledger.prepare(SLOT).id();
            ledger.advance(id, Stage.ABORTED, null);
            check(!ledger.slotReserved(0), "abort releases slot");
            reject(() -> ledger.advance(id, Stage.WORLD_EXECUTED, null), "abort is terminal");
            OperationId next = ledger.prepare(SLOT).id();
            check(next.counter() == 1, "aborted counters never reused");
            ledger.advance(next, Stage.WORLD_EXECUTED, null);
            reject(() -> ledger.advance(next, Stage.WORLD_RESULT_DURABLE, null), "receipt required");
            reject(() -> ledger.get(new OperationId(UUID.randomUUID(), PLAYER, 0)), "foreign cluster rejected");
            reject(() -> ledger.get(new OperationId(CLUSTER, UUID.randomUUID(), 0)), "foreign player rejected");
            reject(() -> { try (var other = new PlayerOperationLedger(journal, CLUSTER, PLAYER)) {} },
                "concurrent ledger writer rejected");
        }
        reject(() -> { try (var wrong = new PlayerOperationLedger(journal, CLUSTER, UUID.randomUUID())) {} },
            "reopen under wrong player rejected");
        try (var ledger = new PlayerOperationLedger(journal, CLUSTER, PLAYER)) {
            check(ledger.unfinished().size() == 1, "aborted operation excluded from recovery");
        }
    }
    private static void tornTailAndCorruption(Path root) throws Exception {
        Path good = root.resolve("good");
        try (var ledger = new PlayerOperationLedger(good, CLUSTER, PLAYER)) { ledger.prepare(SLOT); }
        byte[] original = Files.readAllBytes(good);
        for (byte[] tail : new byte[][] { {0x46}, {0x46,0x43,0x50},
                java.nio.ByteBuffer.allocate(15).putInt(0x46435032).putInt(20).putInt(~20).put(new byte[]{1,2,3}).array() }) {
            Path torn = root.resolve("torn-" + tail.length);
            Files.write(torn, original); Files.write(torn, tail, StandardOpenOption.APPEND);
            try (var ledger = new PlayerOperationLedger(torn, CLUSTER, PLAYER)) {
                check(ledger.unfinished().size() == 1, "torn tail preserves durable prefix");
                check(Files.size(torn) == original.length, "torn tail truncated before append");
                ledger.advance(ledger.unfinished().get(0).id(), Stage.ABORTED, null);
                check(ledger.prepare(SLOT).id().counter() == 1, "append after tail repair");
            }
        }
        Path corrupt = root.resolve("corrupt");
        original[20] ^= 1; Files.write(corrupt, original);
        reject(() -> { try (var ledger = new PlayerOperationLedger(corrupt, CLUSTER, PLAYER)) {} },
            "complete corrupt record fails closed");
    }
    private static void timeSkew() throws Exception {
        long remaining = ContinuationTime.exportRemaining(1250, 1200);
        check(remaining == 50, "source +200 exports remaining delay");
        check(ContinuationTime.importDueAt(remaining, 850) == 900, "destination -150 imports delay");
        check(ContinuationTime.exportRemaining(900, 950) == 0, "overdue tick stays due");
        reject(() -> ContinuationTime.importDueAt(1, Long.MAX_VALUE), "timer overflow rejected");
        reject(() -> ContinuationTime.exportRemaining(0, -1), "negative time rejected");
    }
    private interface Throwing { void run() throws Exception; }
    private static void reject(Throwing action, String message) throws Exception {
        boolean rejected = false;
        try { action.run(); } catch (IOException | IllegalArgumentException | IllegalStateException |
                NullPointerException | ArithmeticException e) { rejected = true; }
        check(rejected, message);
    }
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
}
