package com.foundations.cluster.manager;

record LayerCellKey(String worldId, String dimensionId, int cellX, int cellZ) {
    LayerCellKey {
        worldId = normalize(worldId, "foundations");
        dimensionId = normalize(dimensionId, "minecraft:overworld");
    }

    String groupId() { return worldId + "|" + dimensionId + "|" + cellX + "|" + cellZ; }
    String display() { return dimensionId + " [" + cellX + ", " + cellZ + "]"; }

    private static String normalize(String value, String def) {
        String v = value == null ? "" : value.trim();
        if (v.isBlank()) return def;
        if (v.length() > 128) throw new IllegalArgumentException("World/dimension identifier is too long.");
        return v;
    }
}
