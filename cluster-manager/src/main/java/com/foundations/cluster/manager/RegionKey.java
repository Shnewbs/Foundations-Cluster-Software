package com.foundations.cluster.manager;

record RegionKey(String worldId, String dimensionId, int regionX, int regionZ) {
    RegionKey {
        worldId = worldId == null || worldId.isBlank() ? "foundations" : worldId;
        dimensionId = dimensionId == null || dimensionId.isBlank() ? "minecraft:overworld" : dimensionId;
    }
    String id() { return worldId + "/" + dimensionId + "/r." + regionX + "." + regionZ; }
    String display() { return dimensionId + " [" + regionX + ", " + regionZ + "]"; }
}
