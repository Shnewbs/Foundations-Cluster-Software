# R17 transfer evidence and next experiment

Date: 2026-09-23. This record analyzes the supplied Windows log capture; no new live cluster test was run here.

## What the logs establish

| Evidence | Meaning |
|---|---|
| Gateway startup at local 02:58:40 reports `seamless-server-switches = false` | This gateway process used normal physical reconfiguration. |
| Audit at 10:03:00Z records a change to `gateway.seamless.enabled`; no subsequent restart appears | Saving configuration did not demonstrate that the running proxy applied it. The audit does not record the new value. |
| Handoffs 1, 2 and 3 commit at 10:02:03Z, 10:03:10Z and 10:03:16Z | Real backend movement succeeds in both directions. Gateway switch acceptance took 929, 671 and 597 ms; these are stage timings, not proof of seamlessness or total handoff duration. |
| No `FoundationsConfigTrace` messages appear | There is no experimental configuration-handshake trace to diagnose in this capture. |
| Manager profile capture warns at 10:02:02.334Z | One live profile capture failed after a successful final checkpoint; the short warning does not establish why the file was unavailable. |
| Worker-001 advancement save fails at local 03:03:16 with a Windows sharing violation | A real save error remains. A nearby successful Manager capture does not prove that the failed advancement update was preserved. |

Manager/audit timestamps use UTC. Gateway/worker logs use local time, seven hours behind UTC in this capture.

## Next controlled test

1. In **Diagnostics → Physical Switch Policy**, enable experimental seamless switching and save. Test on a disposable world/player because this capture has an unresolved save failure.
2. Fully stop and restart the cluster so gateway setup applies the policy. In the new `logs/velocity.log` startup, confirm `seamless-server-switches = true`. A saved checkbox alone is insufficient.
3. Wait for two healthy, seed/fingerprint-matched workers. Reconnect the client. After installing this WebUI correction, hard-refresh the browser and run Safe Suite; a pass establishes readiness only.
4. Use Test Physical Migration for one same-dimension worker A→B transfer and then B→A. Record whether there is a loading/reconfiguration screen, a disconnect, a freeze or position snap, and whether inventory/health/XP/advancements persist. Avoid the broader Full Acceptance routine for this first trace because it also changes region authority and restarts workers.
5. Collect the new `logs/velocity.log`, `logs/cluster-manager.log`, `logs/workers/worker-001.log`, `logs/workers/worker-002.log` and client `latest.log`. Keep timestamps spanning startup and the entire transfer. The diagnostic summary export deliberately excludes raw logs.

If the transfer stalls, inspect `FoundationsConfigTrace` for configuration channels, unanswered payloads and the last task. If it completes with a visible transition, compare the active policy and packet path with the client observation before changing the bridge. Do not treat Gateway R16's constant `physical-traced` heartbeat label as the live seamless-policy value.

## Next runtime correctness work

`FoundationsClusterNode.playerLoggedOut` flushes progress and sends player-state asynchronously. The Manager's player-state endpoint then captures live profile files using `Files.copy`. Those reads can overlap further Minecraft logout writes. The log timing is consistent with that race; it does not identify the process holding the Windows file lock.

The next persistence change should give the worker responsibility for a completed, immutable profile snapshot, identify its generation, acknowledge durable capture before destination restore, and reject late source updates after ownership changes. Include advancements, stats, player NBT and configured mod profile files. Failed save/capture must not silently become successful state-preservation acceptance. Reproduce and verify the fix on Windows; adding a retry only to Manager reads would not resolve a worker save that already failed.

This Test Lab hotfix changes the WebUI, targeted tests and documentation. Manager R17, Node R7 and Gateway R16 binaries remain unchanged. Seamless negotiation and the profile-file race are not claimed fixed.
