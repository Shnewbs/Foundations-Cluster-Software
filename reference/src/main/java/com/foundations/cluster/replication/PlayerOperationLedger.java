package com.foundations.cluster.replication;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.zip.CRC32C;

/** Spike A only: durable protocol model, not a Minecraft inventory/world adapter.
 * One journal belongs to one cluster/player. force() precedes publication of state.
 * New records protect their length with a magic and complement. Complete legacy
 * records remain readable in place; ambiguous legacy tails fail closed without
 * truncation. No migration rewrites existing records.
 * Production group commit and coherent chunk/entity checkpoints remain spike gates.
 */
public final class PlayerOperationLedger implements AutoCloseable {
    public enum Stage { PREPARED, WORLD_EXECUTED, WORLD_RESULT_DURABLE,
        PLAYER_COMMITTED, CLIENT_ACKED, ABORTED }
    public record OperationId(UUID cluster, UUID player, long counter) {
        public OperationId { Objects.requireNonNull(cluster); Objects.requireNonNull(player);
            if (counter < 0) throw new IllegalArgumentException("Negative counter"); }
        public UUID outputEntityId(int index) {
            if (index < 0) throw new IllegalArgumentException("Negative output index");
            return UUID.nameUUIDFromBytes((cluster + "/" + player + "/" + counter + "/" + index)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    public record Reservation(long inventoryRevision, int slot, String stackIdentity, int count) {
        public Reservation {
            if (inventoryRevision < 0 || slot < 0 || count <= 0 || stackIdentity == null
                    || stackIdentity.isBlank()) throw new IllegalArgumentException("Invalid reservation");
        }
    }
    public record Result(String cell, long epoch, long frame, String digest, String journalReceipt) {
        public Result {
            if (cell == null || cell.isBlank() || epoch < 0 || frame < 0 || digest == null
                    || digest.isBlank() || journalReceipt == null || journalReceipt.isBlank())
                throw new IllegalArgumentException("Invalid durable result");
        }
    }
    public record Operation(OperationId id, Reservation reservation, Stage stage, Result result) {}
    private static final int RECORD_MAGIC = 0x46435032; // FCP2 framing; payload schema remains 1.
    private static final int MAX_RECORD_BYTES = 65536;
    private final UUID cluster, player;
    private final FileChannel channel;
    private final FileLock lock;
    private final Map<Long, Operation> operations = new LinkedHashMap<>();
    private long nextCounter;
    private boolean failed;

    public PlayerOperationLedger(Path journal, UUID cluster, UUID player) throws IOException {
        this.cluster = Objects.requireNonNull(cluster);
        this.player = Objects.requireNonNull(player);
        channel = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.READ,
            StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Player ledger already open");
            lock = acquired;
            replay();
        } catch (IOException | RuntimeException e) {
            try { if (acquired != null) acquired.release(); }
            catch (IOException cleanup) { e.addSuppressed(cleanup); }
            try { channel.close(); }
            catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
    }

    public synchronized Operation prepare(Reservation reservation) throws IOException {
        available();
        Objects.requireNonNull(reservation);
        if (nextCounter == Long.MAX_VALUE) throw new IllegalStateException("Operation counter exhausted");
        for (Operation op : operations.values())
            if (reserved(op) && op.reservation.slot == reservation.slot)
                throw new IllegalStateException("Inventory slot already reserved");
        Operation op = new Operation(new OperationId(cluster, player, nextCounter), reservation,
            Stage.PREPARED, null);
        append(op);
        return op;
    }

    public synchronized Operation advance(OperationId id, Stage stage, Result result) throws IOException {
        available();
        Operation old = get(id);
        Objects.requireNonNull(stage);
        if (old.stage == stage && Objects.equals(old.result, result)) return old;
        boolean abort = stage == Stage.ABORTED &&
            (old.stage == Stage.PREPARED || old.stage == Stage.WORLD_EXECUTED);
        boolean next = old.stage != Stage.ABORTED && old.stage != Stage.CLIENT_ACKED &&
            stage.ordinal() == old.stage.ordinal() + 1;
        if (!abort && !next) throw new IllegalStateException("Illegal operation transition");
        if (stage == Stage.CLIENT_ACKED)
            throw new IllegalStateException("Use acknowledge after local frame apply");
        if (stage == Stage.WORLD_RESULT_DURABLE) Objects.requireNonNull(result, "Durability receipt required");
        else if (!Objects.equals(result, old.result)) throw new IllegalStateException("Result changed");
        Operation updated = new Operation(id, old.reservation, stage, result);
        append(updated);
        return updated;
    }

    /** The replica adapter must supply its actually applied cell/epoch/frame, not an observed remote head. */
    public synchronized Operation acknowledge(OperationId id, String cell, long epoch, long appliedFrame)
            throws IOException {
        available();
        Operation old = get(id);
        if (old.stage != Stage.PLAYER_COMMITTED && old.stage != Stage.CLIENT_ACKED)
            throw new IllegalStateException("Player delta not committed");
        Result r = old.result;
        if (!r.cell.equals(cell) || r.epoch != epoch || appliedFrame < r.frame)
            throw new IllegalStateException("Result frame not locally applied");
        if (old.stage == Stage.CLIENT_ACKED) return old;
        Operation updated = new Operation(id, old.reservation, Stage.CLIENT_ACKED, r);
        append(updated);
        return updated;
    }

    public synchronized Operation get(OperationId id) {
        readable();
        Objects.requireNonNull(id);
        if (!cluster.equals(id.cluster) || !player.equals(id.player))
            throw new IllegalArgumentException("Wrong cluster/player");
        Operation op = operations.get(id.counter);
        if (op == null) throw new IllegalArgumentException("Unknown operation");
        return op;
    }
    public synchronized List<Operation> unfinished() {
        readable();
        return operations.values().stream().filter(o -> o.stage != Stage.ABORTED &&
            o.stage != Stage.CLIENT_ACKED).toList();
    }
    public synchronized boolean slotReserved(int slot) {
        readable();
        return operations.values().stream().anyMatch(o -> reserved(o) && o.reservation.slot == slot);
    }
    private static boolean reserved(Operation o) {
        return o.stage == Stage.PREPARED || o.stage == Stage.WORLD_EXECUTED ||
            o.stage == Stage.WORLD_RESULT_DURABLE;
    }

    private void available() throws IOException {
        if (failed || !channel.isOpen() || !lock.isValid())
            throw new IOException("Ledger unavailable; reopen for recovery");
    }
    private void readable() {
        if (failed || !channel.isOpen() || !lock.isValid())
            throw new IllegalStateException("Ledger unavailable; reopen for recovery");
    }
    private void append(Operation op) throws IOException {
        available();
        byte[] payload = encode(op);
        if (payload.length > MAX_RECORD_BYTES) throw new IOException("Record limit exceeded");
        CRC32C crc = new CRC32C(); crc.update(payload);
        ByteBuffer bytes = ByteBuffer.allocate(payload.length + 16);
        bytes.putInt(RECORD_MAGIC).putInt(payload.length).putInt(~payload.length)
            .put(payload).putInt((int) crc.getValue()).flip();
        try {
            channel.position(channel.size());
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (IOException e) { failed = true; throw e; }
        operations.put(op.id.counter, op);
        nextCounter = Math.max(nextCounter, op.id.counter + 1);
    }

    private void replay() throws IOException {
        long offset = 0, size = channel.size();
        boolean protectedSeen = false;
        while (offset < size) {
            long remaining = size - offset;
            if (remaining < 4) {
                // Only a prefix of our protected magic is a repairable new-frame tail.
                // A short legacy length cannot be distinguished from corrupt framing.
                ByteBuffer prefix = ByteBuffer.allocate((int) remaining);
                readFully(prefix, offset);
                byte[] magic = ByteBuffer.allocate(4).putInt(RECORD_MAGIC).array();
                for (int i = 0; i < remaining; i++)
                    if (prefix.array()[i] != magic[i]) throw new IOException("Ambiguous ledger tail; original preserved");
                truncateTail(offset); break;
            }
            ByteBuffer first = ByteBuffer.allocate(4); readFully(first, offset); first.flip();
            int word = first.getInt(), length, headerSize;
            boolean protectedFrame = word == RECORD_MAGIC;
            if (protectedFrame) {
                protectedSeen = true;
                int headerBytes = (int) Math.min(remaining, 12);
                ByteBuffer header = ByteBuffer.allocate(headerBytes); readFully(header, offset); header.flip();
                header.getInt();
                if (headerBytes < 8) { truncateTail(offset); break; }
                length = header.getInt();
                if (length <= 0 || length > MAX_RECORD_BYTES) throw new IOException("Corrupt ledger length");
                byte[] inverse = ByteBuffer.allocate(4).putInt(~length).array();
                for (int i = 0; header.hasRemaining(); i++)
                    if (header.get() != inverse[i]) throw new IOException("Corrupt ledger record header");
                if (headerBytes < 12) { truncateTail(offset); break; }
                headerSize = 12;
            } else {
                if (protectedSeen || word <= 0 || word > MAX_RECORD_BYTES)
                    throw new IOException("Corrupt ledger framing");
                length = word; headerSize = 4;
            }
            long recordSize = headerSize + length + 4L;
            if (remaining < recordSize) {
                if (!protectedFrame) throw new IOException("Incomplete legacy ledger record; original preserved");
                truncateTail(offset); break;
            }
            ByteBuffer body = ByteBuffer.allocate(length + 4); readFully(body, offset + headerSize); body.flip();
            byte[] payload = new byte[length]; body.get(payload);
            CRC32C crc = new CRC32C(); crc.update(payload);
            if (body.getInt() != (int) crc.getValue()) throw new IOException("Corrupt ledger checksum");
            Operation op = decode(payload);
            if (!op.id.cluster.equals(cluster) || !op.id.player.equals(player))
                throw new IOException("Ledger belongs to another cluster/player");
            if (op.id.counter == Long.MAX_VALUE) throw new IOException("Invalid counter");
            validateReplay(op);
            operations.put(op.id.counter, op);
            nextCounter = Math.max(nextCounter, op.id.counter + 1);
            offset += recordSize;
        }
    }
    private void validateReplay(Operation op) throws IOException {
        Operation old = operations.get(op.id.counter);
        if (old == null) {
            if (op.id.counter != nextCounter || op.stage != Stage.PREPARED || op.result != null)
                throw new IOException("Ledger starts an operation out of order");
            if (slotReserved(op.reservation.slot)) throw new IOException("Ledger reserves an occupied slot");
            return;
        }
        boolean abort = op.stage == Stage.ABORTED &&
            (old.stage == Stage.PREPARED || old.stage == Stage.WORLD_EXECUTED);
        boolean next = old.stage != Stage.ABORTED && old.stage != Stage.CLIENT_ACKED &&
            op.stage.ordinal() == old.stage.ordinal() + 1;
        if (!old.reservation.equals(op.reservation) || (!abort && !next))
            throw new IOException("Illegal ledger transition or changed reservation");
        if (op.stage != Stage.WORLD_RESULT_DURABLE && !Objects.equals(old.result, op.result))
            throw new IOException("Ledger result changed outside durability transition");
    }
    private void truncateTail(long offset) throws IOException { channel.truncate(offset); channel.force(true); }
    private void readFully(ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);
            if (read <= 0) throw new EOFException("Incomplete ledger record");
            offset += read;
        }
    }
    private static byte[] encode(Operation o) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1); out.writeUTF(o.id.cluster.toString()); out.writeUTF(o.id.player.toString());
            out.writeLong(o.id.counter); out.writeLong(o.reservation.inventoryRevision);
            out.writeInt(o.reservation.slot); out.writeUTF(o.reservation.stackIdentity);
            out.writeInt(o.reservation.count); out.writeUTF(o.stage.name());
            out.writeBoolean(o.result != null);
            if (o.result != null) { out.writeUTF(o.result.cell); out.writeLong(o.result.epoch);
                out.writeLong(o.result.frame); out.writeUTF(o.result.digest); out.writeUTF(o.result.journalReceipt); }
        }
        return bytes.toByteArray();
    }
    private static Operation decode(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1) throw new IOException("Unsupported ledger schema");
            OperationId id = new OperationId(UUID.fromString(in.readUTF()), UUID.fromString(in.readUTF()), in.readLong());
            Reservation reservation = new Reservation(in.readLong(), in.readInt(), in.readUTF(), in.readInt());
            Stage stage = Stage.valueOf(in.readUTF());
            Result result = in.readBoolean() ? new Result(in.readUTF(), in.readLong(), in.readLong(), in.readUTF(), in.readUTF()) : null;
            if (in.available() != 0) throw new IOException("Trailing ledger data");
            if ((stage == Stage.WORLD_RESULT_DURABLE || stage == Stage.PLAYER_COMMITTED ||
                    stage == Stage.CLIENT_ACKED) && result == null) throw new IOException("Missing durable result");
            return new Operation(id, reservation, stage, result);
        } catch (IllegalArgumentException e) { throw new IOException("Invalid ledger record", e); }
    }
    @Override public synchronized void close() throws IOException {
        try { if (lock.isValid()) lock.release(); } finally { channel.close(); }
    }
}
