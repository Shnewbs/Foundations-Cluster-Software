package com.foundations.cluster.protocol;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32C;

/**
 * Experimental anchor-owned inventory journal. A COMMIT contains the complete
 * inventory image AND its applied operation identity in one forced record.
 * Never installs itself into Minecraft player persistence or packet handling.
 * Epochs and authority receipts MUST be supplied by a trusted coordinator; this
 * local file lock is not a distributed lease. No checkpoint/compaction yet.
 * One pending operation per player deliberately serializes the initial adapter.
 */
public final class PlayerInventoryJournal implements AutoCloseable {
    public static final int MAX_IMAGE_BYTES = 1024 * 1024;
    private static final int MAGIC = 0x46434931;
    private static final int MAX_RECORD_BYTES = MAX_IMAGE_BYTES + 8192;
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_OPERATIONS = 4096;
    private static final int INIT = 0, CLAIM = 1, RESERVE = 2, COMMIT = 3, OBSERVE = 4;
    public record OperationId(UUID cluster, UUID player, long counter) {
        public OperationId {
            Objects.requireNonNull(cluster); Objects.requireNonNull(player);
            if (counter < 0 || counter == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid operation counter");
        }
    }
    public record Reservation(OperationId id, long revision, int slot) {}
    /** Identifies an already-durable authority result; not proof of authenticity. */
    public record WorldReceipt(String cell, long epoch, long frame, String digest) {
        public WorldReceipt {
            if (cell == null || cell.isBlank() || cell.length() > 1024 || epoch < 0 || frame < 1 ||
                    digest == null || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid world receipt");
        }
    }
    public record Applied(OperationId id, long revision, int slot, WorldReceipt receipt, String imageDigest) {}
    public record CommitResult(Applied applied, boolean newCommit) {}
    public record View(UUID cluster, UUID player, long anchorEpoch, long revision, long nextCounter, byte[] image,
                       Reservation pending, int appliedCount) {
        public View { image = image.clone(); }
        @Override public byte[] image() { return image.clone(); }
    }
    private record Event(int type, long sequence, long epoch, long revision, long counter,
                         int slot, WorldReceipt receipt, byte[] image) {}
    private final UUID cluster, player;
    private final FileChannel channel;
    private final FileLock lock;
    private final Map<Long, Applied> applied = new LinkedHashMap<>();
    private byte[] image;
    private Reservation pending;
    private long epoch = -1, revision, nextCounter, sequence;
    private boolean failed;
    public PlayerInventoryJournal(Path path, UUID cluster, UUID player) throws IOException {
        this.cluster = Objects.requireNonNull(cluster); this.player = Objects.requireNonNull(player);
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Inventory journal already open");
            lock = acquired;
            replay();
        } catch (IOException | RuntimeException e) {
            try { if (acquired != null) acquired.release(); } catch (IOException ex) { e.addSuppressed(ex); }
            try { channel.close(); } catch (IOException ex) { e.addSuppressed(ex); }
            throw e;
        }
    }
    /** Explicit bootstrap only. Opening an existing file never overwrites it with a vanilla save. */
    public synchronized void initialize(long anchorEpoch, byte[] initialImage) throws IOException {
        available();
        if (image != null) throw new IllegalStateException("Inventory is already initialized");
        append(new Event(INIT, sequence + 1, anchorEpoch, 0, 0, -1, null, checkedImage(initialImage)));
    }
    public synchronized boolean initialized() throws IOException { available(); return image != null; }
    public synchronized View view() throws IOException {
        ready(); return new View(cluster, player, epoch, revision, nextCounter, image, pending, applied.size());
    }
    public synchronized Optional<Applied> applied(OperationId id) throws IOException {
        ready(); identity(id); return Optional.ofNullable(applied.get(id.counter));
    }
    /** The caller obtains this monotonically increasing epoch from the anchor authority. */
    public synchronized void claim(long newEpoch) throws IOException {
        ready();
        if (newEpoch < epoch) throw new IllegalStateException("Stale anchor epoch");
        if (newEpoch == epoch) return;
        append(new Event(CLAIM, sequence + 1, newEpoch, revision, nextCounter, -1, null, new byte[0]));
    }
    public synchronized Reservation reserve(long anchorEpoch, long expectedRevision, int slot, byte[] observedImage)
            throws IOException {
        fence(anchorEpoch);
        if (expectedRevision != revision || !Arrays.equals(image, Objects.requireNonNull(observedImage)))
            throw new IllegalStateException("Inventory changed before reservation");
        append(new Event(RESERVE, sequence + 1, epoch, revision, nextCounter, slot, null, new byte[0]));
        return pending;
    }
    /**
     * For coordinated local inventory changes when no operation is pending.
     * This is not a packet/mixin guard: the future live adapter must intercept all
     * inventory mutators. An uncoordinated change is rejected by reserve/commit.
     */
    public synchronized void observe(long anchorEpoch, long expectedRevision, byte[] newImage) throws IOException {
        fence(anchorEpoch);
        if (expectedRevision != revision) throw new IllegalStateException("Stale inventory revision");
        byte[] copy = checkedImage(newImage);
        if (pending != null) throw new IllegalStateException("Inventory is reserved");
        if (Arrays.equals(image, copy)) return;
        append(new Event(OBSERVE, sequence + 1, epoch, nextRevision(), nextCounter, -1, null, copy));
    }
    public synchronized CommitResult commit(long anchorEpoch, OperationId id, WorldReceipt receipt,
                                             byte[] afterImage) throws IOException {
        fence(anchorEpoch); identity(id); Objects.requireNonNull(receipt);
        byte[] copy = checkedImage(afterImage);
        Applied old = applied.get(id.counter);
        if (old != null) {
            if (!old.receipt.equals(receipt) || !old.imageDigest.equals(digest(copy)))
                throw new IllegalStateException("Conflicting player delta retry");
            return new CommitResult(old, false);
        }
        if (pending == null || !pending.id.equals(id)) throw new IllegalStateException("No matching inventory reservation");
        append(new Event(COMMIT, sequence + 1, epoch, nextRevision(), id.counter, pending.slot, receipt, copy));
        return new CommitResult(applied.get(id.counter), true);
    }
    // No timeout-driven abort: a missing receipt is not proof the world action did not execute.
    private long nextRevision() { return Math.addExact(revision, 1); }
    private void identity(OperationId id) {
        Objects.requireNonNull(id);
        if (!cluster.equals(id.cluster) || !player.equals(id.player)) throw new IllegalArgumentException("Wrong cluster/player");
    }
    private void available() throws IOException {
        if (failed || !channel.isOpen() || !lock.isValid()) throw new IOException("Inventory journal unavailable; reopen for recovery");
    }
    private void ready() throws IOException { available(); if (image == null) throw new IllegalStateException("Inventory is not initialized"); }
    private void fence(long anchorEpoch) throws IOException {
        ready(); if (anchorEpoch != epoch) throw new IllegalStateException("Stale anchor epoch");
    }
    private static byte[] checkedImage(byte[] value) {
        Objects.requireNonNull(value);
        if (value.length == 0 || value.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Invalid inventory image size");
        return value.clone();
    }
    private void validate(Event e) {
        if (sequence == Long.MAX_VALUE || e.sequence != sequence + 1 || e.epoch < 0 || e.revision < 0 ||
                e.counter < 0 || e.counter == Long.MAX_VALUE) throw new IllegalStateException("Invalid inventory record order");
        if (e.type == INIT) {
            if (image != null || sequence != 0 || e.revision != 0 || e.counter != 0 || e.slot != -1 || e.receipt != null || e.image.length == 0)
                throw new IllegalStateException("Invalid inventory initialization");
            return;
        }
        if (image == null) throw new IllegalStateException("Missing inventory initialization");
        switch (e.type) {
            case CLAIM -> {
                if (e.epoch <= epoch || e.revision != revision || e.counter != nextCounter || e.slot != -1 || e.receipt != null || e.image.length != 0)
                    throw new IllegalStateException("Invalid anchor claim");
            }
            case RESERVE -> {
                if (e.epoch != epoch || pending != null || e.revision != revision || e.counter != nextCounter ||
                        e.slot < 0 || e.slot > 40 || e.receipt != null || e.image.length != 0 || applied.size() >= MAX_OPERATIONS)
                    throw new IllegalStateException("Invalid/occupied inventory reservation or checkpoint required");
            }
            case COMMIT -> {
                if (e.epoch != epoch || pending == null || e.counter != pending.id.counter || e.slot != pending.slot ||
                        e.revision != nextRevision() || e.receipt == null || e.image.length == 0 || applied.containsKey(e.counter))
                    throw new IllegalStateException("Invalid player delta commit");
            }
            case OBSERVE -> {
                if (e.epoch != epoch || pending != null || e.revision != nextRevision() || e.counter != nextCounter ||
                        e.slot != -1 || e.receipt != null || e.image.length == 0)
                    throw new IllegalStateException("Invalid local inventory observation");
            }
            default -> throw new IllegalStateException("Unknown inventory record type");
        }
    }
    private void publish(Event e) {
        sequence = e.sequence;
        switch (e.type) {
            case INIT -> { image = e.image; epoch = e.epoch; }
            case CLAIM -> epoch = e.epoch;
            case RESERVE -> { pending = new Reservation(new OperationId(cluster, player, e.counter), revision, e.slot); nextCounter++; }
            case COMMIT -> {
                Applied result = new Applied(pending.id, e.revision, e.slot, e.receipt, digest(e.image));
                image = e.image; revision = e.revision; applied.put(e.counter, result); pending = null;
            }
            case OBSERVE -> { image = e.image; revision = e.revision; }
            default -> throw new AssertionError();
        }
    }
    private void append(Event e) throws IOException {
        available(); validate(e);
        byte[] data = encode(e);
        CRC32C crc = new CRC32C(); crc.update(data);
        ByteBuffer bytes = ByteBuffer.allocate(data.length + 16).putInt(MAGIC).putInt(data.length)
            .putInt(~data.length).put(data).putInt((int) crc.getValue()); bytes.flip();
        if (channel.size() > MAX_FILE_BYTES - bytes.remaining()) throw new IOException("Inventory journal full; coherent checkpoint required");
        try {
            channel.position(channel.size());
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (IOException e1) { failed = true; throw e1; }
        publish(e);
    }
    private void replay() throws IOException {
        long size = channel.size(), offset = 0;
        if (size > MAX_FILE_BYTES) throw new IOException("Inventory journal exceeds recovery limit");
        while (offset < size) {
            int headerLength = (int) Math.min(12, size - offset);
            ByteBuffer header = ByteBuffer.allocate(headerLength); readFully(header, offset); header.flip();
            if (headerLength < 4) {
                byte[] magic = ByteBuffer.allocate(4).putInt(MAGIC).array();
                for (int i = 0; i < headerLength; i++)
                    if (header.get() != magic[i]) throw new IOException("Corrupt inventory tail");
                truncate(offset); break;
            }
            if (header.getInt() != MAGIC) throw new IOException("Corrupt inventory magic");
            if (headerLength < 8) { truncate(offset); break; }
            int length = header.getInt();
            if (length < 1 || length > MAX_RECORD_BYTES) throw new IOException("Corrupt inventory record length");
            byte[] inverse = ByteBuffer.allocate(4).putInt(~length).array();
            for (int i = 0; header.hasRemaining(); i++)
                if (header.get() != inverse[i]) throw new IOException("Corrupt inventory framing");
            if (headerLength < 12 || size - offset < length + 16L) { truncate(offset); break; }
            ByteBuffer body = ByteBuffer.allocate(length + 4); readFully(body, offset + 12); body.flip();
            byte[] data = new byte[length]; body.get(data); CRC32C crc = new CRC32C(); crc.update(data);
            if (body.getInt() != (int) crc.getValue()) throw new IOException("Corrupt inventory checksum");
            Event e = decode(data);
            try { validate(e); } catch (IllegalArgumentException | IllegalStateException | ArithmeticException ex) {
                throw new IOException("Invalid inventory history", ex);
            }
            publish(e); offset += length + 16L;
        }
    }
    private byte[] encode(Event e) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1); out.writeLong(cluster.getMostSignificantBits()); out.writeLong(cluster.getLeastSignificantBits());
            out.writeLong(player.getMostSignificantBits()); out.writeLong(player.getLeastSignificantBits());
            out.writeInt(e.type); out.writeLong(e.sequence); out.writeLong(e.epoch); out.writeLong(e.revision);
            out.writeLong(e.counter); out.writeInt(e.slot); out.writeBoolean(e.receipt != null);
            if (e.receipt != null) { out.writeUTF(e.receipt.cell); out.writeLong(e.receipt.epoch);
                out.writeLong(e.receipt.frame); out.writeUTF(e.receipt.digest); }
            out.writeInt(e.image.length); out.write(e.image);
        }
        if (bytes.size() > MAX_RECORD_BYTES) throw new IOException("Inventory record too large");
        return bytes.toByteArray();
    }
    private Event decode(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != 1) throw new IOException("Unsupported inventory schema");
            if (!cluster.equals(new UUID(in.readLong(), in.readLong())) || !player.equals(new UUID(in.readLong(), in.readLong())))
                throw new IOException("Inventory journal belongs to a different cluster/player");
            int type = in.readInt(); long seq = in.readLong(), ep = in.readLong(), rev = in.readLong(), counter = in.readLong();
            int slot = in.readInt();
            WorldReceipt receipt = in.readBoolean() ? new WorldReceipt(in.readUTF(), in.readLong(), in.readLong(), in.readUTF()) : null;
            int length = in.readInt();
            if (length < 0 || length > MAX_IMAGE_BYTES || length != in.available()) throw new IOException("Invalid inventory payload");
            return new Event(type, seq, ep, rev, counter, slot, receipt, in.readNBytes(length));
        } catch (IllegalArgumentException e) { throw new IOException("Invalid inventory record", e); }
    }
    private void readFully(ByteBuffer buffer, long at) throws IOException {
        while (buffer.hasRemaining()) { int n = channel.read(buffer, at); if (n <= 0) throw new EOFException(); at += n; }
    }
    private void truncate(long at) throws IOException { channel.truncate(at); channel.force(true); }
    public static String digest(byte[] image) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    @Override public synchronized void close() throws IOException {
        try { if (lock.isValid()) lock.release(); } finally { channel.close(); }
    }
}
