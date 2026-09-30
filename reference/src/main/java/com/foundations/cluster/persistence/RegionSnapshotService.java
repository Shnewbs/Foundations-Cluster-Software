package com.foundations.cluster.persistence;

import com.foundations.cluster.transfer.RegionKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Creates a stable staged copy of the Anvil region/entities/poi triplet. */
public final class RegionSnapshotService {
    private final Path stagingRoot;

    public RegionSnapshotService(Path stagingRoot) {
        this.stagingRoot = Objects.requireNonNull(stagingRoot, "stagingRoot").toAbsolutePath().normalize();
    }

    public RegionSnapshot capture(
            RegionStorageLayout layout,
            RegionKey region,
            String sourceWorkerId,
            long epoch,
            long revision) {
        String transferId = UUID.randomUUID().toString();
        Path snapshotRoot = stagingRoot.resolve(transferId);
        try {
            Files.createDirectories(snapshotRoot);
            List<RegionFileSnapshot> files = new ArrayList<>();
            long totalBytes = 0L;
            for (RegionFileKind kind : RegionFileKind.values()) {
                Path live = layout.file(region, kind);
                String fileName = live.getFileName().toString();
                Path staged = snapshotRoot.resolve(kind.directory()).resolve(fileName);
                if (Files.exists(live)) {
                    Files.createDirectories(staged.getParent());
                    Path temp = staged.resolveSibling(staged.getFileName() + ".part");
                    Files.copy(live, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    atomicMove(temp, staged);
                    long bytes = Files.size(staged);
                    totalBytes += bytes;
                    files.add(new RegionFileSnapshot(kind, fileName, bytes, Hashing.sha256(staged), true));
                } else {
                    files.add(new RegionFileSnapshot(kind, fileName, 0L, "MISSING", false));
                }
            }
            String aggregate = Hashing.aggregate(files);
            return new RegionSnapshot(
                    transferId,
                    region,
                    sourceWorkerId,
                    epoch,
                    revision,
                    System.currentTimeMillis(),
                    snapshotRoot,
                    files,
                    aggregate,
                    totalBytes);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to capture region snapshot " + region, e);
        }
    }

    public void verify(RegionSnapshot snapshot) {
        try {
            List<RegionFileSnapshot> actual = new ArrayList<>();
            for (RegionFileSnapshot expected : snapshot.files()) {
                Path file = snapshot.snapshotRoot().resolve(expected.kind().directory()).resolve(expected.fileName());
                if (!expected.present()) {
                    if (Files.exists(file)) throw new IllegalStateException("Unexpected staged file " + file);
                    actual.add(expected);
                    continue;
                }
                if (!Files.exists(file)) throw new IllegalStateException("Missing staged file " + file);
                long bytes = Files.size(file);
                String hash = Hashing.sha256(file);
                if (bytes != expected.bytes() || !hash.equals(expected.sha256())) {
                    throw new IllegalStateException("Snapshot file verification failed for " + file);
                }
                actual.add(new RegionFileSnapshot(expected.kind(), expected.fileName(), bytes, hash, true));
            }
            String aggregate = Hashing.aggregate(actual);
            if (!aggregate.equals(snapshot.aggregateSha256())) {
                throw new IllegalStateException("Snapshot aggregate checksum mismatch");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Snapshot verification failed", e);
        }
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
