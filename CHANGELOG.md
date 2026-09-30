# 0.0.6a.R19-HF11 / Node R13 — 2026-09-28

- Reproduced replica-only natural tree growth in R12 on actual NeoForge workers.
- Add bounded expiring native ticking tickets for requested authority chunks.
- Fence native random/weather chunk ticks on replicas or stale/unready authorities.
- Add read-only growth diagnostics; retain durable capture and replica invalidations.
- Include compiled Manager/Node, retain startup and dynamic WebUI version fixes.
- Preserve Gateway/Conduit, world/journal/config/security and existing worker folders.
- See docs/R19_HF11_NATURAL_GROWTH_VALIDATION.md for executed tests and remaining scope.

---

## 0.0.6a.R19-HF9 — 2026-09-28

- Fix separate graceful Stop → Restart preserving a temporary drain fence and returning DRAINED.
- Keep manual drain and maintenance intent; cancellation and repeated stops preserve the correct policy.
- Show actual state/liveness/drain/maintenance in restart-test timeout errors.
- Add policy, real managed-process HTTP lifecycle and WebUI regression tests.
- Keep Node R11 and Gateway/Conduit byte-identical to HF8; no persistence/proxy changes.
- Record supplied HF8 inventory/chest/generated-tree transfer evidence and the separate unresolved stats-JSON warning.

## 0.0.6a.R19-HF8 / Node R11 — 2026-09-28

- Diagnostics support ZIPs: manual download, opt-in schedule and failed Test Lab capture, bounded recent logs, merged journal snapshots, checksums, retention and credential exclusions.
- Offline private backups include logs and correct package metadata.
- Snapshot ingestion suppresses placement/removal gameplay callbacks, retains lighting/heightmap/BlockEntity bookkeeping, and resumes intrinsic fire ticks. Replay errors show expected/actual states and revision; retries are throttled.
- Journal capture reads the final world state after nested block mutations.
- Chunk requests/snapshots must match their claimed authority region. Snapshot BlockEntities must stay within their chunk and vertical range.
- Test fixture now waits for Node readiness instead of a fixed delay before planting its generated tree.
- Manager/Gateway stay R19; working Conduit bridge unchanged. See HF8 validation for evidence and remaining limits.

# 0.0.6a.R19-HF7 — Restore acknowledgement and chest/tree diagnostics

- Ship Node R10 in Manager selection, built metadata, template/worker updater, and build scripts.
- Require matching R10 worker-process/region-epoch restoration reports before Manager marks regions ACTIVE. Keep old-node protocol compatibility; require R10 for HF7 acceptance.
- Scope replay to newly acquired epochs, serialize world application, reject stale control/replay responses, and keep failed restorations fenced. Preserve plant supports during snapshot reconstruction.
- Make forced/immediate BlockEntity saves supersede older coalesced payloads; remove stale player-region pre-event writes; validate remote block coordinates and replica response ownership.
- Check chest slot/ID/count exactly, report fresh worker errors with source/destination/return stage, preserve existing force-load tickets, and avoid replacing occupied fixture space.
- Add bounded read-only Node readiness/block probes and a generated-oak authority round-trip test with exact 6144-block signatures.
- Validation: Manager 109, Node ledger 20, real Manager HTTP 13, WebUI test-flow 73 checks pass. Java 21 builds and package integrity checked. Gateway bytes unchanged from HF6.
- User reports physical/inventory migration PASS. No HF7 live Minecraft, Windows, or browser execution success claimed. Full non-authoritative growth simulation remains open.

# 0.0.6a.R19-HF6 — NeoForge declaration equality

