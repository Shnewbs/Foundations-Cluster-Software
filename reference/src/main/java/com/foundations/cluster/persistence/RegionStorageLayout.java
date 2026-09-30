package com.foundations.cluster.persistence;

import com.foundations.cluster.transfer.RegionKey;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Resolves the Anvil file triplet for a 32x32-chunk authority region. */
public final class RegionStorageLayout {
    private final Path worldRoot;

    public RegionStorageLayout(Path worldRoot) {
        this.worldRoot = Objects.requireNonNull(worldRoot, "worldRoot").toAbsolutePath().normalize();
    }

    public Path worldRoot() {
        return worldRoot;
    }

    public Path dimensionRoot(String dimensionId) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        return switch (dimensionId) {
            case "minecraft:overworld" -> worldRoot;
            case "minecraft:the_nether" -> worldRoot.resolve("DIM-1");
            case "minecraft:the_end" -> worldRoot.resolve("DIM1");
            default -> {
                int split = dimensionId.indexOf(':');
                if (split <= 0 || split == dimensionId.length() - 1) {
                    throw new IllegalArgumentException("Invalid dimension id: " + dimensionId);
                }
                String namespace = sanitize(dimensionId.substring(0, split));
                String path = dimensionId.substring(split + 1);
                Path result = worldRoot.resolve("dimensions").resolve(namespace);
                for (String component : path.split("/")) {
                    result = result.resolve(sanitize(component));
                }
                yield result;
            }
        };
    }

    public Path file(RegionKey region, RegionFileKind kind) {
        Path base = dimensionRoot(region.dimensionId()).resolve(kind.directory());
        return base.resolve("r." + region.regionX() + "." + region.regionZ() + ".mca");
    }

    private static String sanitize(String value) {
        String lowered = value.toLowerCase(Locale.ROOT);
        if (!lowered.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Unsafe path component: " + value);
        }
        return lowered;
    }
}
