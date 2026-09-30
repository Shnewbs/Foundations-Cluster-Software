package com.foundations.cluster.persistence;

import com.foundations.cluster.transfer.AuthorityRecord;
import com.foundations.cluster.transfer.AuthorityStore;
import com.foundations.cluster.transfer.RegionKey;
import com.foundations.cluster.transfer.RegionTransferCoordinator;
import com.foundations.cluster.transfer.RegionTransferException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Filesystem-level regression harness for R7.R4 persistent region migration. */
public final class PersistentRegionTransferSelfTest {
    private static final RegionKey REGION = new RegionKey("foundations", "minecraft:overworld", 0, 0);

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("foundations-r7r4-persistence-");
        try {
            pingPong100Transfers(root.resolve("pingpong"));
            customDimensionLayout(root.resolve("dimensions"));
            installRollbackRestoresOldDestination(root.resolve("rollback"));
            postCommitActivationFailureNeverReenablesSource(root.resolve("postcommit"));
            System.out.println("R7.R4 persistent-region self-tests: PASS");
        } finally {
            deleteTree(root);
        }
    }

    private static void pingPong100Transfers(Path root) throws Exception {
        Path w1World = root.resolve("worker-001/world");
        Path w2World = root.resolve("worker-002/world");
        TestAuthorityStore authority = new TestAuthorityStore(new AuthorityRecord(REGION, "worker-001", 5, 1));
        TestHooks h1 = new TestHooks("worker-001", authority, 1);
        TestHooks h2 = new TestHooks("worker-002", authority, 1);
        LocalFilesystemWorkerEndpoint w1 = endpoint("worker-001", w1World, root.resolve("worker-001/runtime"), h1);
        LocalFilesystemWorkerEndpoint w2 = endpoint("worker-002", w2World, root.resolve("worker-002/runtime"), h2);
        RegionStorageLayout l1 = new RegionStorageLayout(w1World);
        RegionStorageLayout l2 = new RegionStorageLayout(w2World);

        writeRegionTriplet(l1, "A0");
        writeRegionTriplet(l2, "STALE-B0");

        RegionTransferCoordinator coordinator = new RegionTransferCoordinator(authority, line -> {});
        LocalFilesystemWorkerEndpoint source = w1;
        LocalFilesystemWorkerEndpoint dest = w2;
        RegionStorageLayout sourceLayout = l1;
        RegionStorageLayout destLayout = l2;
        TestHooks sourceHooks = h1;
        TestHooks destHooks = h2;

        for (int i = 0; i < 100; i++) {
            coordinator.transfer(REGION, source, dest);
            assertTripletsEqual(sourceLayout, destLayout, "after transfer " + i);

            String next = "STATE-" + i + "|block=" + (i % 7) + "|chest=" + (1000 + i) + "|tfc_soil=" + (2000 + i);
            writeRegionTriplet(destLayout, next);
            destHooks.revision++;
            authority.record = new AuthorityRecord(REGION, dest.workerId(), authority.record.epoch(), destHooks.revision);

            LocalFilesystemWorkerEndpoint eTmp = source;
            source = dest;
            dest = eTmp;
            RegionStorageLayout lTmp = sourceLayout;
            sourceLayout = destLayout;
            destLayout = lTmp;
            TestHooks hTmp = sourceHooks;
            sourceHooks = destHooks;
            destHooks = hTmp;
        }

        assertTrue(h1.unloadCount + h2.unloadCount >= 100, "destination invalidation did not run every transfer");
        assertTrue(h1.demoteCount + h2.demoteCount >= 100, "source demotion did not run every transfer");
    }

    private static void customDimensionLayout(Path root) {
        RegionStorageLayout layout = new RegionStorageLayout(root.resolve("world"));
        RegionKey dim = new RegionKey("foundations", "example:moon/base", -2, 3);
        Path file = layout.file(dim, RegionFileKind.REGION);
        String normalized = file.toString().replace('\\', '/');
        assertTrue(normalized.endsWith("world/dimensions/example/moon/base/region/r.-2.3.mca"), "custom dimension path incorrect: " + normalized);
    }

    private static void installRollbackRestoresOldDestination(Path root) throws Exception {
        Path sourceWorld = root.resolve("source/world");
        Path destWorld = root.resolve("dest/world");
        RegionStorageLayout source = new RegionStorageLayout(sourceWorld);
        RegionStorageLayout dest = new RegionStorageLayout(destWorld);
        writeRegionTriplet(source, "NEW-AUTHORITATIVE");
        writeRegionTriplet(dest, "OLD-DESTINATION");

        RegionSnapshotService snapshots = new RegionSnapshotService(root.resolve("stage"));
        RegionSnapshot snapshot = snapshots.capture(source, REGION, "worker-001", 3, 9);
        snapshots.verify(snapshot);

        // Corrupt a staged file after capture so install fails verification before promotion completes.
        Path corrupt = snapshot.snapshotRoot().resolve("region").resolve("r.0.0.mca");
        Files.writeString(corrupt, "CORRUPTED", StandardCharsets.UTF_8);

        TransferJournal journal = new TransferJournal(root.resolve("journal"));
        RegionInstallService installer = new RegionInstallService(root.resolve("backups"), journal);
        try {
            installer.install(snapshot, dest, "worker-002");
            throw new AssertionError("expected install failure");
        } catch (IllegalStateException expected) {
            // expected
        }
        for (RegionFileKind kind : RegionFileKind.values()) {
            String text = Files.readString(dest.file(REGION, kind), StandardCharsets.UTF_8);
            assertTrue(text.contains("OLD-DESTINATION"), "rollback did not restore destination " + kind + ": " + text);
        }
    }

    private static void postCommitActivationFailureNeverReenablesSource(Path root) throws Exception {
        Path w1World = root.resolve("worker-001/world");
        Path w2World = root.resolve("worker-002/world");
        TestAuthorityStore authority = new TestAuthorityStore(new AuthorityRecord(REGION, "worker-001", 11, 4));
        TestHooks h1 = new TestHooks("worker-001", authority, 4);
        TestHooks h2 = new TestHooks("worker-002", authority, 4);
        h2.failActivate = true;
        LocalFilesystemWorkerEndpoint w1 = endpoint("worker-001", w1World, root.resolve("worker-001/runtime"), h1);
        LocalFilesystemWorkerEndpoint w2 = endpoint("worker-002", w2World, root.resolve("worker-002/runtime"), h2);
        writeRegionTriplet(new RegionStorageLayout(w1World), "SAFE");

        RegionTransferCoordinator coordinator = new RegionTransferCoordinator(authority, line -> {});
        try {
            coordinator.transfer(REGION, w1, w2);
            throw new AssertionError("expected post-commit activation failure");
        } catch (RegionTransferException expected) {
            assertTrue(expected.getMessage().contains("recovery is required"), "wrong post-commit error: " + expected.getMessage());
        }
        assertEquals("worker-002", authority.record.ownerWorkerId(), "authority did not remain on committed destination");
        assertEquals(0, h1.releaseFenceCount, "source fence was incorrectly released after authority CAS commit");
    }

    private static LocalFilesystemWorkerEndpoint endpoint(String id, Path world, Path runtime, TestHooks hooks) {
        return new LocalFilesystemWorkerEndpoint(
                id,
                new RegionStorageLayout(world),
                hooks,
                new RegionSnapshotService(runtime.resolve("snapshots")),
                new RegionInstallService(runtime.resolve("backups"), new TransferJournal(runtime.resolve("journal"))));
    }

    private static void writeRegionTriplet(RegionStorageLayout layout, String state) throws IOException {
        for (RegionFileKind kind : RegionFileKind.values()) {
            Path file = layout.file(REGION, kind);
            Files.createDirectories(file.getParent());
            Files.writeString(file, kind + "|" + state, StandardCharsets.UTF_8);
        }
    }

    private static void assertTripletsEqual(RegionStorageLayout a, RegionStorageLayout b, String message) throws IOException {
        for (RegionFileKind kind : RegionFileKind.values()) {
            byte[] left = Files.readAllBytes(a.file(REGION, kind));
            byte[] right = Files.readAllBytes(b.file(REGION, kind));
            if (!java.util.Arrays.equals(left, right)) {
                throw new AssertionError(message + " kind=" + kind);
            }
        }
    }

    private static final class TestAuthorityStore implements AuthorityStore {
        private AuthorityRecord record;
        private TestAuthorityStore(AuthorityRecord record) { this.record = record; }
        @Override public AuthorityRecord get(RegionKey region) { return record; }
        @Override public AuthorityRecord commitTransfer(RegionKey region, String source, long epoch, long revision, String destination) {
            if (!record.region().equals(region)
                    || !record.ownerWorkerId().equals(source)
                    || record.epoch() != epoch
                    || record.revision() > revision) {
                throw new RegionTransferException("authority compare-and-swap failed");
            }
            record = new AuthorityRecord(region, destination, epoch + 1, revision);
            return record;
        }
    }

    private static final class TestHooks implements NodeRegionHooks {
        private final String workerId;
        private final TestAuthorityStore authority;
        private long revision;
        private int unloadCount;
        private int releaseFenceCount;
        private int demoteCount;
        private boolean failActivate;

        private TestHooks(String workerId, TestAuthorityStore authority, long revision) {
            this.workerId = workerId;
            this.authority = authority;
            this.revision = revision;
        }

        @Override
        public long fenceAndFlush(RegionKey region, long expectedEpoch, long minimumRevision) {
            AuthorityRecord now = authority.record;
            if (!Objects.equals(now.ownerWorkerId(), workerId) || now.epoch() != expectedEpoch) {
                throw new RegionTransferException("source no longer authoritative");
            }
            revision = Math.max(revision, minimumRevision);
            return revision;
        }

        @Override public void unloadAndInvalidate(RegionKey region, long incomingEpoch, long incomingRevision) { unloadCount++; }
        @Override public void releaseFence(RegionKey region, long sourceEpoch) { releaseFenceCount++; }
        @Override public void activate(RegionKey region, long committedEpoch, long revision) {
            if (failActivate) throw new RegionTransferException("simulated destination activation failure");
            this.revision = revision;
        }
        @Override public void demote(RegionKey region, long committedEpoch, long revision) { demoteCount++; }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException e) { throw new RuntimeException(e); }
            });
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
    }
    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
