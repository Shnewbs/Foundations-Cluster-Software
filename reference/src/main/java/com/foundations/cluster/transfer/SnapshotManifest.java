package com.foundations.cluster.transfer;

import java.util.Objects;

public record SnapshotManifest(
        RegionKey region,
        String sourceWorkerId,
        long epoch,
        long revision,
        String checksum,
        int chunkCount,
        int blockEntityCount,
        int persistentAttachmentCount) {

    public SnapshotManifest {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(sourceWorkerId, "sourceWorkerId");
        Objects.requireNonNull(checksum, "checksum");
        if (epoch < 0 || revision < 0) throw new IllegalArgumentException("epoch/revision must be >= 0");
        if (chunkCount < 0 || blockEntityCount < 0 || persistentAttachmentCount < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
    }
}
