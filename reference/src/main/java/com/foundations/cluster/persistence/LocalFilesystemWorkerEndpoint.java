package com.foundations.cluster.persistence;

import com.foundations.cluster.transfer.InstalledManifest;
import com.foundations.cluster.transfer.RegionKey;
import com.foundations.cluster.transfer.SnapshotManifest;
import com.foundations.cluster.transfer.WorkerReplicationEndpoint;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference local-cluster endpoint for workers whose directories are visible to the manager.
 * Production remote-machine deployments should replace the direct snapshot lookup with the
 * existing authenticated R7 relay transport, while preserving the same manifest rules.
 */
public final class LocalFilesystemWorkerEndpoint implements WorkerReplicationEndpoint {
    private final String workerId;
    private final RegionStorageLayout layout;
    private final NodeRegionHooks hooks;
    private final RegionSnapshotService snapshots;
    private final RegionInstallService installer;
    private final Map<String, RegionSnapshot> exported = new ConcurrentHashMap<>();
    private volatile boolean transferReady = true;

    public LocalFilesystemWorkerEndpoint(
            String workerId,
            RegionStorageLayout layout,
            NodeRegionHooks hooks,
            RegionSnapshotService snapshots,
            RegionInstallService installer) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.installer = Objects.requireNonNull(installer, "installer");
    }

    @Override public String workerId() { return workerId; }
    @Override public boolean isTransferReady() { return transferReady; }
    public void setTransferReady(boolean transferReady) { this.transferReady = transferReady; }

    @Override
    public SnapshotManifest flushAndSnapshot(RegionKey region, long expectedEpoch, long minimumRevision) {
        long revision = hooks.fenceAndFlush(region, expectedEpoch, minimumRevision);
        if (revision < minimumRevision) {
            throw new IllegalStateException("Node returned regressed region revision");
        }
        RegionSnapshot snapshot = snapshots.capture(layout, region, workerId, expectedEpoch, revision);
        snapshots.verify(snapshot);
        exported.put(snapshot.aggregateSha256(), snapshot);
        return new SnapshotManifest(
                region,
                workerId,
                expectedEpoch,
                revision,
                snapshot.aggregateSha256(),
                0,
                0,
                snapshot.files().size());
    }

    @Override
    public void invalidateLocalRegion(RegionKey region, long incomingEpoch, long incomingRevision) {
        hooks.unloadAndInvalidate(region, incomingEpoch, incomingRevision);
    }

    @Override
    public InstalledManifest pullAndInstall(WorkerReplicationEndpoint source, SnapshotManifest sourceManifest) {
        if (!(source instanceof LocalFilesystemWorkerEndpoint localSource)) {
            throw new IllegalStateException("Reference local endpoint requires a local filesystem source");
        }
        RegionSnapshot snapshot = localSource.exported.get(sourceManifest.checksum());
        if (snapshot == null) {
            throw new IllegalStateException("Source snapshot is no longer exported: " + sourceManifest.checksum());
        }
        snapshots.verify(snapshot);
        RegionInstallService.InstallResult installed = installer.install(snapshot, layout, workerId);
        return new InstalledManifest(
                snapshot.region(),
                workerId,
                snapshot.epoch(),
                snapshot.revision(),
                installed.aggregateSha256(),
                0,
                0,
                snapshot.files().size());
    }

    @Override
    public void releaseWriteFence(RegionKey region, long epoch) {
        hooks.releaseFence(region, epoch);
    }

    @Override
    public void activateAuthority(RegionKey region, long committedEpoch, long revision) {
        hooks.activate(region, committedEpoch, revision);
    }

    @Override
    public void demoteAuthority(RegionKey region, long committedEpoch, long revision) {
        hooks.demote(region, committedEpoch, revision);
    }
}
