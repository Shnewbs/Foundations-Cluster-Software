package com.foundations.cluster.transfer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

public final class RegionTransferCoordinatorSelfTest {
    private static final RegionKey REGION = new RegionKey("world", "minecraft:overworld", 0, 0);

    public static void main(String[] args) {
        pingPong100TimesPreservesNewestState();
        checksumMismatchRollsBack();
        destinationFailureRollsBack();
        staleSourceIsRejected();
        System.out.println("R7.R4 reference self-tests: PASS");
    }

    private static void pingPong100TimesPreservesNewestState() {
        FakeAuthorityStore authority = new FakeAuthorityStore(new AuthorityRecord(REGION, "worker-001", 7, 1));
        FakeWorker w1 = new FakeWorker("worker-001");
        FakeWorker w2 = new FakeWorker("worker-002");
        w1.write(REGION, "RED|chest=1");
        w1.revision = 1;
        RegionTransferCoordinator coordinator = new RegionTransferCoordinator(authority, line -> {});

        FakeWorker source = w1;
        FakeWorker dest = w2;
        for (int i = 0; i < 100; i++) {
            coordinator.transfer(REGION, source, dest);
            assertEquals(source.state.get(REGION), dest.state.get(REGION), "ping-pong state mismatch at transfer " + i);
            dest.write(REGION, "STATE-" + i + "|chest=" + (i + 2) + "|be=" + i);
            dest.revision++;
            authority.record = new AuthorityRecord(REGION, dest.workerId, authority.record.epoch(), dest.revision);
            FakeWorker tmp = source;
            source = dest;
            dest = tmp;
        }
    }

    private static void checksumMismatchRollsBack() {
        FakeAuthorityStore authority = new FakeAuthorityStore(new AuthorityRecord(REGION, "worker-001", 2, 5));
        FakeWorker source = new FakeWorker("worker-001");
        FakeWorker dest = new FakeWorker("worker-002");
        source.write(REGION, "BLUE|chest=abc");
        source.revision = 5;
        dest.corruptInstallChecksum = true;
        RegionTransferCoordinator coordinator = new RegionTransferCoordinator(authority, line -> {});
        expectFailure(() -> coordinator.transfer(REGION, source, dest), "checksum");
        assertEquals("worker-001", authority.record.ownerWorkerId(), "owner changed after checksum failure");
        assertTrue(source.fenceReleased, "source fence not released after checksum failure");
    }

    private static void destinationFailureRollsBack() {
        FakeAuthorityStore authority = new FakeAuthorityStore(new AuthorityRecord(REGION, "worker-001", 3, 8));
        FakeWorker source = new FakeWorker("worker-001");
        FakeWorker dest = new FakeWorker("worker-002");
        source.write(REGION, "GREEN|moddata=tfc-soil");
        source.revision = 8;
        dest.failInstall = true;
        RegionTransferCoordinator coordinator = new RegionTransferCoordinator(authority, line -> {});
        expectFailure(() -> coordinator.transfer(REGION, source, dest), "install");
        assertEquals("worker-001", authority.record.ownerWorkerId(), "owner changed after destination failure");
        assertTrue(source.fenceReleased, "source fence not released after destination failure");
    }

    private static void staleSourceIsRejected() {
        FakeAuthorityStore authority = new FakeAuthorityStore(new AuthorityRecord(REGION, "worker-002", 10, 20));
        FakeWorker stale = new FakeWorker("worker-001");
        FakeWorker dest = new FakeWorker("worker-003");
        RegionTransferCoordinator coordinator = new RegionTransferCoordinator(authority, line -> {});
        expectFailure(() -> coordinator.transfer(REGION, stale, dest), "authoritative");
    }

    private static final class FakeAuthorityStore implements AuthorityStore {
        private AuthorityRecord record;

        private FakeAuthorityStore(AuthorityRecord record) {
            this.record = record;
        }

        @Override
        public AuthorityRecord get(RegionKey region) {
            if (!record.region().equals(region)) throw new IllegalArgumentException("unknown region");
            return record;
        }

        @Override
        public AuthorityRecord commitTransfer(RegionKey region, String expectedSourceWorkerId, long expectedEpoch, long expectedRevision, String destinationWorkerId) {
            if (!record.region().equals(region)
                    || !record.ownerWorkerId().equals(expectedSourceWorkerId)
                    || record.epoch() != expectedEpoch
                    || record.revision() > expectedRevision) {
                throw new RegionTransferException("authority compare-and-swap failed");
            }
            record = new AuthorityRecord(region, destinationWorkerId, expectedEpoch + 1, expectedRevision);
            return record;
        }
    }

    private static final class FakeWorker implements WorkerReplicationEndpoint {
        private final String workerId;
        private final Map<RegionKey, String> state = new HashMap<>();
        private long revision;
        private boolean ready = true;
        private boolean corruptInstallChecksum;
        private boolean failInstall;
        private boolean fenceReleased;

        private FakeWorker(String workerId) {
            this.workerId = workerId;
        }

        void write(RegionKey region, String value) {
            state.put(region, value);
        }

        @Override
        public String workerId() {
            return workerId;
        }

        @Override
        public boolean isTransferReady() {
            return ready;
        }

        @Override
        public SnapshotManifest flushAndSnapshot(RegionKey region, long expectedEpoch, long minimumRevision) {
            if (revision < minimumRevision) revision = minimumRevision;
            String value = state.getOrDefault(region, "");
            return new SnapshotManifest(region, workerId, expectedEpoch, revision, sha256(value), 1024, value.contains("chest") ? 1 : 0, value.contains("moddata") ? 1 : 0);
        }

        @Override
        public void invalidateLocalRegion(RegionKey region, long incomingEpoch, long incomingRevision) {
            state.remove(region);
        }

        @Override
        public InstalledManifest pullAndInstall(WorkerReplicationEndpoint sourceEndpoint, SnapshotManifest sourceManifest) {
            if (failInstall) throw new RegionTransferException("simulated destination install failure");
            FakeWorker source = (FakeWorker) sourceEndpoint;
            String value = source.state.getOrDefault(sourceManifest.region(), "");
            state.put(sourceManifest.region(), value);
            revision = sourceManifest.revision();
            String checksum = corruptInstallChecksum ? "CORRUPT" : sha256(value);
            return new InstalledManifest(
                    sourceManifest.region(), workerId, sourceManifest.epoch(), revision, checksum,
                    1024, value.contains("chest") ? 1 : 0, value.contains("moddata") ? 1 : 0);
        }

        @Override
        public void releaseWriteFence(RegionKey region, long epoch) {
            fenceReleased = true;
        }

        @Override
        public void activateAuthority(RegionKey region, long committedEpoch, long revision) {
            this.revision = revision;
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void expectFailure(Runnable runnable, String contains) {
        try {
            runnable.run();
            throw new AssertionError("expected failure containing: " + contains);
        } catch (RegionTransferException expected) {
            if (!expected.getMessage().toLowerCase().contains(contains.toLowerCase())
                    && (expected.getCause() == null || !String.valueOf(expected.getCause().getMessage()).toLowerCase().contains(contains.toLowerCase()))) {
                throw new AssertionError("wrong failure: " + expected.getMessage());
            }
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
