# Foundations Cluster HF11 / Node R13 — natural-growth fix and validation

Date: 2026-09-28. Target: Minecraft 1.21.1 / NeoForge 21.1.250; Manager and Node Java 21.

## What was reproduced

This is a simulation/capture fix, not another dashboard version fix. The starting code was the supplied HF10 source with the complete Node R12 build definition, HF10-NODEFIX1 Manager repair, and HF10-UIFIX1 browser checks applied. The current installer and runtime version repairs are retained.

In the R12 source, `applyAuthorityChunkRequest` obtained a chunk and encoded its snapshot but did not give it a ticking ticket. The generic `ChunkMutationMixin` existed; the authoritative journal deliberately did not accept a replica's ordinary local block mutations. R12 also did not fence the native chunk random-tick pass on replicas.

Against the actual bundled NeoForge 21.1.250 implementation of `ServerChunkCache.tickChunks`, this left a gap: snapshot loading alone did not pass the native nearby-player/forced-ticking eligibility gate on a playerless authority. A tick-eligible replica could grow a different tree locally, while the authority retained its sapling and never journaled that tree.

This was reproduced on two real headless NeoForge workers running R12. A stage-zero oak sapling was planted and mirrored. Native random ticks grew a tree on the replica while the authority remained a sapling; repeated snapshot requests did not make the playerless authority grow it. The baseline journal contained planting changes, not the generated tree.

## Implemented changes

* `GrowthTickMixin`: gates the native `ServerLevel.tickChunk` pass for managed workers. Only the current ACTIVE, hydrated, unsealed authority with a live lease and recently accepted Manager control response runs that pass. Standalone operation remains unchanged. This pass includes native random ticks and weather-related chunk behavior.
* `RemoteTickLeases`: bounded, monotonic, server-thread-owned view-interest leases. Accepted authority chunk requests retain NeoForge transient forced-ticking tickets so the authority runs its normal native tick pass without needing a local player. This does not call sapling growth manually or add a second random-tick loop.
* Tickets are renewed by real replica view requests and released on expiry, authority/epoch/control loss, sealing or shutdown. They are not written to the world's permanent forced-chunk list.
* Existing authoritative block capture, durable journal, mutation invalidations and replica snapshots are reused. Gateway/Conduit and the physical handoff implementation are unchanged.
* Manager launches/restarts export the bounded growth settings and require Node R13 consistently. The browser retains UIFIX1's Manager-derived version checks.
* `foundations_growth_status <x> <y> <z>` provides permission-gated, read-only evidence. It does not grow blocks, create tickets or load the target chunk.

Key source files:

- `minecraft-agent/src/main/java/com/foundations/cluster/node/FoundationsClusterNode.java`
- `minecraft-agent/src/main/java/com/foundations/cluster/node/RemoteTickLeases.java`
- `minecraft-agent/src/main/java/com/foundations/cluster/node/mixin/GrowthTickMixin.java`
- `minecraft-agent/src/main/java/com/foundations/cluster/node/PersistenceProbe.java`
- `cluster-manager/src/main/java/com/foundations/cluster/manager/ClusterVersion.java`
- `cluster-manager/src/main/java/com/foundations/cluster/manager/WorkerManager.java`

## Executed validation