- Reproduce HF5's known_registry_data_maps rejection with equivalent declarations in different orders.
- Match data maps by registry and map ID; retain every mandatory flag and empty declaration.
- Audit and correct enum-class ordering and feature-flag set ordering. Keep entire enum entries, including member order, exact.
- Add bounded parsing, malformed/duplicate rejection and concrete mismatch diagnostics; preserve captured replies and packet ownership.
- Ship and require the new declaration helper. All 717 handshake checks pass, including 366 new checks, also from the patched core; WebUI 49 and Manager 89 pass.
- Package marker HF6, components R19/R9/R19. No live-client or Windows execution success claimed; prior persistence follow-ups remain open.

# 0.0.6a.R19-HF5 — tag map comparison and differences

- HF4 live trace verifies all 30 frozen-registry snapshots, then rejects TagsUpdatePacket.
- Compare registry/tag maps by name, preserving exact member IDs, multiplicity and sequence.
- Reject malformed/duplicate data and missing tags; report the first concrete difference without dumping payloads.
- Require and ship the tag helper in the Conduit overlay; preserve normal packet forwarding on capture failure.
- Reproduce the order-only rejection against HF4. All 351 handshake checks pass, including 222 tag checks, also loaded from the patched core. WebUI 49 and Manager 89 pass.
- Startup package HF5; components stay R19/R9/R19. No live-client or Windows execution success is claimed. Other persistence follow-ups remain open.

# 0.0.6a.R19-HF4 — frozen-registry equality

- Compare manifests as complete unique name sets, and match snapshot packets by registry name.
- Keep numeric IDs, aliases and every snapshot byte exact; reject missing, extra, duplicate and malformed registry traffic, including omissions before acknowledgement.
- Show missing/added registry names or registry-specific lengths/fingerprints for true mismatches.
- Reproduce the HF3 order-only rejection; 48 new registry checks, 81 retained handshake checks, 49 WebUI cases and 89 Manager assertions pass.
- Retain HF3 pong and fast handoff errors. Actual live payload equality and client-visible seamlessness still require acceptance; persistence follow-ups remain open.

# 0.0.6a.R19-HF3 — answer the NeoForge configuration ping

- Add the missing CONFIG ping/pong response; retain transcript guards and the HF1 registry fix.
- Reproduce HF2 ignoring ping zero; 32 new wire/dispatch checks plus 49 retained handshake checks pass, including from the patched Conduit JAR.
- Show exact handoff ABORTED/FAILED reasons immediately in Test Lab; include last state in actual timeouts. Nine new WebUI handoff cases pass.
- Rebuild Manager for the HF3 package marker; Node R9 and Gateway plugin R19 remain unchanged. All 89 Manager checks and 40 previous WebUI cases pass.
- This repairs a confirmed stalled-handshake defect; full live client seamlessness still needs acceptance. Wrong-region writes and advancement file locking remain separate open fixes.

# 0.0.6a.R19-HF2 — preserve Node R9 through startup

- Correct Manager NODE_VERSION from R8 to R9, fixing startup downgrade from a retained build cache.
- Select the expected Node during fresh/stored worker synchronization and fail safely if it is missing.
- Require R9 telemetry for Durable Save Health; include the HF2 package marker in startup logs.
- Add a 15-check upgrade lifecycle test, reproduced failing against HF1; 89 Manager checks and 40 WebUI fixture cases pass.
- Retain HF1 registry capture fix. Separate six-second gateway rejection needs detailed proxy logs.

# 0.0.6a.R19-HF1 — registry packet capture correction

- Fix an R19 observer regression that consumed registry_data before forwarding it to the client, causing a registry decode disconnect.
- Copy registry buffers without advancing their indices; preserve real login when capture encoding is unsupported.
- Add 29 packet-integrity checks using actual Conduit packet objects; all 20 existing handshake checks pass.
- Manager R19, Node R9 and Gateway R19 JARs are unchanged. Live seamless acceptance remains open.

# 0.0.6a.R19 — terrain durability and guarded handoff

