# World Replication v2.2 — player-ledger recovery continuation

Baseline: HF15 / Node R14, commit db70436731297dd9a1f666f2e24a30293f685de2.
This development increment hardens the isolated reference protocol and fixes a
Manager support-archive status race discovered during its Windows CI run.
It is not a new playable release, does not change runtime version markers, and
**does not enable remote Survival breaking or placement**. Published HF15 assets
are unchanged; the support-archive correction is a source change for a subsequent
build, not an update to an already-installed Manager.

## Reproduced defect and change

In the HF15 reference PlayerOperationLedger, replacing the length of a complete
129-byte record with 60000 caused recovery to classify it as an interrupted
append, truncate the file to zero and return no operations. The CRC could not
help because recovery trusted the corrupted length before reaching the CRC.
This was reproduced using the exact HF15 source blob
c6cce11ad16059d3f8c4b6dd8900f109f515efda. The patched reader rejects the identical
corrupt legacy fixture while preserving all 129 original bytes.

New appends use FCP2 framing: magic, bounded payload length, complementary
length, the unchanged schema-1 payload and CRC32C. A complete damaged header or
payload fails closed. A recognized incomplete protected frame can be removed
without changing the verified durable prefix. All read APIs and even idempotent
write/acknowledgement calls reject a closed or failed ledger. Closing is
idempotent; failed constructor cleanup retains the original error and releases
the file/channel lock.

## Compatibility and data preservation

Complete legacy records are read in place. A valid legacy prefix can be followed
by protected records; no existing bytes are migrated, replaced or compacted.
Legacy framing after protected framing is rejected. Incomplete legacy records
and unrecognized short tails fail closed with the original file unchanged:
without protected length metadata, an interrupted append cannot reliably be
distinguished from length corruption. This deliberately replaces the older
unsafe automatic truncation behavior.

Older readers cannot read newly appended FCP2 records. Keep original fixture
backups when comparing old and new reference builds; do not downgrade the reader
on a file that has received new-format appends. This format is used by the
isolated model, not by deployed Minecraft player saves or runtime journals.
No user world, profile, settings, gateway, installed Manager or worker directory
is modified by this development work.

## Support-archive race found by Windows CI

During asynchronous retention, statusJson previously enumerated archive paths,
then performed separate size/time reads. Retention could remove an enumerated
ZIP before these reads, causing NoSuchFileException and failing the status poll.
The first Windows run also exposed a new recovery-test portability issue:
reading bytes through a second handle while an exclusive journal lock is held.
Those fixture inspections now occur after closing the owning handle; runtime
exclusive locks were not weakened.

Support archive enumeration now captures immutable no-follow file attributes
once per candidate, sorts the captured values and renders JSON from them without
re-reading the filesystem. An entry that disappeared during enumeration is
omitted; other I/O errors are not swallowed. Naming rules, unrelated-file
protection, symlink exclusion and existing retention behavior remain enforced.
New deterministic tests remove a listed file before metadata inspection and a
second file after capture, then verify stable size/time/order, immutable results,
and directory/unrelated-file/dangling-symlink exclusion.

## Validation

Local Java 21 / Linux compilation and execution:

- Existing replication protocol tests: 102 checks passed.
- Existing authority result journal tests: 33 checks passed.
- New player-ledger recovery tests: 1695 checks passed, including six JVMs
  forcibly terminated by a parent process while both journal handles remain open.
- Support-archive tests: 33 checks passed (25 existing plus 8 new). This local
  subset compiled the modified support class and test against the released HF15
  Manager and its existing dependency; the full source build is a separate CI gate.

Total local subset: 1863 checks passed. The Windows-safe fixture changes were
included in the repeated local run. Current Windows/Linux full-build results
must be read from the associated PR checks, not inferred from this local subset.

The recovery checks exercise every truncated byte position of an appended
protected transition; every single-bit header corruption; a bit flip at each
payload/CRC byte; complete legacy and mixed histories; incomplete legacy
preservation; framing downgrade rejection; file-lock cleanup; closed/poisoned
state guards; and independent player/authority journal recovery.

Process termination occurs after PREPARED, WORLD_EXECUTED, authority-only durable
result, WORLD_RESULT_DURABLE, PLAYER_COMMITTED and CLIENT_ACKED. The authority-only
case repairs the missing player receipt from the reopened authority journal
without appending a second result. Uncertain actions with no authoritative
receipt remain reserved. Retries preserve receipts, ordering and counters;
acknowledgement still requires the correct locally applied revision barrier.
Shutdown-hook sentinels confirm the child did not follow graceful JVM shutdown.

The recovery test is called by ReplicationProtocolSelfTest so the existing
portable validation entrypoint and Windows/Linux CI execute it. The support
fixtures are in the existing SupportArchivesSelfTest entrypoint. No CI tests are
skipped or relaxed to accommodate either fix.

## Limits / next gates

This is journal-process recovery evidence, **not** sudden power-loss, physical
storage fault, actual player inventory, live Minecraft worker, chunk/entity
recovery or multi-worker seam certification. The poisoned-state guard fixture
sets the internal failure flag explicitly; it is not a simulated hardware fault.
The tests do not execute or replay a Minecraft block break or materialize drops.

The remaining HF15 gates still apply: fail-closed chunk save-image validation,
entity output reconciliation including saved-then-consumed drops, coherent
checkpoints, actual scheduled-tick/mod-timer skew, production action-player and
presence selection, anchor-owned inventory revisions, and V0-CONTROL/V0-SEAM.
See WORLD_REPLICATION_V2_2_SPIKE_A_C_2026-10-09.md.
