package com.foundations.cluster.persistence;

import com.foundations.cluster.transfer.RegionKey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Installs a verified snapshot using stage -> backup -> promote semantics.
 * Existing destination files are never overwritten in-place.
 */
public final class RegionInstallService {
    private final Path backupRoot;
    private final TransferJournal journal;

    public RegionInstallService(Path backupRoot, TransferJournal journal) {
        this.backupRoot = Objects.requireNonNull(backupRoot, "backupRoot").toAbsolutePath().normalize();
        this.journal = Objects.requireNonNull(journal, "journal");
    }

    public InstallResult install(
            RegionSnapshot snapshot,
            RegionStorageLayout destination,
            String destinationWorkerId) {
        RegionKey region = snapshot.region();
        Path transferBackup = backupRoot.resolve(snapshot.transferId());
        List<Path> promoted = new ArrayList<>();
        List<Path> backedUp = new ArrayList<>();
        journal.write(snapshot.transferId(), region, snapshot.sourceWorkerId(), destinationWorkerId,
                snapshot.epoch(), snapshot.revision(), snapshot.aggregateSha256(), "INSTALL_PREPARE");
        try {
            Files.createDirectories(transferBackup);
            // Back up every destination member first.
            for (RegionFileSnapshot file : snapshot.files()) {
                Path live = destination.file(region, file.kind());
                if (Files.exists(live)) {
                    Path backup = transferBackup.resolve(file.kind().directory()).resolve(file.fileName());
                    Files.createDirectories(backup.getParent());
                    atomicMove(live, backup);
                    backedUp.add(live);
                }
            }
            journal.write(snapshot.transferId(), region, snapshot.sourceWorkerId(), destinationWorkerId,
                    snapshot.epoch(), snapshot.revision(), snapshot.aggregateSha256(), "DEST_BACKED_UP");

            for (RegionFileSnapshot file : snapshot.files()) {
                Path live = destination.file(region, file.kind());
                if (!file.present()) {
                    Files.deleteIfExists(live);
                    continue;
                }
                Path source = snapshot.snapshotRoot().resolve(file.kind().directory()).resolve(file.fileName());
                Files.createDirectories(live.getParent());
                Path incoming = live.resolveSibling(live.getFileName() + ".incoming-" + snapshot.transferId());
                Files.copy(source, incoming, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                if (Files.size(incoming) != file.bytes() || !Hashing.sha256(incoming).equals(file.sha256())) {
                    throw new IOException("Incoming verification failed for " + incoming);
                }
                atomicMove(incoming, live);
                promoted.add(live);
            }

            List<RegionFileSnapshot> installed = inspect(destination, region, snapshot.files());
            String aggregate = Hashing.aggregate(installed);
            if (!aggregate.equals(snapshot.aggregateSha256())) {
                throw new IOException("Installed aggregate checksum mismatch");
            }
            journal.write(snapshot.transferId(), region, snapshot.sourceWorkerId(), destinationWorkerId,
                    snapshot.epoch(), snapshot.revision(), aggregate, "DEST_INSTALLED_VERIFIED");
            return new InstallResult(aggregate, snapshot.totalBytes(), snapshot.files().size(), transferBackup);
        } catch (Exception failure) {
            rollback(destination, region, snapshot, transferBackup, promoted, backedUp);
            journal.write(snapshot.transferId(), region, snapshot.sourceWorkerId(), destinationWorkerId,
                    snapshot.epoch(), snapshot.revision(), snapshot.aggregateSha256(), "INSTALL_ROLLED_BACK");
            throw new IllegalStateException("Region install failed and was rolled back", failure);
        }
    }

    private static List<RegionFileSnapshot> inspect(
            RegionStorageLayout layout,
            RegionKey region,
            List<RegionFileSnapshot> expected) throws IOException {
        List<RegionFileSnapshot> result = new ArrayList<>();
        for (RegionFileSnapshot e : expected) {
            Path file = layout.file(region, e.kind());
            if (!e.present()) {
                if (Files.exists(file)) throw new IOException("Expected missing file still exists: " + file);
                result.add(e);
            } else {
                if (!Files.exists(file)) throw new IOException("Expected installed file missing: " + file);
                result.add(new RegionFileSnapshot(e.kind(), e.fileName(), Files.size(file), Hashing.sha256(file), true));
            }
        }
        return result;
    }

    private static void rollback(
            RegionStorageLayout destination,
            RegionKey region,
            RegionSnapshot snapshot,
            Path transferBackup,
            List<Path> promoted,
            List<Path> backedUp) {
        try {
            for (Path live : promoted) Files.deleteIfExists(live);
            for (RegionFileSnapshot file : snapshot.files()) {
                Path live = destination.file(region, file.kind());
                Path backup = transferBackup.resolve(file.kind().directory()).resolve(file.fileName());
                if (Files.exists(backup)) {
                    Files.createDirectories(live.getParent());
                    atomicMove(backup, live);
                }
            }
        } catch (IOException rollbackFailure) {
            throw new IllegalStateException("CRITICAL: region install rollback failed", rollbackFailure);
        }
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record InstallResult(String aggregateSha256, long bytesInstalled, int fileCount, Path backupPath) {}
}
