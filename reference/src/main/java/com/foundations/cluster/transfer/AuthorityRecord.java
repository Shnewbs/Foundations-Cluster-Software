package com.foundations.cluster.transfer;

import java.util.Objects;

public record AuthorityRecord(RegionKey region, String ownerWorkerId, long epoch, long revision) {
    public AuthorityRecord {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(ownerWorkerId, "ownerWorkerId");
        if (epoch < 0) throw new IllegalArgumentException("epoch must be >= 0");
        if (revision < 0) throw new IllegalArgumentException("revision must be >= 0");
    }
}
