# R19-HF7 — Destination restoration and persistence diagnostics

September 28, 2026 UTC. Baseline: R19-HF6. Manager R19 / **Node R10** / Gateway R19 / NeoForge 21.1.250. Startup marker: `package 0.0.6a.R19-HF7`.

## What the supplied run proves

The user reports successful physical alignment from worker-002 to worker-001, followed by a successful player-inventory NBT round-trip across worker-001 and worker-002. This is progress beyond the earlier configuration rejections. The chest test fails after 19,072 ms because fresh console output does not contain `minecraft:diamond`. The exact source/destination/return stage and command response are absent. That message alone does not prove the diamonds were lost. The user also reports suspected missing tree growth, without coordinates, ownership, paired world samples, or raw worker logs. Client-visible seamlessness remains unconfirmed.

## Confirmed defects addressed

- R9 used a worker-wide restoration flag and replayed all assigned regions whenever an assignment's state changed. A RECOVERING → ACTIVE transition could replay again; acquiring one region paused mutation capture for other regions. Overlapping asynchronous responses had no per-region epoch check.
- Manager readiness depended on a recovery timer, not acknowledgement that destination blocks and BlockEntities were restored. R10 readiness now requires the same worker process and region epoch to report a completed restore. Restarted processes lose old acknowledgements. R9 protocol compatibility remains, but HF7 acceptance requires every live worker to run R10.
- Replay failures/incompatible BlockEntities could be logged and then treated as successful hydration. R10 keeps the affected region fenced and retries, with an explicit log reason. Unknown block IDs/properties fail instead of silently becoming air/defaults.
- Block replay used ordinary neighbor updates while reconstructing a snapshot. R10 suppresses neighbor-shape updates and associated drops during block/host restoration to avoid breaking plants while their supports are still being restored. This is not scheduled-tick or entity-state migration.
- A forced/immediate BlockEntity save did not remove an older coalesced save for that position. The older payload could later overwrite the newer one. Immediate/forced saves now supersede pending payloads.
- Redundant player pre-break/place writes used the player's route region, which can differ from the target block's region. Local writes now rely on the final mutation hook, keyed by the block's actual authority. Received remote block requests validate their coordinates. Complete remote border interaction remains prototype scope.
- Final block flush no longer depends on BlockEntity persistence being enabled. Replica responses are checked against current routes/ownership and do not publish their replay as authoritative changes. Deferred replica BlockEntities are cleared when assignments change. Older control responses cannot roll back a newer assignment response.

## Test Lab changes

The chest fixture is created atomically with 17 diamonds in slot 0 using `setblock ... keep`; an occupied source position is not replaced. Source, destination and return checks use Minecraft's NBT matcher for ID, slot and exact count. Fresh console errors and the failing worker, command and stage are included in failures. Successful Manager readiness is followed by a read-only Node readiness check, so a queued control update or lingering save seal cannot race fixture creation.

Existing force-load tickets are preserved. Cleanup runs on the current active owner and only removes force-load tickets the fixture acquired. Incomplete cleanup is reported. Live tests still require the existing destructive-test opt-in and confirmation.

A new **Test Generated Tree** button, also included in Full Acceptance, generates an oak in an empty 16×24×16 test volume at `0,280,0` through `15,303,15`. A permission-gated, read-only Node probe hashes all 6,144 block states and counts logs/leaves. Source, destination and return signatures must match. This exercises the configured tree feature generator and the authoritative journal/replay path. It does not simulate every sapling's random ticks, modded growth, or non-authoritative replica ticking. Occupied test space is rejected before mutation.

The read-only commands are `foundations_region_ready "<region-id>"` and `foundations_region_probe <from x y z> <to x y z>` (maximum 8,192 loaded blocks; operator level 2). Neither loads missing chunks. The old 18 administration pages remain.

## Validation performed for HF7

| Check | Result |
| --- | --- |
| Manager regression/upgrade tests | 109 assertions passed, including 20 new destination-hydration checks |
| Node per-region hydration ledger | 20 checks passed: independent regions, epoch changes, delayed/duplicate replies, release/reacquire, fresh processes |
| Real Manager HTTP contract | 13 checks passed with simulated R10 worker reports, including scoped replay and stale-process/epoch rejection |
| Web acceptance and button behavior | 24 acceptance + 18 button + 9 handoff + 22 persistence cases passed (73 total) |
| Node R10 build | Compiled against cached mapped NeoForge 21.1.250, `javac --release 21`; packaged class versions and mod metadata checked |
| Manager build | JDK 21 target; HF7 package marker and Node R10 constant checked |
| Source/updater integrity | Every payload SHA-256 and source/updater pair verified; no prior source member omitted except replacement of the old Node JAR |
| Gateway/Conduit overlay | Byte-identical to HF6; its prior 717 handshake checks are retained historical evidence, not rerun for this persistence-only patch |

The normal offline Gradle attempt stopped in cached NeoForm runtime artifact setup before compilation (`NoSuchElementException`); mapped-artifact compilation succeeded. The browser smoke test could not launch because Chromium was absent, so no new browser screenshot or browser-run success is claimed. Windows/PowerShell execution and real Minecraft worker/client acceptance were not available. The two-worker integration script has been updated for R10 readiness and exact generated-tree signatures, and syntax-checked, but **not run for HF7**. The historical R19/Node R9 seven-check integration result is not evidence that these new binaries pass live acceptance.

Reproduce: `tools/test_manager.sh`, `tools/test_node_persistence.sh`, `node tests/testlab_acceptance_test.cjs`, `node tests/testlab_buttons_test.cjs`, `node tests/testlab_handoff_test.cjs`, `node tests/testlab_persistence_test.cjs`, and `python tests/hydration_api_test.py --java <JDK21-java>`. The real-worker script additionally needs a provisioned NeoForge template whose EULA was already accepted: `tests/persistence_integration_test.py`.

## Installation and next live check

1. Disconnect the client and fully stop Manager, gateway, and all workers.
2. Extract the HF7 updater and run `APPLY_R19_UPDATE.bat`, selecting the existing Cluster folder. Keep its printed backup path.
3. Restart. Verify the HF7 package marker and **Node 0.0.6a.R10 on both workers**. The updater and startup synchronization install R10 in the template and stored workers.
4. Reconnect, then run **Test Chest / BlockEntity** and **Test Generated Tree** separately before Full Acceptance. Record any complete failure line and the corresponding worker/Manager logs.
5. Observe a physical player transfer to assess whether the client still shows reconfiguration/loading. Inventory migration passing is not that visual check.

## Limits that remain open

Growth on a physical worker that is not the region's authority can still diverge because full replica simulation/tick ownership is unfinished. This patch must not be described as complete cross-worker natural growth support. No blind forwarding of arbitrary replica block mutations has been added. Existing changes that never reached the journal cannot be reconstructed by this overlay. Full region snapshots, scheduled ticks, entities, and mod-specific simulation state remain roadmap work. A hard kill can still lose writes that have not been acknowledged; zero pending/failed telemetry is not a full-world completeness certificate. Windows advancement-file locking and all prior unrelated roadmap items remain open.
