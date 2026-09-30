package com.foundations.cluster.persistence;

import com.foundations.cluster.transfer.RegionKey;

/**
 * Minecraft-facing integration points. The Cluster Node implementation must execute
 * these callbacks on the server thread where required by NeoForge/Minecraft.
 */
public interface NodeRegionHooks {
    /**
     * Fence new persistent writes, force-save dirty chunks/block entities/entities/POI,
     * and return the final monotonically increasing region revision.
     */
    long fenceAndFlush(RegionKey region, long expectedEpoch, long minimumRevision);

    /** Unload/invalidate stale local chunks before files are replaced. */
    void unloadAndInvalidate(RegionKey region, long incomingEpoch, long incomingRevision);

    /** Source-only rollback before authority has committed. */
    void releaseFence(RegionKey region, long sourceEpoch);

    /** Destination-only: open authoritative writes after manager CAS commit. */
    void activate(RegionKey region, long committedEpoch, long revision);

    /** Source-only: keep the old copy fenced/read-only after authority moves away. */
    default void demote(RegionKey region, long committedEpoch, long revision) {}
}