- Journal authoritative chunk mutations, including creeper damage, with bounded block batches and visible durable-write status.
- Require sealed, durable source saves before planned region ownership changes; reject stale worker instances and preserve ownership on failure.
- Capture actual client login replies and validate destination configuration before experimental NeoForge handshake replay. Live seamless acceptance remains required.
- Add save-health diagnostics and alerts, support summaries, normal-transfer recovery, pending-save cancellation and failed-destination cooldown.
- Ship Manager R19, Node R9 and Gateway R19 with a transactional updater; retain every earlier roadmap area.
- Validate real two-worker chest transfer and acknowledged creeper recovery after source hard-kill. See docs/R19_VALIDATION.md for results and coverage limits.

# 0.0.6a.R18 — shared environment and live acceptance controls

- Add one leased overworld clock/weather owner, durable checkpoints, follower synchronization and restore-before-publish fencing. Preserve local scheduled-tick clocks.
- Node advances to R8; Manager advances to R18. Gateway R16 and the Conduit trace overlay are unchanged.
- Reject migration destinations that have not acknowledged a fresh environment snapshot.
- Fix undefined Test Lab run-state handlers, interlock all test buttons, retain selections, and report PASS/FAIL/blocked states.
- Match actual Minecraft inventory responses and separate fresh console output using unique markers.
- Show saved versus proxy-launch seamless policy and shared environment status in Diagnostics.
- Use fixed-delay heartbeats to avoid a burst of catch-up heartbeats after long scheduler suspension.
- Validate with two real headless NeoForge workers, including owner loss and Manager restart. Client seamlessness and mod-specific calendars remain acceptance work.

# 0.0.6a.R17 — gateway startup and forwarding correction

- Test Lab hotfix: replace stale R15 checks with R17-line Manager validation and the actual Gateway R16 heartbeat. Report configured transfer policy separately from observed seamlessness; skip live actions when preflight fails. Manager/Node/Gateway binaries are unchanged by this hotfix.
- Fix undefined Conduit replacement staging path in R16 bootstrap.
- Install the bundled Gateway R16 plugin before attempting Gradle.
- Validate and stage NeoForge Proxy Compatible Forge 1.2.2 downloads; repair invalid JARs and remove failed staging files.
- Validate forwarding before copying new worker folders; prevent premature worker creation in the WebUI.
- Expose startup failures and saved memory-budget arithmetic; suppress duplicate scaling recommendation logs.
- Preserve the current active configuration and the user's corrected RAM settings.
- Keep Node R7 / Gateway R16 / Minecraft 1.21.1 / NeoForge 21.1.250.

# 0.0.6a.R16 — 2026-09-23

See README.md, docs/R16_VALIDATION.md and docs/FEATURE_COVERAGE.md for the full implementation and acceptance record. This revision preserves Node R7 and the 0.0.6a base scope.

# Foundations Cluster Runtime 0.0.6a.R15

## NeoForge seamless bridge + migration stability + WebUI drawer polish

- Keeps the R12+ physical snapshot/preload/final-checkpoint/commit contract unchanged.
- Adds a narrow Foundations overlay to the pinned Conduit 1.8.0 seamless configuration handler. Backend CONFIGURATION plugin messages are offered to Velocity's active backend phase first so Proxy Compatible Forge / NeoForge responders can complete destination negotiation while the Minecraft client remains in PLAY. Unhandled configuration payloads are still absorbed rather than forwarded into the client's PLAY state.
- Gateway heartbeat identifies the active path as `conduit-1.8.0-foundations-r15-seamless-neoforge`; Gateway advances to 0.0.6a.R15.
- Cluster Node advances to 0.0.6a.R7 and preserves the R6 BlockEntity coalescing/unload fix.
- Fences stale data-plane and persistence publishes after Region Authority changes instead of repeatedly emitting HTTP 409 warnings.
- Deduplicates unchanged replica chunk snapshots and demotes zero-change replica applications to debug logging.
- Makes graceful worker Stop migration-aware: the normal evacuation grace still begins immediately, but an active evacuation handoff may continue up to `worker.evacuationMigrationTimeoutSeconds` (default 60s) before the worker is stopped/aborted.
- WebUI worker drawer now animates in/out, closes when the backdrop is clicked, closes with Escape, and retains the explicit close button.
- Repairs Windows Manager `@argfile` source-path generation in `cluster-manager\build_manager.bat` by writing forward-slash absolute paths, preventing `C:\Users...` from becoming an invalid escaped filename.
- `BUILD_ALL_VISIBLE.bat` keeps the console open and clearly reports aggregate build success/failure.
- R15 updater preserves worlds, runtime journals, player profiles, security state, worker worlds, operator configuration, and the isolated Java 25 gateway runtime.
- R15 NeoForge seamless handling is an experimental compatibility bridge and requires live acceptance testing with the actual modpack before it is considered complete.

