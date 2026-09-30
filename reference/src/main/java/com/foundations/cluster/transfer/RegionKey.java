package com.foundations.cluster.transfer;

import java.util.Objects;

public record RegionKey(String worldId, String dimensionId, int regionX, int regionZ) {
    public RegionKey {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(dimensionId, "dimensionId");
    }

    @Override
    public String toString() {
        return worldId + ":" + dimensionId + ":" + regionX + "," + regionZ;
    }
}
