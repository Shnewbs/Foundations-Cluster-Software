# R19-HF9 — log review and restart availability fix

2026-09-28 UTC. Manager R19 / package HF9; Node R11 and Gateway R19 remain byte-identical to HF8.

## Evidence from the supplied HF8 run

The `New folder(7).zip` archive contains accumulated historical logs. This review separates the HF8 Manager startup at 13:07:22 UTC (06:07:22 Pacific) through shutdown at 13:13:24 UTC from earlier R9/R10 failures.

| Observation | Result |
| --- | --- |
| Installed versions | Manager package HF8; both workers loaded Node R11 |
| Region restoration | r.0.0 and r.0.-1 became ACTIVE; no repeated fire replay failure in the HF8 section |
| Physical transfers | handoff-00000001 and 00000002 committed; evacuation handoff-00000003 committed |
| Inventory | Five console NBT samples across both workers are identical |
| Chest | All three source/destination/return reads show exactly 17 diamonds in slot 0 |
| Generated tree | Source, destination and return probes share hash `ceda38699c46a5f07d9033d5977fd4aee02e042ef3a0e92311262e20be06c4d1`; 59 non-air blocks, 4 logs, 54 leaves |
| Graceful evacuation | worker-001 moved its player, stopped cleanly and was restarted |
| Restart problem | worker-001 registered as DRAINED at 13:10:05 UTC; WebUI requires READY/ACTIVE, so this prevents restart acceptance |
| Proxy configuration replay | Each of the three switches reached CONFIG_FINISH_ACK_AND_PLAY; SWITCH_ACCEPTED was reported in 346/96/95 ms (proxy switch stage only) |

The first tree probe reports an empty volume as expected before fixture placement. The generated-tree result proves that fixture's authority round-trip; it does not prove independent natural growth on every replica. Server-side proxy acceptance does not establish what the client displayed.

At 13:13:12 and 13:13:17 UTC, both workers were explicitly force-killed before the full cluster shutdown request. The later connection-refused/fallback messages follow those actions; no live backend remained. They are separate from the earlier committed transfers.

## Fix

Graceful stop set the worker's `draining` flag to prevent new placements. A later, separate Restart preserved that same flag as though the operator had deliberately selected Drain. The combined online-restart path had its own policy restoration, but the Stop → Restart path lost the original policy.

HF9 records the pre-stop drain policy separately from the temporary stop fence:

- Ordinary stop followed by restart returns an otherwise healthy worker to READY.
- Pre-existing manual drain and maintenance settings remain in effect.
- An operator changing drain intent during evacuation updates the saved policy while the active stop fence stays closed.
- Cancellation releases the temporary fence and restores the current operator policy.
- Repeated stop/force-stop does not replace the saved policy with the temporary `true` value.
- Restart consumes the saved policy after a successful launch. A failed launch retains it for retry.

The WebUI still requires READY/ACTIVE. Timeout messages now show the last state, process liveness, readiness, drain and maintenance flags. HF9 does not hide the issue by treating DRAINED as a successful rejoin.

## Validation executed for HF9

- Manager regressions: 134 checks (109 original Manager checks plus 25 support-archive checks).
- New lifecycle policy tests: 14 checks, including cancellation, repeated stop, manual drain/undrain and maintenance preservation.
- Real Manager HTTP lifecycle with owned Java fixture processes: 9 checks across separate stop/restart cycles, online graceful restart, manual drain, maintenance and force-stop recovery. These are real launched/stopped processes with simulated agent reports, not Minecraft gameplay tests.
- WebUI tests: 83 checks, including 5 new restart-result/timeout cases.
- Hydration HTTP contract: 13 checks; support ZIP HTTP integration: 20 checks.
- Manager compiled with JDK 21. Node R11 source/JAR and Gateway/Conduit source/binaries verified unchanged from HF8.

The previously reported HF8 NeoForge integration and browser runs are historical evidence. They were not rerun for this focused Manager fix. Windows updater execution and client-visible seamlessness remain unverified here.

## Separate open issue

At 06:08:25 Pacific, Minecraft reports `Unable to parse Stat data` for worker-001's player statistics JSON. The new ZIP contains logs, not that JSON file; its exact contents and the point of corruption are not established. Do not delete playerdata, inventory, advancements or journals to clear this warning. A future profile-integrity fix needs the affected stats file and the corresponding central `runtime/player-profiles/.../stats/` copy, with the cluster stopped. This warning did not prevent the observed inventory/chest/tree transfers.

The brief environment-lease warning at 06:10:01 is followed in the same second by worker-002 taking environment ownership; it is not proof of weather divergence.

## Installation and next check

Stop the whole cluster, apply HF9 using the folder-picker updater, restart, and verify the startup package marker is HF9. Workers remain Node R11. Enable failed-test Support ZIP capture in Diagnostics. Run Graceful Stop + Restore, then Full Acceptance. Observe an actual game-client transfer separately to assess the preconfiguration screen. Natural replica growth, complete scheduled ticks/entities/full-world migration and the remaining roadmap scope are still open.
