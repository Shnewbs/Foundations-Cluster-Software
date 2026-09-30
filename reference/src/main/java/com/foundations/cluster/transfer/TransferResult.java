package com.foundations.cluster.transfer;

import java.util.List;

public record TransferResult(
        boolean committed,
        RegionKey region,
        String sourceWorkerId,
        String destinationWorkerId,
        long oldEpoch,
        long newEpoch,
        long revision,
        String checksum,
        List<TransferStage> stages,
        String message) {
}
