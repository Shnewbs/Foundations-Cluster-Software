package com.foundations.cluster.persistence;

import com.foundations.cluster.transfer.RegionKey;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record RegionSnapshot(
        String transferId,
        RegionKey region,
        String sourceWorkerId,
        long epoch,
        long revision,
        long createdAtMillis,
        Path snapshotRoot,
        List<RegionFileSnapshot> files,
        String aggregateSha256,
        long totalBytes) {

    public RegionSnapshot {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(sourceWorkerId, "sourceWorkerId");
        Objects.requireNonNull(snapshotRoot, "snapshotRoot");
        files = List.copyOf(files);
        Objects.requireNonNull(aggregateSha256, "aggregateSha256");
        if (epoch < 0 || revision < 0 || totalBytes < 0) {
            throw new IllegalArgumentException("epoch/revision/totalBytes must be >= 0");
        }
    }
}
