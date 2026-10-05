package com.foundations.cluster.manager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 0.0.6a.R13 opaque BlockEntity persistence journal.
 *
 * R11 keeps the manager NBT-agnostic but persists the host block state beside
 * each BlockEntity payload.  That makes replay atomic from Minecraft's point of
 * view: the destination can recreate the correct host block before loading NBT,
 * instead of endlessly deferring a beehive/chest/etc. because the local chunk
 * currently contains air or a mismatched block.
 *
 * Snapshot/WAL parsing remains backwards compatible with the R7/R10 layout.
 */
final class PersistentBlockEntityJournal implements AutoCloseable {
    record Summary(boolean enabled, long revision, int blockEntities, long walEntries, long payloadBytes) {}
    record BlockEntityKey(String worldId, String dimensionId, int x, int y, int z) {}
    record Entry(BlockEntityKey key, String regionId, long authorityEpoch, boolean present,
                 String typeId, String blockState, String nbtPayload, long revision, long updatedAtMillis) {}

    private final boolean enabled;
    private final Path snapshotFile;
    private final Path walFile;
    private final ClusterLog log;
    private final ConcurrentHashMap<BlockEntityKey, Entry> latest = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();
    private long walEntries;
    private long nextCompaction = 2048;
    private boolean writeFailed;
    private long payloadBytes;

    PersistentBlockEntityJournal(ManagerConfig cfg, ClusterLog log) throws IOException {
        this.enabled = cfg.getBoolean("persistence.blockEntityJournalEnabled", true);
        this.log = log;
        Path runtime = cfg.home().resolve("runtime");
        Files.createDirectories(runtime);
        this.snapshotFile = runtime.resolve("persistent-block-entities.tsv");
        this.walFile = runtime.resolve("persistent-block-entities.wal");
        loadFile(snapshotFile);
        loadFile(walFile);
        recomputePayloadBytes();
        log.info("Persistent BlockEntity journal initialized: enabled=" + enabled + " revision=" + revision.get()
                + " blockEntities=" + latest.size() + " payloadBytes=" + payloadBytes + " schema=host-block-v3");
    }

    boolean enabled() { return enabled; }

    synchronized Entry record(String worldId, String dimensionId, String regionId, long authorityEpoch,
                              int x, int y, int z, boolean present, String typeId, String blockState, String nbtPayload) {
        BlockEntityKey key = new BlockEntityKey(blank(worldId, "foundations"), blank(dimensionId, "minecraft:overworld"), x, y, z);
        if (!enabled) return new Entry(key, regionId, authorityEpoch, present, blank(typeId, ""), blank(blockState, ""),
                blank(nbtPayload, ""), revision.get(), System.currentTimeMillis());
        if (regionId == null || regionId.isBlank()) throw new IllegalArgumentException("regionId is required for persistent BlockEntity journal");
        if (present && (nbtPayload == null || nbtPayload.isBlank())) throw new IllegalArgumentException("present BlockEntity persistence requires NBT payload");
        if (writeFailed) throw new IllegalStateException("Journal write failed; restart and reconcile before accepting more mutations");
        long rev = revision.incrementAndGet();
        Entry entry = new Entry(key, regionId, Math.max(1, authorityEpoch), present,
                blank(typeId, ""), present ? blank(blockState, "") : "", present ? blank(nbtPayload, "") : "",
                rev, System.currentTimeMillis());
        appendWal(entry);
        Entry old = latest.put(entry.key(), entry);
        if (old != null) payloadBytes -= payloadSize(old);
        payloadBytes += payloadSize(entry);
        walEntries++;
        if (walEntries >= nextCompaction) compactSafe();
        return entry;
    }

