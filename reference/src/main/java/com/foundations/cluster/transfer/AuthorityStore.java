package com.foundations.cluster.transfer;

public interface AuthorityStore {
    AuthorityRecord get(RegionKey region);

    /**
     * Atomic compare-and-swap ownership commit.
     * Must fail if owner/epoch/revision changed after PREPARING.
     */
    AuthorityRecord commitTransfer(
            RegionKey region,
            String expectedSourceWorkerId,
            long expectedEpoch,
            long expectedRevision,
            String destinationWorkerId);
}
