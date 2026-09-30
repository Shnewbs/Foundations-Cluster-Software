package com.foundations.cluster.transfer;

import java.util.Objects;

public record InstalledManifest(
        RegionKey region,
        String destinationWorkerId,
        long sourceEpoch,
        long revision,
        String checksum,
        int chunkCount,
        int blockEntityCount,
        int persistentAttachmentCount) {

    public InstalledManifest {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(destinationWorkerId, "destinationWorkerId");
        Objects.requireNonNull(checksum, "checksum");
    }
}