    String bundleForRegions(Collection<RegionRecord> ownedRegions) {
        if (!enabled || ownedRegions == null || ownedRegions.isEmpty()) return "";
        Set<String> ids = new HashSet<>();
        for (RegionRecord r : ownedRegions) if (r != null && r.key != null) ids.add(r.key.id());
        if (ids.isEmpty()) return "";
        StringBuilder raw = new StringBuilder();
        latest.values().stream()
                .filter(e -> ids.contains(e.regionId()))
                .sorted(Comparator.comparingLong(Entry::revision))
                .forEach(e -> raw.append(enc(e.key().worldId())).append('\t')
                        .append(enc(e.key().dimensionId())).append('\t')
                        .append(enc(e.regionId())).append('\t')
                        .append(e.authorityEpoch()).append('\t')
                        .append(e.key().x()).append('\t').append(e.key().y()).append('\t').append(e.key().z()).append('\t')
                        .append(e.present() ? '1' : '0').append('\t')
                        .append(enc(e.typeId())).append('\t')
                        .append(enc(e.blockState())).append('\t')
                        .append(enc(e.nbtPayload())).append('\t')
                        .append(e.revision()).append('\n'));
        if (raw.isEmpty()) return "";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));
    }

    synchronized Summary summary() { return new Summary(enabled, revision.get(), latest.size(), walEntries, Math.max(0, payloadBytes)); }

    synchronized byte[] diagnosticSnapshot(int maxBytes) {
        var out=new java.io.ByteArrayOutputStream();
        for(Entry entry:latest.values().stream().sorted(Comparator.comparingLong(Entry::revision)).toList()) {
            byte[] bytes=line(entry).getBytes(StandardCharsets.UTF_8);
            if(out.size()+(long)bytes.length>maxBytes)return null;
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }

    private void appendWal(Entry e) {
        try {
            Files.createDirectories(walFile.getParent());
            byte[] bytes = line(e).getBytes(StandardCharsets.UTF_8);
            try (FileChannel ch = FileChannel.open(walFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                DurableFiles.writeFully(ch, ByteBuffer.wrap(bytes));
                ch.force(true);
            }
        } catch (IOException ex) {
            writeFailed = true; // The append outcome may be uncertain; never reuse the revision.
            throw new IllegalStateException("could not durably append persistent BlockEntity journal: " + ex.getMessage(), ex);
        }
    }

    private void loadFile(Path file) throws IOException {
        DurableFiles.validateJournal(file);
        if (!Files.exists(file)) return;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        String line; int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank() || line.startsWith("#")) continue;
            try {
                Entry e = parse(line);
                if (e.revision() < 1 || e.authorityEpoch() < 1 || e.updatedAtMillis() < 1 || e.regionId().isBlank() || e.key().worldId().isBlank() || e.key().dimensionId().isBlank())
                    throw new IllegalArgumentException("Invalid persistence identity/revision");
                latest.merge(e.key(), e, (a,b) -> a.revision() >= b.revision() ? a : b);
                revision.accumulateAndGet(e.revision(), Math::max);
                if (file.equals(walFile)) walEntries++;
            } catch (RuntimeException ex) {
                throw new IOException("Invalid persistence record in " + file.getFileName() + " at line " + lineNumber + "; restore/reconcile the journal before starting", ex);
            }
        }
        }
    }

    private Entry parse(String line) {
        String[] p = line.split("\\t", -1);
        if (p.length < 8 || (!p[7].equals("0") && !p[7].equals("1"))) throw new IllegalArgumentException("Invalid presence flag");
        // R11 v3: world dim region epoch x y z present type blockState nbt rev updated = 13
        if (p.length == 13) {
            BlockEntityKey key = new BlockEntityKey(dec(p[0]), dec(p[1]), Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]));
            return new Entry(key, dec(p[2]), Long.parseLong(p[3]), "1".equals(p[7]), dec(p[8]), dec(p[9]), dec(p[10]), Long.parseLong(p[11]), Long.parseLong(p[12]));
        }
        // Legacy R7-R10 v2: world dim region epoch x y z present type nbt rev updated = 12
        if (p.length != 12) throw new IllegalArgumentException("bad persistent BlockEntity journal row");
        BlockEntityKey key = new BlockEntityKey(dec(p[0]), dec(p[1]), Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]));
        return new Entry(key, dec(p[2]), Long.parseLong(p[3]), "1".equals(p[7]), dec(p[8]), "", dec(p[9]), Long.parseLong(p[10]), Long.parseLong(p[11]));
    }

    private String line(Entry e) {
        return enc(e.key().worldId()) + '\t' + enc(e.key().dimensionId()) + '\t' + enc(e.regionId()) + '\t'
                + e.authorityEpoch() + '\t' + e.key().x() + '\t' + e.key().y() + '\t' + e.key().z() + '\t'
                + (e.present() ? "1" : "0") + '\t' + enc(e.typeId()) + '\t' + enc(e.blockState()) + '\t' + enc(e.nbtPayload()) + '\t'
                + e.revision() + '\t' + e.updatedAtMillis() + '\n';
    }

    private synchronized void compactSafe() {
        if (writeFailed) return; // Never compact uncertain writes out of the WAL.
        try { compact(); }
        catch (Exception e) { log.warn("Persistent BlockEntity journal compaction failed: " + e.getMessage()); }
        finally { nextCompaction = walEntries + 2048; }
    }

    private void compact() throws IOException {
        if (!enabled) return;
        StringBuilder b = new StringBuilder("# Foundations persistent BlockEntity journal 0.0.6a.R13 host-block-v3\n");
        latest.values().stream().sorted(Comparator.comparingLong(Entry::revision)).forEach(e -> b.append(line(e)));
        boolean directoryForced = DurableFiles.replace(snapshotFile, b.toString().getBytes(StandardCharsets.UTF_8));
        if (!directoryForced) {
            log.warn("Snapshot written, but directory sync unavailable; retaining recovery WAL");
            return;
        }
        try (FileChannel ch = FileChannel.open(walFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ch.truncate(0);
            ch.force(true);
        }
        walEntries = 0;
    }

    private void recomputePayloadBytes() {
        long total = 0;
        for (Entry e : latest.values()) total += payloadSize(e);
        payloadBytes = total;
    }

    private static long payloadSize(Entry e) { return e.present() ? (long)e.nbtPayload().length() + e.blockState().length() : 0; }
    private static String blank(String s, String d) { return s == null || s.isBlank() ? d : s; }
    private static String enc(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8)); }
    private static String dec(String s) { return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8); }

    @Override public synchronized void close() { compactSafe(); }
}