| Check | Result | What it establishes |
|---|---|---|
| Full Manager Java 21 compilation | PASS | Compiled production Manager JAR, not a version-label overlay. |
| Full Node compilation against actual mapped NeoForge 21.1.250 and runtime libraries | PASS | Compiled all Node Java source/resources; actual servers loaded the R13 mixin. One existing deprecated-API compiler note remains. |
| R12 natural-growth reproduction | 5 checks PASS | Reproduced replica-only growth and lack of authority growth from snapshot loads. |
| R13 natural-growth two-worker test | 10 checks PASS | Native authority growth, replica suppression, durable logs/leaves, zero pending/failed writes at acceptance, identical 6,144 sampled block states, ticket expiry, authority A–B–A replay. |
| Retained real-worker persistence/environment integration | 9 checks PASS | Shared time/weather; saved fire hydration; exact 17-diamond chest; generated-oak 6,144-state comparison; real creeper damage; hard-kill replay; environment failover and Manager restart. |
| Remote tick lease unit tests | 20 checks PASS | Bounds, renewal, expiry, ownership/epoch invalidation, cleanup and monotonic-clock wrap. |
| Node startup/deployment fixtures | 33 checks PASS | R13 version parity, artifact recognition, malformed/stale artifacts and startup/build retry handling. Not Minecraft gameplay. |
| Retained Manager tests | 148 checks PASS | 27 R16, 9 R17, 29 R18, 9 R19 save, 15 Node upgrade, 20 hydration, 25 support, 14 restart. |
| Retained WebUI tests | 110 checks PASS | Dynamic required version; acceptance/persistence/handoff/restart/support/button fixtures. Not a browser-connected live client transfer. |
| Actual Java installer transactions | 70 checks PASS | Configured paths with spaces; template and two existing workers; correct compiled bytes; metadata/hash rejection; rerun; completed and injected-failure rollback; running-port rejection; preserved world/journal/security/config/unrelated mod fixtures. |

### Natural-growth test method and limits

Two actual NeoForge workers ran in disposable local worlds with a real Manager. A synthetic Manager session and authenticated view requests placed the anchor and authority on different workers; no Minecraft client joined. A tiny test-only mod supplied native tick eligibility to the replica, reproducing the nearby-player condition without forging growth. The tree itself grew through ordinary native random ticks: no `/place feature`, no bonemeal and no direct call to a sapling tick/growth method.

The fixture temporarily used `randomTickSpeed=4096` to avoid waiting for probabilistic growth and then froze random ticks for comparison. This setting was confined to disposable test worlds. **Do not use that value in your production world.** The shipped updater does not change gamerules. Native growth still needs suitable light/space, a nonzero random-tick speed and an active/tick-eligible area; default speed does not imply instant growth.

The naturally grown tree matched all 6,144 sampled block states on its replica without moving authority first. It then survived an acknowledged authority transfer A → B → A. That is **not** a test of physical client backend handoff, absence of a loading screen or zero movement stall.

The regression suite's `/place feature` oak is separate from the natural-sapling test. Successful generated-feature persistence is not used as a substitute for natural-growth evidence.

### Snapshot from the R13 read-only diagnostic

The live test recorded `node=0.0.6a.R13`, `role=AUTHORITY`, `authorityReady=true`, `tickTicket=true`, `ticketChunks=1`, `chunkTicks=199`, `chunkCaptured=202`, `pendingWrites=0`, `failedWrites=0`, and the inspected block `minecraft:oak_log`. Captured mutations include intermediate/repeated changes, not a distinct-block count. `randomTickSpeed=0` in that final sample reflects the test freezing random ticks after growth for comparison.

## Installation on the existing HF10 deployment

1. Stop Manager, Gateway, Desktop launcher and every worker. Finish any running acceptance test first.
2. Extract the updater anywhere and run `UPDATE_HF10_TO_HF11.bat`. Choose the existing Cluster root containing `config`, `cluster-manager` and `minecraft-agent`.
3. The updater verifies bundled SHA-256 values and embedded versions, installs compiled Node R13 in `minecraft-agent/build/libs`, the configured template and every existing `worker-*` mod folder, and installs the compiled HF11 Manager plus matching source. It keeps UIFIX1 browser checks.
4. Start Cluster normally, hard-refresh the dashboard and verify the fresh startup versions below. Existing workers must log R13, not just the template.

No Gradle build, worker-directory deletion, worker restore prompt or regeneration is required. Your configured template/worker paths are honored. The updater refuses unsupported baselines or an active deployment, journals backups before mutation, and rolls back changed files on failure. It does not copy personal config, security, runtime state, worlds or Gateway/Conduit files from the build environment.

