package com.foundations.cluster.persistence;

import com.foundations.cluster.transfer.RegionKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Small durable journal so a crash cannot silently resurrect a stale region copy. */
public final class TransferJournal {
    private final Path journalRoot;

    public TransferJournal(Path journalRoot) {
        this.journalRoot = Objects.requireNonNull(journalRoot, "journalRoot").toAbsolutePath().normalize();
    }

    public void write(
            String transferId,
            RegionKey region,
            String source,
            String destination,
            long epoch,
            long revision,
            String checksum,
            String stage) {
        String body = "transferId=" + transferId + "\n"
                + "region=" + region + "\n"
                + "source=" + source + "\n"
                + "destination=" + destination + "\n"
                + "epoch=" + epoch + "\n"
                + "revision=" + revision + "\n"
                + "checksum=" + checksum + "\n"
                + "stage=" + stage + "\n"
                + "updatedAt=" + System.currentTimeMillis() + "\n";
        Path target = journalRoot.resolve(transferId + ".journal");
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(journalRoot);
            Files.writeString(temp, body, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist transfer journal", e);
        }
    }
}
