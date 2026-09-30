package com.foundations.cluster.transfer;

public interface WorkerReplicationEndpoint {
    String workerId();

    /** Healthy enough to accept authoritative region ownership right now. */
    boolean isTransferReady();

    /**
     * Fence new persistent writes for this region and synchronously flush pending
     * region/chunk writes, block entities, entities/POI and persistent mod attachments.
     */
    SnapshotManifest flushAndSnapshot(RegionKey region, long expectedEpoch, long minimumRevision);

    /**
     * Drop/unload any stale local authoritative/read-replica copy before receiving
     * a region it may have owned in the past.
     */
    void invalidateLocalRegion(RegionKey region, long incomingEpoch, long incomingRevision);

    /**
     * Pull/install from source using an authenticated transport. R7.R4 keeps the
     * existing R7 relay protocol compatible while adding persistence manifests.
     */
    InstalledManifest pullAndInstall(
            WorkerReplicationEndpoint source,
            SnapshotManifest sourceManifest);

    /** Re-open source writes only when a transfer aborts before ownership commit. */
    void releaseWriteFence(RegionKey region, long epoch);

    /** Destination may begin authoritative writes only after manager CAS commit. */
    void activateAuthority(RegionKey region, long committedEpoch, long revision);

    /** Source remains non-authoritative after a successful commit. */
    default void demoteAuthority(RegionKey region, long committedEpoch, long revision) {}
}
