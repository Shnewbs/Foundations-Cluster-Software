# World Replication v2.2 — player-ledger recovery continuation

Baseline: HF15 / Node R14, commit db70436731297dd9a1f666f2e24a30293f685de2.
This is a development change to the isolated reference protocol and its tests.
It is not a new playable release, does not change runtime version markers, and
**does not enable remote Survival breaking or placement**.

## Reproduced defect and change

In the HF15 reference PlayerOperationLedger, replacing the length of a complete
129-byte record with 60000 caused recovery to classify it as an interrupted
append, truncate the file to zero and return no operations. The CRC could not
help because recovery trusted the corrupted length before reaching the CRC.

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
No user world, profile, settings, gateway, Manager or worker directory is changed.

## Validation

Local Java 21 / Linux compilation and execution:

- Existing replication protocol tests: 102 checks passed.
- Existing authority result journal tests: 33 checks passed.
- New player-ledger recovery tests: 1695 checks passed, including six JVMs
  forcibly terminated by a parent process while both journal handles remain open.

The new checks exercise every truncated byte position of an appended protected
transition; every single-bit header corruption; a bit flip at each payload/CRC
byte; complete legacy and mixed histories; incomplete legacy preservation;
framing downgrade rejection; file-lock cleanup; closed/poisoned-state guards;
and independent player/authority journal recovery.

Process termination occurs after PREPARED, WORLD_EXECUTED, authority-only durable
result, WORLD_RESULT_DURABLE, PLAYER_COMMITTED and CLIENT_ACKED. The authority-only
case repairs the missing player receipt from the reopened authority journal
without appending a second result. Uncertain actions with no authoritative
receipt remain reserved. Retries preserve receipts, ordering and counters;
acknowledgement still requires the correct locally applied revision barrier.
Shutdown-hook sentinels confirm the child did not follow graceful JVM shutdown.

The new test is called by ReplicationProtocolSelfTest so the existing portable
validation entrypoint and Windows/Linux CI execute it. The full repository and
real NeoForge suites are separate CI checks, not claimed by the local subset run.

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
