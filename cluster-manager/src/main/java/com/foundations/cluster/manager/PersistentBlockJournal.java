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
 * 0.0.5a.R7.R4 durable block-mutation journal.
 *
 * This is intentionally manager-owned and independent from any one worker world
 * directory. Authoritative workers commit final block states here before those
 * states are treated as restart-safe. On worker startup/authority acquisition,
 * the current owner replays the journal overlay for its regions and checkpoints
 * the result into its normal Anvil world files.
 *
 * The journal is not a replacement for full region-file migration. It is the
 * first durable persistence layer for the R7 live replica protocol and prevents
 * ordinary block mutations from disappearing when the cluster restarts or a
 * region is reassigned to a worker with an older local world copy.
 */
final class PersistentBlockJournal implements AutoCloseable {
    record Summary(boolean enabled, long revision, int blocks, long walEntries) {}
    record BlockKey(String worldId, String dimensionId, int x, int y, int z) {}
    record Entry(BlockKey key, String regionId, long authorityEpoch, String blockState, long revision, long updatedAtMillis) {}

    private final boolean enabled;
    private final Path snapshotFile;
    private final Path walFile;
    private final ClusterLog log;
    private final ConcurrentHashMap<BlockKey, Entry> latest = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();
    private long walEntries;
    private long nextCompaction = 4096;
    private boolean writeFailed;

    PersistentBlockJournal(ManagerConfig cfg, ClusterLog log) throws IOException {
        this.enabled = cfg.getBoolean("persistence.blockJournalEnabled", true);
        this.log = log;
        Path runtime = cfg.home().resolve("runtime");
        Files.createDirectories(runtime);
        this.snapshotFile = runtime.resolve("persistent-blocks.tsv");
        this.walFile = runtime.resolve("persistent-blocks.wal");
        loadFile(snapshotFile);
        loadFile(walFile);
        log.info("Persistent block journal initialized: enabled=" + enabled + " revision=" + revision.get() + " blocks=" + latest.size());
    }

    boolean enabled() { return enabled; }

    synchronized Entry record(String worldId, String dimensionId, String regionId, long authorityEpoch,
                              int x, int y, int z, String blockState) {
        if (!enabled) return new Entry(new BlockKey(worldId, dimensionId, x, y, z), regionId, authorityEpoch, blockState, revision.get(), System.currentTimeMillis());
        if (regionId == null || regionId.isBlank()) throw new IllegalArgumentException("regionId is required for persistent block journal");
        if (writeFailed) throw new IllegalStateException("Journal write failed; restart and reconcile before accepting more mutations");
        long rev = revision.incrementAndGet();
        Entry entry = new Entry(
                new BlockKey(blank(worldId, "foundations"), blank(dimensionId, "minecraft:overworld"), x, y, z),
                regionId,
                Math.max(1, authorityEpoch),
                blockState == null || blockState.isBlank() ? "minecraft:air" : blockState,
                rev,
                System.currentTimeMillis());
        appendWal(entry);
        latest.put(entry.key(), entry);
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
                        .append(enc(e.blockState())).append('\t')
                        .append(e.revision()).append('\n'));
        if (raw.isEmpty()) return "";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toString().getBytes(StandardCharsets.UTF_8));
    }

    synchronized Summary summary() { return new Summary(enabled, revision.get(), latest.size(), walEntries); }

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
            throw new IllegalStateException("could not durably append persistent block journal: " + ex.getMessage(), ex);
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
        if (p.length != 10) throw new IllegalArgumentException("bad persistent journal row");
        BlockKey key = new BlockKey(dec(p[0]), dec(p[1]), Integer.parseInt(p[4]), Integer.parseInt(p[5]), Integer.parseInt(p[6]));
        return new Entry(key, dec(p[2]), Long.parseLong(p[3]), dec(p[7]), Long.parseLong(p[8]), Long.parseLong(p[9]));
    }

    private String line(Entry e) {
        return enc(e.key().worldId()) + '\t' + enc(e.key().dimensionId()) + '\t' + enc(e.regionId()) + '\t'
                + e.authorityEpoch() + '\t' + e.key().x() + '\t' + e.key().y() + '\t' + e.key().z() + '\t'
                + enc(e.blockState()) + '\t' + e.revision() + '\t' + e.updatedAtMillis() + '\n';
    }

    private synchronized void compactSafe() {
        if (writeFailed) return; // Never compact uncertain writes out of the WAL.
        try { compact(); }
        catch (Exception e) { log.warn("Persistent block journal compaction failed: " + e.getMessage()); }
        finally { nextCompaction = walEntries + 4096; }
    }

    private void compact() throws IOException {
        if (!enabled) return;
        StringBuilder b = new StringBuilder("# Foundations persistent block journal 0.0.5a.R7.R4\n");
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

    private static String blank(String s, String d) { return s == null || s.isBlank() ? d : s; }
    private static String enc(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString((s == null ? "" : s).getBytes(StandardCharsets.UTF_8)); }
    private static String dec(String s) { return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8); }

    @Override public synchronized void close() { compactSafe(); }
}