# Foundations Cluster Runtime 0.0.6a.R14

## Gateway Java 25 isolation + BlockEntity unload fix

- Preserves the R13/R12 physical player-transfer contract and Conduit 1.8.0 seamless-switch path; no handoff sequencing changes in this revision.
- Fixes the R13 gateway startup failure where Conduit 1.8.0 exited immediately with `UnsupportedClassVersionError` because it was built for Java class-file version 69 (Java 25) while the Cluster Manager runs on Java 21.
- Installs an isolated Temurin Java 25 runtime under `velocity/runtime/java25` and launches only the Conduit gateway with it. The Manager and NeoForge workers remain on Java 21.
- Gateway bootstrap validates the Java 25 runtime before launch and can self-heal/reinstall it from the official Adoptium API if it is missing.
- Foundations Gateway plugin advances to 0.0.6a.R14 and reports `conduit-1.8.0-seamless-r14`.
- Cluster Node advances to 0.0.6a.R6.
- Fixes a BlockEntity persistence bypass in `ChunkEvent.Unload`: R13 force-published every BlockEntity in every unloading chunk, defeating the coalescer and causing thousands of commits per minute.
- Chunk unload now captures only changed fingerprints into the coalescer and force-flushes only pending mutations belonging to that chunk.
- Existing 40-tick scan interval, 256 scan budget, 5-second quiet coalesce, 5-minute hot-entity checkpoint, and lifecycle final flushes remain in place.
- WebUI/Desktop acceptance versions advance to Manager R14 / Node R6.

# Foundations Cluster Runtime 0.0.6a.R13

- Restores **real player migration** for operator/player transfer requests.
- `/api/handoffs/request` again arms the Velocity backend switch after snapshot/preload/final-checkpoint.
- Region transfers with `moveSessions=true` physically migrate affected players.
- Keeps internal anchor-preserving simulation-target transitions separate from player transfers.
- Acceptance tests now fail unless both `anchorWorkerId` and `simulationWorkerId` move to the destination.
- WebUI/Desktop wording now distinguishes physical worker migration from internal simulation routing.

# Foundations Cluster Runtime Changelog

## 0.0.6a — Dynamic Cluster Scaling + World Identity

- Added Manual, Assisted, and Automatic scaling modes.
- Added 75% default target-utilization planning and multi-signal pressure from population, MSPT, CPU, and heap.
- Added batch consolidation so multiple excess workers can drain toward a smaller worker set safely.
- Marks every worker in a consolidation batch DRAINING before migrations begin, preventing players from bouncing between workers that are all being retired.
- Added sustain windows and cooldown hysteresis to prevent scale thrash.
- Added physical-RAM-aware hard worker limits shared by autoscaling and manual worker creation.
- Added configurable host reserve and per-JVM reservation; automatic reservation derives Xmx plus JVM/native overhead.
- Added Assisted-mode approve action for both scale-up and consolidation recommendations.
- Added one persisted cluster world seed for new workers.
- Added actual worker world-identity fingerprinting from seed, Minecraft version, mod IDs/versions, datapacks, and selected worldgen configuration.
- Added seed/identity mismatch routing protection and alerts.
- Added dashboard telemetry for desired workers, effective RAM ceiling, available JVM slots, seed/world identity, and scaling reasons.
- Preserved R7.R4/R7.R5/R7.R6 block, BlockEntity, player-profile, and graceful-stop behavior.

