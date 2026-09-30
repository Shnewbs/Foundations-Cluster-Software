package com.foundations.cluster.persistence;

import java.util.Objects;

public record RegionFileSnapshot(
        RegionFileKind kind,
        String fileName,
        long bytes,
        String sha256,
        boolean present) {

    public RegionFileSnapshot {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(sha256, "sha256");
        if (bytes < 0) throw new IllegalArgumentException("bytes must be >= 0");
    }
}