Expected versions:

```text
Startup package       0.0.6a.R19-HF11
Manager runtime/API   0.0.6a.R19  (protocol version intentionally retained)
Manager required Node 0.0.6a.R13
Every live worker     0.0.6a.R13
Gateway / Conduit     unchanged R19 artifacts
```

`Worker mod deployments updated` counts existing worker directories. With the user's existing worker-003 and worker-004 under the configured path it should report 2. A count of zero is not proof of deployment to live workers; inspect the configured path and keep all backups. Do not delete worlds to correct a version issue.

`CHECK_HF11.bat` validates prerequisites without installing. `ROLLBACK_HF11.bat`, run only with the entire cluster stopped, asks for the backup directory containing `rollback.properties`. Rollback restores changed deployment files, not gameplay world history. Retain `.foundations_update_backups` and any original worker backups.

## Live acceptance and diagnostics

Use a fresh naturally grown sapling in a backed-up test area, with the player on a different physical worker from the area's authority. Observe growth, journal acknowledgement and continued tree visibility before and after a physical A → B → A transfer. Then repeat Safe/Full Acceptance and retain any Support ZIP generated by a failure. Do not treat a Node READY badge as proof of gameplay continuity.

From the appropriate worker console run, replacing the coordinates:

```text
foundations_growth_status 40 283 40
```

In game, an administrator can use `/foundations_growth_status <x> <y> <z>`. Inspect both workers through their consoles when needed. `chunkTicks` is native chunk tick passes, not successful sapling growth attempts; `chunkCaptured` includes all captured mutations in the loaded chunk. `suppressedTicks` is expected on a replica. A remote authority needs recent real view requests for `tickTicket=true`; a locally occupied authority can tick normally without a remote ticket. Counters are diagnostics, reset on process restart and per-chunk counters clear when a chunk unloads.

## Configuration and bounded scope

Optional `config/cluster.properties` entries, applied on worker restart:

```properties
dataplane.authorityRandomTicks.enabled=true
dataplane.authorityRandomTicks.ttlMillis=20000
dataplane.authorityRandomTicks.maxChunks=128
```

Defaults work without editing existing config. TTL is clamped to 5–60 seconds and the per-worker ticket budget to 1–1,024 chunks. Accepted Manager control must be fresher than eight seconds and the actual region lease must still be valid. Disabling this feature restores the previous random-tick behavior, including its known divergence risk; it is not a data-repair switch.

Tickets follow chunks requested by the existing replica-view protocol, not all chunks in every owned region. With existing default view settings that is a nearby 3×3 chunk request area limited to the session's region, with the existing bounded vertical snapshot window. This is not full vanilla-distance remote simulation or unlimited offline growth. The limit bounds ticket count, not a guarantee of a particular CPU cost for every modpack.

Full cross-region feature generation/transaction coordination, scheduled-tick queue migration, entity ownership, arbitrary mod-specific tick systems and complete world snapshots remain separate work. This update was tested for an interior vanilla oak, not every tree species, crop, mod or region-boundary condition. It does not recover tree mutations that were never journaled by an older build. Existing restoration lag/BlockEntity warnings are not claimed solved by this growth patch.

## Evidence and artifact identity

Compiled Manager SHA-256: `a660cdc2c9a202dc77dfd112f0841fed80506704fedd758ae87c8f44a4c7b5a4`

Compiled Node R13 SHA-256: `fedf7debff759588e5e0a75d2e9052b40e08c7b3205e341038ed0650f849ae8a`

The updater's `payload.sha256` binds the source and binaries actually installed. Validation logs/results in `validation/` come from the executed tests, not invented expected output. Installer execution was tested on Linux with Java 21; the Windows BAT/PowerShell launcher was inspected but not executed here. Real connected-client gameplay and the user's complete modpack still require live acceptance.
