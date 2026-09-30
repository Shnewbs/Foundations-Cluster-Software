package com.foundations.cluster.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Foundations Cluster Runtime 0.0.5a.R7.R4 authority + persistence handoff core.
 *
 * Critical invariants:
 *  - exactly one authoritative writer;
 *  - symmetric Wn -> Wm transfer path;
 *  - source is fenced/flushed before final snapshot;
 *  - destination unloads stale state before install;
 *  - revision/checksum/persistent counts match before ownership commit;
 *  - authority CAS commit is the point of no return;
 *  - failures before CAS release the source fence and roll back;
 *  - failures after CAS NEVER re-enable the old owner and require recovery instead.
 */
public final class RegionTransferCoordinator {
    private final AuthorityStore authorityStore;
    private final TransferLogger logger;

    public RegionTransferCoordinator(AuthorityStore authorityStore, TransferLogger logger) {
        this.authorityStore = Objects.requireNonNull(authorityStore, "authorityStore");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public TransferResult transfer(
            RegionKey region,
            WorkerReplicationEndpoint source,
            WorkerReplicationEndpoint destination) {

        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");

        if (source.workerId().equals(destination.workerId())) {
            throw new RegionTransferException("source and destination must differ");
        }
        if (!destination.isTransferReady()) {
            throw new RegionTransferException("destination is not transfer-ready: " + destination.workerId());
        }

        AuthorityRecord before = authorityStore.get(region);
        if (!before.ownerWorkerId().equals(source.workerId())) {
            throw new RegionTransferException(
                    "source is not authoritative: expected=" + before.ownerWorkerId() + " actual=" + source.workerId());
        }

        List<TransferStage> stages = new ArrayList<>();
        stages.add(TransferStage.PREPARING);
        log("REGION_TRANSFER PREPARE", region, source.workerId(), destination.workerId(), before.epoch(), before.revision(), null);

        SnapshotManifest sourceManifest;
        AuthorityRecord committed = null;
        try {
            sourceManifest = source.flushAndSnapshot(region, before.epoch(), before.revision());
            validateSourceManifest(before, source, sourceManifest);
            stages.add(TransferStage.SOURCE_FLUSHED);
            log("SOURCE_FLUSH COMPLETE", region, source.workerId(), destination.workerId(), sourceManifest.epoch(), sourceManifest.revision(), sourceManifest.checksum());

            destination.invalidateLocalRegion(region, sourceManifest.epoch(), sourceManifest.revision());
            stages.add(TransferStage.DESTINATION_INVALIDATED);
            log("DEST_INVALIDATE COMPLETE", region, source.workerId(), destination.workerId(), sourceManifest.epoch(), sourceManifest.revision(), null);

            InstalledManifest installed = destination.pullAndInstall(source, sourceManifest);
            stages.add(TransferStage.SNAPSHOT_INSTALLED);
            validateInstalledManifest(sourceManifest, destination, installed);
            stages.add(TransferStage.VERIFIED);
            log("REGION_SYNC VERIFIED", region, source.workerId(), destination.workerId(), installed.sourceEpoch(), installed.revision(), installed.checksum());

            // Point of no return. Everything above this line is safe to roll back to source.
            committed = authorityStore.commitTransfer(
                    region,
                    source.workerId(),
                    before.epoch(),
                    sourceManifest.revision(),
                    destination.workerId());
            validateCommitted(before, sourceManifest, destination, committed);
            stages.add(TransferStage.AUTHORITY_COMMITTED);
            log("AUTHORITY_CAS COMMITTED", region, source.workerId(), destination.workerId(), committed.epoch(), committed.revision(), sourceManifest.checksum());

            destination.activateAuthority(region, committed.epoch(), committed.revision());
            stages.add(TransferStage.DESTINATION_ACTIVATED);
            source.demoteAuthority(region, committed.epoch(), committed.revision());
            stages.add(TransferStage.SOURCE_DEMOTED);
            stages.add(TransferStage.COMMITTED);
            log("AUTHORITY_COMMIT COMPLETE", region, source.workerId(), destination.workerId(), committed.epoch(), committed.revision(), sourceManifest.checksum());

            return new TransferResult(
                    true,
                    region,
                    source.workerId(),
                    destination.workerId(),
                    before.epoch(),
                    committed.epoch(),
                    committed.revision(),
                    sourceManifest.checksum(),
                    List.copyOf(stages),
                    "committed");
        } catch (RuntimeException failure) {
            if (committed == null) {
                try {
                    source.releaseWriteFence(region, before.epoch());
                } catch (RuntimeException releaseFailure) {
                    failure.addSuppressed(releaseFailure);
                }
                stages.add(TransferStage.ROLLED_BACK);
                log("REGION_TRANSFER ROLLBACK", region, source.workerId(), destination.workerId(), before.epoch(), before.revision(), null);
                throw new RegionTransferException("region transfer failed before authority commit: " + failure.getMessage(), failure);
            }

            // NEVER revive source after CAS committed to destination. Doing so could create two writers.
            stages.add(TransferStage.RECOVERY_REQUIRED);
            log("REGION_TRANSFER RECOVERY_REQUIRED", region, source.workerId(), destination.workerId(), committed.epoch(), committed.revision(), null);
            throw new RegionTransferException(
                    "region authority committed to " + destination.workerId()
                            + " but post-commit activation/demotion failed; source remains fenced and recovery is required: "
                            + failure.getMessage(),
                    failure);
        }
    }

    private static void validateSourceManifest(
            AuthorityRecord before,
            WorkerReplicationEndpoint source,
            SnapshotManifest manifest) {
        if (!manifest.region().equals(before.region())) throw new RegionTransferException("source snapshot region mismatch");
        if (!manifest.sourceWorkerId().equals(source.workerId())) throw new RegionTransferException("source snapshot worker mismatch");
        if (manifest.epoch() != before.epoch()) throw new RegionTransferException("source snapshot epoch mismatch");
        if (manifest.revision() < before.revision()) throw new RegionTransferException("source snapshot revision regressed");
        if (manifest.checksum().isBlank()) throw new RegionTransferException("source snapshot checksum missing");
    }

    private static void validateInstalledManifest(
            SnapshotManifest source,
            WorkerReplicationEndpoint destination,
            InstalledManifest installed) {
        if (!installed.region().equals(source.region())) throw new RegionTransferException("destination region mismatch");
        if (!installed.destinationWorkerId().equals(destination.workerId())) throw new RegionTransferException("destination worker mismatch");
        if (installed.sourceEpoch() != source.epoch()) throw new RegionTransferException("destination source epoch mismatch");
        if (installed.revision() != source.revision()) throw new RegionTransferException("destination revision mismatch");
        if (!installed.checksum().equals(source.checksum())) throw new RegionTransferException("destination checksum mismatch");
        if (installed.chunkCount() != source.chunkCount()
                || installed.blockEntityCount() != source.blockEntityCount()
                || installed.persistentAttachmentCount() != source.persistentAttachmentCount()) {
            throw new RegionTransferException("destination persistent-state count mismatch");
        }
    }

    private static void validateCommitted(
            AuthorityRecord before,
            SnapshotManifest sourceManifest,
            WorkerReplicationEndpoint destination,
            AuthorityRecord committed) {
        if (!committed.ownerWorkerId().equals(destination.workerId())) {
            throw new RegionTransferException("authority store committed unexpected owner " + committed.ownerWorkerId());
        }
        if (committed.epoch() <= before.epoch()) throw new RegionTransferException("authority epoch did not advance");
        if (committed.revision() != sourceManifest.revision()) {
            throw new RegionTransferException("committed revision differs from verified snapshot revision");
        }
    }

    private void log(String event, RegionKey region, String source, String destination, long epoch, long revision, String checksum) {
        String line = "[Cluster] " + event
                + " region=" + region
                + " source=" + source
                + " dest=" + destination
                + " epoch=" + epoch
                + " revision=" + revision
                + (checksum == null ? "" : " checksum=" + checksum);
        logger.log(line);
    }
}