## 0.0.5a.R7.R6 — Cluster Player Continuity + Graceful Worker Evacuation

- Added 10-second configurable player evacuation window for normal **Stop**.
- Added **Cancel Stop** and separate **Force Stop** operator actions.
- Stops new placement onto a worker as soon as graceful draining begins.
- Added batched handoff commands so many players can evacuate in parallel.
- Load-aware evacuation destinations include pending handoff reservations.
- Graceful Stop now waits for players, simulation targets, heartbeat count, and Region Authority ownership to reach zero before sending Minecraft `stop`.
- Stop aborts and restores routing if the evacuation deadline cannot be met.
- Added manager-owned cluster player profile staging for playerdata, advancements and statistics.
- Source Nodes explicitly flush player NBT, advancements and stats before evacuation snapshots.
- Added configurable `playerProfile.sharedFiles` for additional per-player mod files.
- Preserved stale-source rejection after handoff commit to prevent profile rollback.
- Kept JVM shutdown grace separate from the player evacuation deadline.

# Foundations Cluster Changelog

## 0.0.5a.R7.R5 — Generic BlockEntity Persistence

- Added durable manager-owned BlockEntity WAL/snapshot persistence.
- Added full `saveWithFullMetadata` BlockEntity capture and full-payload replay.
- Added generic mod compatibility through standard Minecraft/NeoForge serialization rather than per-block whitelists.
- Added `BLOCK_ENTITY_REQUEST` / `BLOCK_ENTITY_DELTA` authority write-through.
- Added BlockEntity payloads to authoritative chunk snapshots.
- Added tombstones, SHA-256 change fingerprints, dirty-chunk scanning, and cache invalidation.
- Raised default data-plane frame cap to 16 MiB and added an 8 MiB per-BlockEntity guard.
- Added BlockEntity persistence metrics to status/dashboard surfaces.
- Kept persistent state layer-independent and retained separate writable worker world directories.
- Scope still excludes arbitrary free entities/mobs, POI, scheduled ticks, and external mod stores outside normal BlockEntity/attachment persistence.

# Foundations Cluster Runtime changelog

## 0.0.5a.R7.R4 — restart-durable authoritative blocks

### Added
- Manager-owned durable block mutation journal:
  - `runtime/persistent-blocks.wal` for fsync'd mutation commits.
  - `runtime/persistent-blocks.tsv` compacted latest-state snapshot.
- Authority-owner replay endpoint: `/api/agent/persistence/replay`.
- Cluster Node replay on startup and authority owner/epoch/state changes.
- Periodic vanilla world checkpoints plus a final clean-shutdown checkpoint.
- Startup authority-owner recovery grace (default 90 seconds) so the persisted owner can return before automatic failover selects a potentially stale worker.
- Dashboard/control metadata for the active persistence protocol and journal revision/block count.

### Preserved
- Existing R7 `CHUNK_REQUEST` / `CHUNK_SNAPSHOT` / `BLOCK_DELTA` live replication.
- R7.R3 region transfer dropdown/operator workflow.
- Independent worker world directories; workers do not share one live world folder.

### Hardened
- Persistent commits are accepted only from the current ACTIVE authority owner at the current authority epoch.
- Region authority log output fingerprints owner/epoch/state instead of changing lease-expiry timestamps.
- Existing managed workers receive the current Cluster Node JAR from `server-template/mods` when started/restarted.

### Current boundary
R7.R4 guarantees a durable recovery overlay for ordinary final block states that pass through the R7 block interaction/data-plane path. Full block-entity/container/entity/POI and generic mod SavedData persistence is not claimed complete in this revision.
