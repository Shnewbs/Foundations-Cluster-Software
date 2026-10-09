package com.foundations.cluster.replication;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32C;
import com.foundations.cluster.replication.PlayerOperationLedger.OperationId;
import com.foundations.cluster.replication.PlayerOperationLedger.Result;

/**
 * Spike A cell-owned result barrier. Opaque payloads contain the adapter's world
 * mutations, output entities and player delta, not just their hash. This does NOT
 * make Minecraft chunk/entity saves coherent: that is the separate Spike C gate.
 * No compaction is permitted until a coherent checkpoint implementation exists.
 */
public final class AuthorityResultJournal implements AutoCloseable {
    private static final int MAGIC = 0x46434A31; // FCJ1; length and complement protect torn/corrupt framing.
    private static final int LIMIT = 2 * 1024 * 1024;
    public record DurableResult(OperationId id, String cell, long epoch, long frame,
                                String digest, byte[] payload) {
        public DurableResult { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
        public Result receipt() { return new Result(cell, epoch, frame, digest,
            "authority-v1/" + id.cluster() + "/" + id.player() + "/" + id.counter() + "/" + digest); }
    }
    private final UUID cluster;
    private final String cell;
    private final FileChannel channel;
    private final FileLock lock;
    private final Map<OperationId, DurableResult> results = new LinkedHashMap<>();
    private long lastFrame, lastEpoch = -1;
    private boolean failed;

    public AuthorityResultJournal(Path path, UUID cluster, String cell) throws IOException {
        this.cluster = Objects.requireNonNull(cluster);
        if (cell == null || cell.isBlank() || cell.length() > 1024) throw new IllegalArgumentException("Invalid cell");
        this.cell = cell;
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Authority journal already open");
            lock = acquired;
            replay();
        } catch (IOException | RuntimeException e) {
            try { if (acquired != null) acquired.release(); } finally { channel.close(); }
            throw e;
        }
    }

    /** Returns an identical duplicate without writing; conflicting retries fail closed. */
    public synchronized DurableResult commit(OperationId id, long epoch, byte[] payload) throws IOException {
        available(); Objects.requireNonNull(id); Objects.requireNonNull(payload);
        if (!id.cluster().equals(cluster) || epoch < 0 || payload.length == 0 || payload.length > LIMIT - 4096)
            throw new IllegalArgumentException("Invalid authority result");
        DurableResult old = results.get(id);
        if (old != null) {
            if (old.epoch != epoch || !Arrays.equals(old.payload, payload)) throw new IllegalStateException("Conflicting authority retry");
            return old;
        }
        if (epoch < lastEpoch) throw new IllegalStateException("Stale authority epoch");
        if (lastFrame == Long.MAX_VALUE) throw new IllegalStateException("Frame counter exhausted");
        DurableResult result = new DurableResult(id, cell, epoch, lastFrame + 1, "", payload);
        byte[] encoded = encode(result);
        result = withDigest(result, encoded);
        CRC32C crc = new CRC32C(); crc.update(encoded);
        ByteBuffer record = ByteBuffer.allocate(encoded.length + 16).putInt(MAGIC).putInt(encoded.length).putInt(~encoded.length).put(encoded).putInt((int)crc.getValue());
        record.flip();
        try {
            channel.position(channel.size());
            while (record.hasRemaining()) channel.write(record);
            channel.force(true);
        } catch (IOException e) { failed = true; throw e; }
        publish(result);
        return result;
    }

    public synchronized Optional<DurableResult> find(OperationId id) throws IOException {
        available();
        if (!cluster.equals(id.cluster())) throw new IllegalArgumentException("Wrong cluster");
        return Optional.ofNullable(results.get(id));
    }
    public synchronized List<DurableResult> retained() throws IOException { available(); return List.copyOf(results.values()); }
    private void available() throws IOException {
        if (failed || !channel.isOpen()) throw new IOException("Authority journal unavailable; reopen for recovery");
    }
    private void publish(DurableResult r) { results.put(r.id, r); lastFrame = r.frame; lastEpoch = r.epoch; }
    private void replay() throws IOException {
        long offset = 0, size = channel.size();
        while (offset < size) {
            if (size - offset < 12) { truncate(offset); break; }
            ByteBuffer header = ByteBuffer.allocate(12); read(header, offset); header.flip();
            int magic = header.getInt(), length = header.getInt(), inverse = header.getInt();
            if (magic != MAGIC || inverse != ~length) throw new IOException("Corrupt authority record header");
            if (length < 1 || length > LIMIT) throw new IOException("Corrupt authority record length");
            if (size - offset < length + 16L) { truncate(offset); break; }
            ByteBuffer record = ByteBuffer.allocate(length + 4); read(record, offset + 12); record.flip();
            byte[] data = new byte[length]; record.get(data);
            CRC32C crc = new CRC32C(); crc.update(data);
            if (record.getInt() != (int)crc.getValue()) throw new IOException("Corrupt authority record checksum");
            DurableResult r = decode(data);
            if (!cluster.equals(r.id.cluster()) || !cell.equals(r.cell) || r.epoch < lastEpoch ||
                    r.frame != lastFrame + 1 || results.containsKey(r.id)) throw new IOException("Invalid authority record identity/order");
            publish(withDigest(r, data));
            offset += length + 16L;
        }
    }
    private void truncate(long offset) throws IOException { channel.truncate(offset); channel.force(true); }
    private void read(ByteBuffer target, long offset) throws IOException {
        while (target.hasRemaining()) { int n = channel.read(target, offset); if (n <= 0) throw new EOFException(); offset += n; }
    }
    private static byte[] encode(DurableResult r) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1); out.writeUTF(r.id.cluster().toString()); out.writeUTF(r.id.player().toString());
            out.writeLong(r.id.counter()); out.writeUTF(r.cell); out.writeLong(r.epoch); out.writeLong(r.frame);
            out.writeInt(r.payload.length); out.write(r.payload);
        }
        byte[] data = bytes.toByteArray();
        if (data.length > LIMIT) throw new IOException("Authority record too large");
        return data;
    }
    private static DurableResult decode(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != 1) throw new IOException("Unsupported authority record schema");
            OperationId id = new OperationId(UUID.fromString(in.readUTF()), UUID.fromString(in.readUTF()), in.readLong());
            String cell = in.readUTF(); long epoch = in.readLong(), frame = in.readLong();
            int length = in.readInt();
            if (cell.isBlank() || epoch < 0 || frame < 1 || length < 1 || length != in.available())
                throw new IOException("Invalid authority record");
            return new DurableResult(id, cell, epoch, frame, "", in.readNBytes(length));
        } catch (IllegalArgumentException e) { throw new IOException("Invalid authority record", e); }
    }
    private static DurableResult withDigest(DurableResult r, byte[] encoded) {
        try { return new DurableResult(r.id, r.cell, r.epoch, r.frame,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)), r.payload); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    @Override public synchronized void close() throws IOException {
        try { if (lock.isValid()) lock.release(); } finally { channel.close(); }
    }
}
