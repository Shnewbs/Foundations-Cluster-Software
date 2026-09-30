# Foundations Cluster — Master Status and Roadmap, 2026-09-25 UTC

## Current source candidate: 0.0.6a.R19-HF9 / Node R11

Updated 2026-09-28 UTC. HF9 retains all HF8 features, including automatic Support ZIP capture. Node R11 and Gateway/Conduit source and binaries are unchanged.

**New user evidence (New folder(7).zip, HF8 run 13:07–13:13 UTC):** both workers loaded R11; the former stuck fire restore clears and regions become ACTIVE. Five inventory NBT samples are identical. Chest source/destination/return reads all contain 17 diamonds. Generated-tree source/destination/return hashes match exactly (59 blocks: 4 logs, 54 leaves, plus dirt). Two normal physical handoffs and one graceful-evacuation handoff committed. The worker stopped cleanly, but its separate restart registered DRAINED, preventing the WebUI's READY acceptance condition.

**HF9 fix:** distinguish temporary stop fencing from the operator's drain setting. Separate Stop → Restart now restores the prior/current operator policy instead of preserving the temporary drain. Manual drain and maintenance remain honored; later drain/undrain choices update intent without lifting an active stop fence. Cancellation and repeated stops preserve the policy. Restart-test failures include actual state and availability flags rather than only “did not return READY.”

**Validation:** 134 retained Manager/support checks, 14 new policy checks, 9 real Manager HTTP lifecycle checks using owned Java fixture processes, 83 WebUI unit cases, 13 hydration HTTP checks and 20 Support ZIP HTTP checks passed. Manager rebuilt for Java 21. The Java fixtures test process ownership/stop/restart/agent state; they are not Minecraft gameplay. HF8's prior live-worker/browser tests are historical, not repeated HF9 validation. No Windows execution is claimed.

**Separate open warning:** Minecraft cannot parse worker-001's player statistics JSON at login. That JSON and its central profile copy are absent from this log upload, so no data repair is attempted. The warning did not prevent the measured inventory/chest/tree transfers. Both workers were manually killed before the final cluster shutdown; subsequent connection-refused fallback messages occurred after no live backend remained.

**Next acceptance:** stop the whole cluster and apply HF9; confirm the startup package marker and Node R11. Enable failed-test Support ZIP capture. Run Graceful Stop + Restore, then Full Acceptance. Observe the client transfer to judge visible seamlessness. Generated-tree round-trip success does not establish natural growth on non-authoritative replicas. Full scheduled-tick/entity/mod-specific simulation, shared-world consistency and every remaining roadmap item below remain open.

See `docs/R19_HF9_VALIDATION.md` for detailed evidence and limitations.

---

## Historical candidate: 0.0.6a.R19-HF8 / Node R11

Updated 2026-09-28 UTC. HF8 retains every HF7 source payload feature and the working R19 gateway/Conduit artifacts.

**New operator feature:** Diagnostics → Support ZIPs offers Create & Download Latest, opt-in scheduled capture, automatic failed Test Lab capture, saved ZIP downloads, configurable retention and bounded log/journal sizes. Captures contain recent logs, merged runtime journal snapshots, region/environment/identity metadata, component summary and the current test report. Credentials, vault, authentication sessions, player profiles and world folders are excluded; nothing uploads automatically. This is diagnostic evidence. Full private world backups still require stopping the cluster; the offline backup tool now includes logs.

**Restore findings:** the uploaded runtime stores fire at 65,15,43, the exact coordinate repeatedly blocking HF7 hydration. Snapshot ingestion still called gameplay placement callbacks; fire can remove itself during that call. HF8 suppresses those callbacks only during ingestion, keeps chunk/BlockEntity bookkeeping and resumes fire ticks. Normal journaling now records the final state after nested mutations. Replay errors include expected/actual values, revision and region; retries are bounded. Chunk requests/snapshots also validate their coordinates against the claimed region, preventing neighboring-region data from using a stale session route.

**Executed evidence:** Manager regression 109, support archive 25, Node ledger 20, hydration HTTP 13, support HTTP 20, WebUI unit 78 and browser/API 61 checks passed. Two real NeoForge 21.1.250 workers passed nine checks: shared time/weather, saved-fire hydration, exact chest contents, identical generated-oak block state hashes across an authority round-trip, acknowledged creeper damage after a hard kill, environment failover and Manager restart. First integration attempt found a fixed-delay test race; the corrected test waits for worker readiness before creating the tree. Offline backup checks passed. Manager/Node target Java 21. Gateway binary/overlay unchanged. No connected client or Windows execution is claimed.

**Still open:** client-visible seamlessness; natural growth on non-authoritative physical replicas; complete scheduled-tick, entity and mod-specific state migration; full-region storage and writer/simulation ownership. Sharing one writable vanilla/NeoForge world directory between independent active workers does not provide this coordination. Earlier unjournaled losses cannot be reconstructed. Preserve all prior roadmap scope below.

**Install/test:** stop the entire cluster, apply HF8, restart and verify Node R11 on every worker. Enable failure capture in Diagnostics. Run Chest and Generated Tree separately, then Full Acceptance and an observed player transfer. Download the Support ZIP if anything fails. Keep world/journal data intact. See `docs/R19_HF8_VALIDATION.md` for precise limits and defaults.

---


## Historical candidate: 0.0.6a.R19-HF7

Manager **R19**, Node **R10**, Gateway **R19**, Conduit **1.8.0 with R19 guarded replay**. Minecraft 1.21.1 / NeoForge 21.1.250. All previous scope remains tracked on the 0.0.6a revision line.

### September 28 hotfix 7: destination restoration, chest and tree diagnostics

**New user evidence:** physical worker alignment and the inventory NBT round-trip now pass. The chest check fails on a missing fresh `minecraft:diamond` substring; the actual stage/worker response is not supplied. Suspected missing tree growth has not been reproduced from paired samples. Client-visible seamlessness is still unconfirmed.

HF7 ships Node R10. Newly acquired regions now restore independently; ACTIVE/RECOVERING labels do not trigger another replay of already restored data. R10 reports completed restoration by worker-process identity and region epoch, and Manager waits for that acknowledgement. Old replies, incompatible payloads, restarted processes, and stale control responses cannot claim a completed current restore. Replay suppresses neighbor-shape updates during reconstruction. Forced/immediate BlockEntity saves clear older coalesced payloads; local final block writes use target-position authority, and received remote requests validate coordinates. This removes a known redundant wrong-region write path; full remote border interactions remain prototype work.

The Test Lab checks the exact 17-diamond stack, includes source/destination/return errors and fresh console responses, waits for Node readiness as well as Manager ACTIVE state, preserves existing force-load tickets, and refuses occupied fixture space. A new Generated Tree test compares all 6,144 block states across source/destination/return after generating an oak, with log/leaf counts. It is an authoritative feature-generation/journal test, not proof of every mod's growth simulation.

Validation: 109 Manager assertions, 20 Node hydration-ledger checks, 13 real Manager HTTP checks with simulated workers, and 73 WebUI acceptance/button/handoff/persistence cases pass. Manager/Node target Java 21; source/updater integrity checked. HF6 gateway code and binaries are unchanged. Offline Gradle artifact setup and browser launch were unavailable; mapped Node compilation succeeded. The R10 two-worker integration script is updated but not executed here. No new live Minecraft or Windows result is claimed. See `docs/R19_HF7_VALIDATION.md`.

**Remaining priority:** growth on non-authoritative physical replicas can still diverge because full replica simulation/tick ownership is unfinished. Scheduled ticks, entities, full region snapshots, mod-specific simulation, Windows advancement-file locking, and prior roadmap scope remain open. Existing unjournaled losses cannot be reconstructed by replay. Apply HF7 with the whole cluster stopped, verify Node R10 on every worker, and run Chest and Generated Tree separately before Full Acceptance. Historical sections below retain their original version-specific evidence; HF7 is the current candidate.

### September 28 hotfix 6: NeoForge data-map, enum and feature declarations

The supplied acceptance output passes safe preflight but aborts handoff-00000001 at `neoforge:known_registry_data_maps` (385/385 bytes, different fingerprints). It lacks the paired payloads and startup hotfix marker. The exact live difference and end-to-end seamlessness remain unproven. An equivalent reordered declaration fixture reproduces HF5's rejection against the actual HF5 bridge.

The NeoForge 21.1.250 source confirms unordered registry/data-map declarations, enum entries keyed by class, and feature-flag sets. HF6 normalizes only these structures. It preserves all registry/map IDs and mandatory flags, whole enum entries including counts/network policy/ordered values, and complete unique feature membership. Empty declarations stay significant. Malformed or duplicate data rejects; remaining differences name the registry/map/class/flag. Actual client acknowledgement bytes remain unchanged. The new helper is shipped and required by bootstrap. Tag member sequences, registry snapshots, config-file bytes and unrelated tasks stay strict.

Validation: 366 new declaration checks plus 351 retained handshake checks pass, including loading shipped classes from the patched core. WebUI 49 and Manager 89 pass; source/updater payload integrity is checked. Counts include malformed/truncated fixture cases, not 717 live transfers. No joined client or Windows execution was available. See `docs/R19_HF6_VALIDATION.md`. Stop the entire cluster, apply HF6, restart with the HF6 package marker and experimental seamless enabled, reconnect and test one physical migration. Manager/Node/Gateway versions remain R19/R9/R19.

Live seamless acceptance, wrong-region writes, Windows advancement file sharing, controlled inventory continuity and all prior roadmap scope remain open. Historical sections below preserve their original evidence; use HF6 for the current installation.

### September 28 hotfix 5: tag map comparison and concrete mismatch reporting

The HF4 user trace passes the CONFIG pong, frozen manifest, all 30 frozen snapshots, completion and known packs. It then rejects TagsUpdatePacket: equal 38,207-byte lengths but different fingerprints. The paired payloads are unavailable, so their exact difference remains unknown. A real-Conduit fixture nevertheless reproduces HF4's rejection using equal tag mappings in different map orders.

HF5 normalizes only registry/tag map order. Every numeric member ID, duplicate and sequence remains exact. Empty tags/registries are retained; malformed/duplicate data and missing packets are rejected. Remaining differences name missing/added registries/tags or the first differing member, with an explicit sequence-only diagnostic where applicable. Capture never rewrites forwarded packets. The new helper is shipped and required by gateway bootstrap. All prior hotfixes remain included.

Validation: 222 tag checks plus 129 retained handshake checks pass, also from the real patched Conduit JAR; 49 WebUI cases and 89 Manager assertions pass. Source/updater payload hashes are verified. No joined client or Windows execution was available. See `docs/R19_HF5_VALIDATION.md`. Fully stop, apply HF5, restart with experimental seamless enabled, reconnect and test one physical migration. Confirm the startup HF5 marker; components remain Manager R19 / Node R9 / Gateway R19. Success still requires live physical ownership and no client reconfiguration screen.

Wrong-region block-event routing, Windows advancement file sharing, controlled inventory continuity and all previous roadmap areas remain open. Tag member order has deliberately not been relaxed. Historical notes below retain their original evidence and instructions; use the current HF5 package for installation.

### September 27 hotfix 4: order-sensitive frozen-registry comparison

The HF3 user run advances past the missing-pong stall and rejects `neoforge:frozen_registry_sync_start` promptly. NeoForge 21.1.250 consumes the manifest as a set and registry snapshots by name, but the old bridge compared both by sequence. A fixture with equivalent reordered names reproduces the exact HF3 rejection. The user's raw paired payloads were not supplied, so an actual membership difference remains possible.

HF4 compares the complete unique manifest set and matches snapshots by registry name while retaining exact snapshot bytes, numeric IDs and aliases. Extra/missing/duplicate traffic is rejected, including omitted snapshots before registry completion acknowledgement. Actual differences produce missing/added names or registry-specific lengths/fingerprints. The package retains all earlier hotfixes; startup identifies HF4, components stay Manager R19 / Node R9 / Gateway R19. Forty-eight new registry cases plus 81 retained handshake checks pass, also from the patched Conduit JAR; 49 WebUI cases and 89 Manager assertions pass. No joined-client seamless success is claimed. See `docs/R19_HF4_VALIDATION.md`.

Fully stop, apply HF4, restart and reconnect, then run one physical migration with experimental seamless confirmed at launch. The next error, if any, should distinguish genuine registry differences at this stage. Region-coordinate routing, immutable profile snapshots for Windows sharing violations, controlled inventory continuity and all original roadmap areas remain open.

### September 27 hotfix 3: missing NeoForge configuration pong

New enabled-mode logs now locate the stall after verified `neoforge:register` / `neoforge:network`. The exact 21.1.250 server patch waits for pong(0) before `runConfiguration`, but our seamless handler ignored the CONFIG ping. HF3 responds with the current backend ping ID, keeps the client in PLAY, preserves destination transcript checks, and logs the response. The failure reproduces on HF2; 32 new real-Conduit wire/dispatch checks pass alongside 49 retained handshake checks, also loaded from the patched JAR. All 89 Manager checks, 9 new handoff UI cases and 40 prior WebUI cases pass.

Test Lab now follows the requested handoff ID and surfaces terminal abort reasons immediately. The complete updater retains HF1/HF2, keeps Node R9 and Gateway plugin R19, and adds the HF3 startup marker. Fully stop, apply HF3, restart, reconnect, and run one physical migration with seamless confirmed enabled at launch. No joined-client seamless success is claimed. See `docs/R19_HF3_VALIDATION.md`.

The preceding normal-transfer logs confirm the chest's 17 diamonds on both workers, an empty-inventory round trip, and successful transfers. Later inventory comparisons gained grass/leaves; pickup/replication must be isolated before judging item loss or duplication. Two confirmed open issues now lead the next persistence work: block events use a player-region route across target-block boundaries (409 wrong-region writes), and Windows advancement saves collide with file access. HF3 does not fix either. Earlier healthy preflight does not override later failures. Preserve all original persistence, simulation, mod-compatibility and administration roadmap scope below.

### September 27 hotfix 2: Node R8 reinstalled at startup

The latest supplied run proves the Manager reinstalled Node R8 after update. Its expected Node constant had not advanced with the bundled R9. HF2 rebuilds the Manager with Node R9 selected, ignores stale Node copies during worker synchronization, and prevents old-node Save Health false positives. The startup line now includes the HF2 package marker. Fifteen upgrade lifecycle checks pass (and reproduce failure against HF1), along with 74 prior Manager assertions and 40 WebUI fixture cases.

Apply the full HF2 updater while stopped, confirm both workers report R9, then rerun Safe Suite and chest transfer. The three generic gateway failures at approximately six seconds remain unresolved; request the actual gateway configuration trace and destination worker log before changing negotiation. See `docs/R19_HF2_VALIDATION.md`. HF1 registry-packet changes and all earlier scope remain included.

### September 27 hotfix: registry decode disconnect

R19-HF1 corrects a confirmed R19 regression: inspecting RegistrySyncPacket consumed its data before forwarding to the client. The real Conduit reproducer sent zero bytes from a 26-byte payload after capture; HF1 preserves the complete payload and packet ownership. The 29 new packet-integrity checks and 20 existing handshake checks pass. Component JAR versions remain Manager R19 / Node R9 / Gateway R19. Apply the HF1 updater and test fresh login first. Full seamless client acceptance remains open; see `docs/R19_HF1_VALIDATION.md`. All original scope and previous evidence below are retained.

### R19 additions

- **Explosion terrain persistence:** authoritative chunk mutation capture includes creepers, commands and other changes through the chunk API. A real creeper crater survived an acknowledged save followed by source hard-kill and survivor recovery.
- **Confirmed save before ownership transfer:** source-region sealing, current terrain and BlockEntity flush, durable acknowledgement, epoch/process fencing, cancellation and safe failure. A newly created chest with 17 diamonds survived immediate region transfer.
- **Guarded NeoForge handshake replay:** the second R18 log proves seamless was enabled but destination negotiation stalled at `neoforge:register`. The new bridge captures the actual client's successful login responses, verifies destination configuration, and rejects mismatches or unsupported tasks explicitly. Real-client seamless acceptance is still required.
- **Operator recovery:** pending/failed writes and durable-save timestamps, persistence alerts, Safe Suite gate, support-summary copy/download, normal-transfer recovery, pending-save cancellation, specific transfer errors and failed-destination cooldown.
- **Retained environment work:** time/weather propagation, owner failover and Manager-restart recovery passed again with two real headless NeoForge workers.

The Feature Status page now tracks **48 areas**. `docs/R19_VALIDATION.md` records commands, results and limitations. The source/updater ship Manager R19, Node R9 and Gateway R19 together.

### Next acceptance and remaining priorities

Stop the cluster, apply R19, restart and hard-refresh. Confirm all component versions and zero pending/failed writes. Test an authoritative creeper blast and chest transfer on disposable data. For experimental seamless switching, fully restart with the policy enabled, reconnect the client, and observe A→B→A while collecting correlated traces.

Next engineering priorities are full replica write/tick enforcement and persistent entities, worker-owned immutable profile snapshots for the Windows sharing violation, mod-specific machine/TFC adapters, and real-client seamless compatibility. Full region snapshots, remote hosts, granular roles and high availability remain in the retained roadmap. Unacknowledged last-instant writes and independent non-authoritative replica mutations are not losslessly covered by R19.

---

# Historical R18 status (retained)

# Foundations Cluster — Master Status and Roadmap, 2026-09-25 UTC

## Historical source candidate: 0.0.6a.R18

Manager **R18**, Node **R8**, unchanged Gateway **R16** and Conduit **1.8.0 with the existing trace overlay**. Minecraft 1.21.1 / NeoForge 21.1.250. The original scope remains on the 0.0.6a revision line.

### Latest corrections and evidence

- The three individual Test Lab buttons failed on missing JavaScript run-state definitions. R18 supplies the shared lock and visible blocked/failure output. Full Acceptance also used the wrong inventory-response phrase; R18 parses the actual reply and fences old console output with unique markers.
- R17 had no worker time/weather synchronization. R18 adds one leased overworld environment owner, clock/day-rate/weather snapshots, persisted checkpoints, stale-writer fencing, follower acknowledgements and environment readiness for migration. Diagnostics shows the owner and worker synchronization. This is an implemented vanilla/NeoForge adapter; TFC seasons, custom calendars/climate and cluster-wide sleep voting remain planned.
- Real headless NeoForge workers passed time/weather command propagation, source-owner shutdown/failover and Manager restart recovery. The browser/API suite passes 52 checks. Native Manager checks pass 65 assertions; WebUI fixtures pass 20 acceptance + 16 button/output cases. Boundaries and reproducible commands are in `docs/R18_VALIDATION.md`.
- The newest supplied gateway startup (September 24, 20:14:02 Pacific) still says `seamless-server-switches = false`; physical A→B→A commits at 20:17:14 and 20:17:28. R18 makes saved and launch policy visible. The experimental negotiation bridge is unchanged and client-visible seamlessness is still unproven.
- The earlier Windows advancement-save sharing violation remains a separate unresolved player-profile safety issue.

### Next live acceptance

Apply the stopped-cluster R18 updater, hard-refresh the WebUI, confirm Node R8 everywhere, and wait for Diagnostics to show both workers synchronized. Test day/night and weather from the displayed owner, then player A→B→A and the repaired Test Lab controls on disposable data. For seamless acceptance, enable the experimental policy, save, fully restart, and confirm the new proxy startup reports true before collecting a transfer trace and client latest.log.

All earlier design areas below remain tracked; the environment work adds a 44th feature-status area. No high-availability, remote-host, complete mod replication or lossless-profile claims are added.

---

# Historical R17 status (retained)

# Foundations Cluster — Master Status and Roadmap, 2026-09-23

## Previous source candidate: 0.0.6a.R17

Manager **0.0.6a.R17**, unchanged Node **0.0.6a.R7** and Gateway **0.0.6a.R16**. Loader remains NeoForge 21.1.250 on Minecraft 1.21.1.

R17 corrects the R16 gateway-bootstrap staging regression that prevented Proxy Compatible Forge installation. The bootstrap now prefers the bundled adapter, validates forwarding downloads, and preserves the core on failed overlay validation. Worker spawning validates prerequisites before copying directories; the UI waits for forwarding readiness and explains gateway/RAM blockers. Saved RAM settings are preserved.

Initial validation: 32 gateway bootstrap assertions, 36 Manager assertions and 47 browser/API checks pass. The later Test Lab correction passes 17 targeted fixture cases and JavaScript syntax validation. See `docs/R17_VALIDATION.md` for test boundaries.

### Latest live evidence and next step

The supplied Windows logs show successful R17 gateway bootstrap and three committed physical transfers (worker-001 → worker-002 → worker-001 → worker-002). Conduit started with `seamless-server-switches = false`. The audit records a later edit to `gateway.seamless.enabled`, but the capture contains no restart afterward. This run therefore does not establish experimental seamless behavior. The WebUI now distinguishes transport readiness, configured policy and live acceptance; its old R15 version assumptions are corrected without changing runtime binaries.

Next: enable the experimental policy in Diagnostics, fully restart the cluster, verify `seamless-server-switches = true` in the new gateway startup, and observe one same-dimension A→B→A transfer. Capture `FoundationsConfigTrace` and both worker logs if negotiation stalls or a visible transition remains. See `docs/R17_TRANSFER_NEXT_STEPS.md`.

**State preservation remains an acceptance blocker:** the supplied run has one failed player-profile capture and an advancement-save Windows sharing violation during logout. Source inspection shows asynchronous logout reporting can overlap Manager reads of live profile files; this is a race hypothesis, not proof of the locking process. Investigate a worker-owned, immutable profile snapshot and ordered save/capture/restore protocol before claiming lossless migration. No persistence fix is included in the Test Lab hotfix.

The complete 43-area scope and unfinished live-seamless/distributed work below are retained.

## R16 implementation baseline

Manager **0.0.6a.R16**, Node **0.0.6a.R7 (unchanged)**, Gateway **0.0.6a.R16**, Conduit **1.8.0 with R16 trace instrumentation over the R15 bridge**. Minecraft 1.21.1 / NeoForge 21.1.250. Manager/workers remain Java 21; gateway remains Java 25.

R16 builds out the original WebUI mockup and closes several operator/runtime gaps: global planned-shutdown suppression; heap/freshness/MSPT/capacity checks for migration targets; persisted worker pinning; atomic configuration validation; stale/stopped telemetry filtering; host health; bounded historical metrics; alert acknowledgements; transfer diagnostics; diagnostic export; ownership map; command search; export/sort/log controls; responsive and keyboard behavior; JDK 21 discovery; transactional updater; offline backups; and a new minimal desktop client.

**The original scope is retained. It is not all complete.** Seamless destination NeoForge negotiation is still unproven. Normal physical transfer with visible reconfiguration is the default; the experimental bridge is opt-in and instrumented. Distributed hosts, granular roles, shared storage, complete entity/mod replica semantics and high availability remain tracked work. No fake logical-only physical migration was introduced.

### Validation

Manager, Gateway and Conduit overlay compile. Unchanged Node R7 source compiles against the supplied mapped artifacts, while the normal Gradle ModDev 2.0.147 dependency resolution is blocked here. Manager regressions (27 assertions), both reference suites, browser/API tests (42 checks), desktop/mobile visual review, backup checks and updater installation/rollback transactions pass. PowerShell syntax passes. Real-client transfers, game-state soak tests, Windows launcher behavior and native desktop rendering require live acceptance.

### Next acceptance order

1. Apply the updater to a stopped copy of the current R15 installation.
2. Run normal physical A→B→A transfer and inventory/state acceptance.
3. Prove graceful stop, hard crash and intentional shutdown with a joined player.
4. Run persistent world/data-plane soak tests.
5. Enable experimental seamless mode and capture the exact unanswered configuration channel/task. Fix only from that evidence.
6. Validate Windows build/bootstrap, updater rollback and offline world backup.
7. Keep the base at 0.0.6a until these gates pass; then expand the distributed phases.

### Complete feature matrix

| Area | Stage | Behavior and remaining work |
|---|---|---|
| Single-machine orchestration | implemented | Manager, reusable worker IDs/ports, template installation, launch and supervision. Validate launchers on Windows with JDK 21. |
| Manual worker controls | implemented | Start, warm start, drain, cancel, maintenance, stop, restart, kill, retirement and deletion. Deletion keeps existing explicit safeguards; use test worlds. |
| Pinned capacity | implemented | Persistent worker pin prevents automatic scale-down while leaving manual controls available. Use worker drawer → Pin online. |
| Named worker profiles | partial | Default heap/JVM profile and memory reservation are configurable. Multiple named templates, affinity, priority and NUMA placement remain planned. |
| Manual / assisted / automatic scaling | implemented | Sustain windows, cooldowns, RAM budgets, consolidation and operator approval. Production scaling still depends on live migration acceptance. |
| Warm spares | implemented | Warm worker creation and configurable warm capacity targets. Predictive demand forecasting is planned. |
| Cluster performance history | implemented | Bounded 5-second, minute and hourly tiers; graph ranges through seven days. History accumulates in memory and resets on Manager restart. |
| Per-worker history | implemented | One hour of bounded MSPT, percentile, heap, CPU, players and chunk telemetry. Open a worker → Metrics. GC pause histograms and allocation rates need additional instrumentation. |
| Local host health | implemented | Host CPU, available RAM, swap, storage, Manager heap and runtime. Unavailable OS counters remain explicitly unavailable. |
| Extended platform counters | planned | Per-core CPU, network rate, disk latency, temperatures and file descriptors. Add platform adapters without blocking Minecraft ticks. |
| Alerts and acknowledgements | implemented | Live incidents, severity thresholds, persisted acknowledgements and resolved-incident clearing. External notification channels are not connected. |
| Unified console / logs / audit | implemented | Manager/worker consoles, search, pause, downloads, CSV export and audited admin mutations. Long-term structured log retention/export pipelines remain planned. |
| Diagnostic summaries | implemented | Download an allowlisted ZIP of component health, host measurements and transfer metadata. Raw logs, secrets, world files and player profiles are excluded. |
| Consistent offline backups | implemented | Stopped-cluster backup tool, SHA-256 manifest and archive verification. Stop the Manager before taking a backup; live snapshots are not implemented. |
| Transactional program updater | implemented | Portable folder selection, stopped-process checks, checksummed payload, backups and rollback. Requires Windows execution acceptance on the target machine. |
| WebUI mockup layout | implemented | Dark dashboard, six overview metrics, load graph, distribution, worker table, quick actions and events. Live ownership map replaces fictional terrain imagery. |
| Operator quality of life | implemented | Ctrl+K search, deep links, sort/export, dirty-form protection, responsive layout, focus trapping and stale-data indicators. Use Feature Status to inspect every roadmap area. |
| World ownership map | implemented | Coordinate-based region view, dimension filter, zoom, pan and cell inspection. A terrain tile renderer is not yet connected. |
| Population layer planner | acceptance | Layer cells, lease placement, party affinity and split/merge foundations exist. Validate large populations and game entity visibility. |
| Persistent bases across layers | partial | Blocks and BlockEntities use one-writer authority and journals. Persistent entities, machines, mod semantics and complete replica tick suppression require further work. |
| Region authority / epochs | acceptance | Leases, fencing, recovery, transfer and authority map are implemented. Prove no duplicate writers under real crash and load tests. |
| Region snapshot transfers | partial | Checksummed snapshot/install coordinator exists in the reference module. Integrate and acceptance-test full region files across live workers. |
| BlockEntity persistence | acceptance | R7 coalescing, throttled scans, host-block state and lifecycle flushes are preserved. Run dynamic TFC/machine/chest soak tests. |
| Data plane / replicas | acceptance | Chunk/block relay, fencing and deduplication exist. Test stale publisher behavior, saturation and replica consistency. |
| Player state / profile restore | acceptance | Capture, preload, final snapshot and profile preparation are implemented. Test inventory, advancement and mod attachment preservation. |
| Physical backend migration | acceptance | Explicit transfers require destination readiness and real backend movement. Run both directions with a connected client; confirm anchor and simulation. |
| Migration headroom | implemented | Reject stale, full, unhealthy, high-MSPT and over-heap destinations. Reserve host memory in Scaling and tune placement.maxHeapPercent. |
| Seamless NeoForge switching | partial | R15 experimental bridge retained; normal physical reconfiguration is the default. Unanswered destination configuration tasks remain unproven; capture new traces before changing negotiation. |
| Transfer evidence timeline | implemented | Correlated gateway switch stages, elapsed time, commit/abort diagnostics and exports. Packet-level bridge logs remain gateway-side; client seamlessness requires observation. |
| Graceful evacuation / rolling restart | acceptance | Migration-aware stop waits for players and authority before exit. Validate successful migration, timeout abort and last-worker handling. |
| Emergency crash recovery | acceptance | Backend failure checks, quarantine, profile preparation and redirects. Validate hard crash behavior and repeat failure limits in live cluster. |
| Intentional shutdown | implemented | Global gate suppresses failover, automatic planning and new topology mutations before workers stop. Run a joined-client shutdown test to verify the proxy path. |
| Java toolchain isolation | implemented | JDK 21 discovery for builds; isolated Java 25 gateway runtime retained. Clean-machine Windows provisioning still requires acceptance. |
| Administrator security | implemented | Argon2id login, encrypted vault, authenticated APIs, same-origin mutation checks and audit. Default binding remains loopback; remote TLS belongs at the reverse proxy. |
| Granular administrative roles | planned | Current runtime has one administrator role. Add Viewer, Operator, Administrator, Forensics and Owner enforcement server-side. |
| Economy / Framework boundary | partial | Economy remains separate from worker orchestration. No Economy health or ledger provider is included in this source package. |
| Native desktop client | partial | New standard-library Tkinter client supplies authenticated worker controls, live summary and logs without PySide dependencies. Desktop window behavior needs Windows acceptance; full Test Lab and topology administration use the WebUI. |
| Remote worker hosts | planned | Existing orchestration currently owns local processes. Build authenticated remote node agent, resource inventory and host drain. |
| Shared SQL / messaging providers | planned | Local authoritative state stores are preserved. Add transactional metadata provider, coordination and storage abstraction. |
| Cross-host world transfer | planned | Reference snapshot structures establish the transfer model. Add authenticated install endpoints, fencing and interrupted-transfer recovery. |
| High availability | planned | Manager and gateway are currently single instances. Add durable leadership, redundant gateways and replicated control-plane state. |
| Seamless cell boundaries | partial | Logical simulation movement exists separately from explicit physical migration. Validate physical handover, visibility, persistent entities and no lost writes. |
| Test Lab | acceptance | Safe and live suites, handoff, inventory, chest and lifecycle checks are preserved. Local tests cannot certify joined-client protocol or real Minecraft rendering. |

---

# Historical R15 handoff (preserved verbatim)

The following 2026-09-22 sections describe the previous R15 baseline, not an additional claim that its pending acceptance is complete. The R16 status above takes precedence.

# Foundations Server Cluster Runtime — Master Status, History, and Roadmap

**Project:** Foundations Server Cluster Runtime  
**Minecraft target:** 1.21.1  
**Loader:** NeoForge 21.1.250  
**Current runtime line:** `0.0.6a`  
**Current development target:** `0.0.6a.R15` + same-version hotfixes  
**Current Cluster Node:** `0.0.6a.R7`  
**Current Gateway plugin:** `0.0.6a.R15`  
**Current proxy core:** Conduit 1.8.0 / Velocity-compatible  
**Gateway Java:** isolated Java 25 runtime  
**Minecraft workers / Manager Java:** Java 21  
**Document date:** 2026-09-22  
**Purpose:** Living master document for what Foundations Cluster is supposed to become, what has already been implemented, what was learned during R7–R15, what remains broken, and the acceptance criteria for moving beyond the `0.0.6a` revision line.

---

# 1. Executive Summary

Foundations Cluster is being built as a **Minecraft server orchestration and world-runtime layer** that can start as a normal single-machine server and scale toward a seamless multi-JVM / multi-machine Minecraft environment.

The long-term player experience is intended to feel like **one server and one persistent world**, even when simulation, player population, region ownership, or capacity is distributed across multiple worker JVMs.

The project currently has working foundations for:

- a dedicated Java Cluster Manager;
- managed NeoForge worker JVMs;
- automatic worker discovery and startup;
- persistent worker IDs and port assignments;
- a central WebUI/API on `127.0.0.1:8765`;
- a desktop administration interface;
- worker health and lifecycle controls;
- persistent player session coordination;
- player profile capture/preload/restore;
- layers and layer placement;
- region authority and epoch-based ownership;
- region failover and evacuation;
- persistent block and BlockEntity journals;
- a simulation data plane;
- an external Velocity/Conduit gateway;
- real physical player transfers between Minecraft backends;
- crash/emergency failover plumbing;
- centralized logs and admin audit trails;
- portable incremental updater packages with backups and rollback;
- Test Lab / acceptance-test tooling;
- Java 25 isolation for the modern proxy while Minecraft remains on Java 21;
- WebUI sidebar/drawer behavior, including click-outside and Escape-to-close;
- significant BlockEntity persistence flood reduction.

The **main remaining blocker** is very specific:

> A real physical transfer reaches the destination NeoForge worker, but the destination stalls during its configuration/login phase and the proxy reports a read timeout after roughly six seconds.

The Manager-side physical handoff pipeline is no longer the primary suspect. Source snapshots, destination preload, final source snapshots, and physical switch arming all complete. The unresolved work is the **NeoForge configuration handshake across a seamless proxy backend switch**.

Before the `0.0.6a` line is considered complete, the project also needs:

- intentional cluster shutdown suppression for emergency failover/quarantine logic;
- reliable Java 21 JDK discovery/provisioning for build tools;
- final validation of migration-aware graceful stop behavior;
- final validation of dataplane/replica deduplication under load;
- reliable end-to-end Test Lab acceptance after all runtime components are normalized;
- a proven seamless physical migration with no client-visible Reconfiguring screen on compatible workers.

---

# 2. Core Product Goal

The target user experience is:

```text
Player joins one server address
          |
          v
Foundations Gateway / Proxy
          |
          v
Foundations Cluster Manager
      /      |      \
     /       |       \
Worker A  Worker B  Worker C
     \       |       /
      \      |      /
       Persistent World
```

A player should not need to know:

- which worker JVM currently owns their network connection;
- which JVM currently simulates their layer;
- which worker owns the surrounding region;
- whether a new worker was started because population increased;
- whether an old worker was drained;
- whether a region was moved to a healthier worker;
- whether the server is running on one physical machine or many.

The system should expose all of that to administrators while hiding it from normal gameplay.

---

# 3. Non-Negotiable Architecture Rule

The most important world-state rule remains:

> **Layers may split population and temporary simulation, but they must not decide whether persistent player-built world data exists.**

Persistent state needs one authoritative interpretation at a time.

Persistent examples include:

- blocks placed or broken by players;
- bases and structures;
- chests and inventories;
- BlockEntities;
- TFC soil/crop state;
- machines;
- claims;
- persistent entities and ownership;
- player profiles;
- progression;
- economy balances and ledgers;
- marketplace state;
- server-owned persistent structures.

Potentially layer-safe or transient state may include:

- visible player population;
- temporary encounters;
- layer-scoped mobs when explicitly safe;
- projectiles;
- particles;
- temporary drops;
- NPC crowds;
- other state intentionally designed to be ephemeral or mergeable.

This distinction is the foundation for seamless phasing without duplicating or losing player-built worlds.

---

# 4. Major Components

## 4.1 Cluster Manager

The Manager is the orchestration authority and should remain a separate Java application.

Responsibilities already implemented or under active development include:

- worker creation and discovery;
- process startup/restart/stop;
- worker IDs and port allocation;
- worker heartbeat tracking;
- health state;
- automatic baseline worker startup;
- manual worker spin-up;
- drain and graceful stop;
- crash detection;
- region authority management;
- layer planning;
- session placement;
- player handoffs;
- persistent player profile coordination;
- persistent block journals;
- persistent BlockEntity journals;
- simulation data plane;
- gateway lifecycle supervision;
- central API;
- WebUI backend;
- configuration validation;
- security vault access;
- audit events;
- test/acceptance endpoints.

The Manager should never require Economy itself to understand process orchestration.

## 4.2 Foundations Cluster Node

The Cluster Node is the NeoForge mod installed in every Minecraft worker.

Current responsibilities include:

- registering the worker with the Manager;
- heartbeats;
- reporting player/session state;
- player snapshot and preload operations;
- persistent block publication/replay;
- persistent BlockEntity publication/replay;
- block-state-aware BlockEntity persistence;
- data-plane publish/receive;
- region-authority awareness;
- local write fencing;
- replica application;
- handoff lifecycle hooks;
- coalescing BlockEntity writes;
- lifecycle flushes;
- dynamic BlockEntity persistence throttling.

## 4.3 Foundations Gateway

The gateway layer currently consists of:

- Conduit 1.8.0 as the proxy core;
- the Foundations Gateway plugin;
- Proxy Compatible Forge forwarding prerequisites;
- modern forwarding configuration;
- Manager topology synchronization;
- physical backend transfer requests;
- emergency backend-loss redirect logic;
- Conduit seamless-switch functionality;
- an R15 Foundations patch/bridge intended to improve NeoForge seamless configuration handling.

The gateway runs on an isolated Java 25 runtime because Conduit 1.8.0 requires a newer class/runtime level than the Java 21 Minecraft stack.

## 4.4 WebUI

The WebUI is the primary browser administration interface.

Current/implemented areas include:

- Dashboard;
- Workers;
- World Map;
- Layers;
- Regions;
- Players;
- Gateway;
- Economy placeholder/integration area;
- Scaling;
- Console;
- Logs;
- Test Lab;
- Alerts;
- Settings;
- Audit.

R15 UI polish includes the requested drawer behavior:

- opening a side drawer overlays the rest of the page;
- clicking outside the drawer closes it;
- Escape closes it;
- the close button still works;
- the drawer slides away rather than disappearing abruptly;
- reduced-motion support is retained for accessibility.

## 4.5 Desktop Admin UI

The desktop interface remains an optional administrative client over the same Manager/API model.

Important work already done includes:

- Manager/API-backed status;
- test execution;
- retention of background task objects/signals so PySide does not destroy them mid-run;
- improved Safe/Full test task lifecycle;
- crash logging.

## 4.6 Test Lab

The Test Lab is intended to prove runtime behavior rather than merely display status.

It should validate:

- Manager version;
- Node version;
- Gateway version;
- gateway ownership/adoption;
- Conduit runtime transport;
- worker health;
- region authority;
- persistent state;
- physical player transfer;
- anchor + simulation movement;
- layer operations;
- restart recovery;
- graceful evacuation;
- emergency failover;
- intentional shutdown behavior;
- seamless transfer acceptance.

---

# 5. Operating Modes We Want

## 5.1 Standalone

```text
1 physical machine
1 Minecraft worker
optional Manager supervision
local storage
proxy optional
```

Goal: a normal small server should not need distributed infrastructure complexity.

## 5.2 Local Cluster

```text
1 physical machine
multiple worker JVMs
1 Manager
1 gateway
shared runtime state
```

This is the mode currently being built and tested most heavily.

## 5.3 Distributed Cluster

```text
multiple physical machines
multiple worker JVMs per host
Manager/control plane
shared storage/messaging
one logical player-facing server
```

Long-term requirements:

- remote worker registration;
- secure node authentication;
- node-aware scheduling;
- machine-level CPU/RAM/IO/network metrics;
- network-aware region/layer placement;
- shared authoritative persistence;
- multi-node worker recovery;
- eventually HA/failover for critical control-plane services.

---

# 6. Version and Revision Policy

Foundations development uses a base alpha version plus revision suffixes.

Examples:

```text
0.0.6a
0.0.6a.R2
0.0.6a.R3
...
0.0.6a.R15
```

Rules:

1. Keep the same base version while finishing that version's agreed scope.
2. Use `.R2`, `.R3`, etc. for revisions.
3. Do not advance the base alpha just to escape a bug.
4. Once the scope is stable and acceptance passes, advance the base alpha.
5. Ship a full source package and an incremental portable updater when practical.
6. Updaters must back up changed files and preserve live state.
7. Updaters must be usable from Downloads/Desktop and ask for the Cluster root.

---

# 7. Runtime Data That Must Be Preserved

An updater, repair, cleaner, or build operation must not casually replace or delete:

```text
runtime/
workers/
logs/                (unless explicitly archived/cleaned)
server-template/
velocity/
config/cluster.properties
player profiles
world data
world identity
security vaults
journals
session state
region authority state
```

When binaries need replacement:

- replace only the intended Manager/Node/Gateway/proxy artifact;
- replace the Node in `server-template/mods` and existing worker `mods` when required;
- remove stale duplicate Foundations JARs;
- preserve everything else;
- make rollback possible.

---

# 8. Development History — What Has Been Done

This section records the major lessons and changes from the current development cycle.

## 8.1 Original Cluster Runtime / 0.0.5a.R7.R2 Era

The initial runtime established the working directory concept, Manager, worker runtime, WebUI, gateway experiments, test utilities, and the general Foundations cluster architecture.

It proved the project was feasible but exposed problems with:

- stale world identity assumptions;
- process ownership;
- transfer semantics;
- gateway lifecycle;
- persistence load;
- UI/test reliability;
- version drift across Manager/Node/Gateway artifacts.

That line became the source for the more structured `0.0.6a` revision work.

## 8.2 R7

R7 exposed important baseline problems rather than hiding them.

Notable findings/fixes:

- stale world-identity behavior surfaced;
- GUI/test crashes became visible;
- clearer acceptance criteria began forming around worker transfers and persistence;
- debugging shifted toward explicit logs rather than silent fallback behavior.

## 8.3 R8 — First “Seamless” Handoff Attempt

R8 produced a transition that looked seamless to the client, but the reason it looked seamless was that the physical Velocity backend connection remained anchored to the old worker.

Behavior:

```text
simulationWorkerId moved
anchorWorkerId stayed on source
no Velocity backend switch
```

This was rejected as the long-term solution because it was not a true physical worker migration.

Important lesson:

> A logical simulation handoff is not sufficient. Acceptance requires the actual network/backend anchor to move too.

## 8.4 R9 — Quarantine / Single-Flight / Updater Validation

R9 added or strengthened:

- detached-worker quarantine;
- Manager-controlled lifecycle flags;
- run-scoped log scanning;
- Test Lab single-flight behavior;
- orphan cleanup;
- updater version validation.

An updater bug initially checked the wrong source version. It was corrected so the built Manager JAR / `ClusterVersion.class` is the authoritative version source.

## 8.5 R10 — Graceful Evacuation

R10 fixed lifecycle paths that could stop workers without first attempting a proper player evacuation.

A good flow became:

```text
Evacuation handoff requested
source snapshot
preload destination
final snapshot
handoff committed
source worker stop
```

This established the desired relationship between worker retirement and player migration.

R10 also exposed two major issues:

- abruptly dead workers need emergency failover rather than graceful-only logic;
- BlockEntity persistence was far too aggressive and could create huge write/log volume.

## 8.6 R11 — Persistence v3, Emergency Failover, Gateway Ownership

R11 was a major stabilization pass.

Implemented:

- Manager `0.0.6a.R11`;
- Node `0.0.6a.R4`;
- Persistent BlockEntity journal schema `host-block-v3`;
- host block state stored alongside BlockEntity NBT;
- backward compatibility with previous journal format;
- BlockEntity write coalescing controls;
- emergency session failover;
- gateway process PID ownership/adoption;
- protection against starting a second gateway on an occupied port;
- backend-loss redirect support in the Velocity plugin;
- additional Desktop background-task retention work.

R11 still retained the logical-normal-handoff design for some flows, so physical migration remained incomplete.

## 8.7 R12 — Real Physical Backend Migration Restored

R12 was the critical semantic correction.

Explicit player transfer was changed to use the physical backend migration path.

Acceptance became:

```text
anchor worker moves
AND
simulation worker moves
```

Logs proved actual source → destination backend transfer.

This solved the “fake seamless” problem but restored the visible modern Minecraft **Reconfiguring** phase.

That gave the project the correct next objective:

> Keep physical migration, but make the client transition seamless.

## 8.8 R13 — Conduit Seamless-Proxy Direction

R13 attempted to preserve R12 physical migration while changing the proxy runtime to Conduit 1.8.0 for seamless switching.

Implemented/planned in R13:

- Manager R13;
- Node R5;
- Gateway R13;
- Conduit 1.8.0 runtime preparation;
- Conduit config with seamless switches enabled;
- Proxy Compatible Forge forwarding prerequisites;
- BlockEntity scan/coalescing changes;
- Desktop Test Lab task-lifetime fixes;
- stronger updater validation.

R13 discovered that Conduit 1.8.0 was built for a newer Java runtime than the Java 21 Cluster launch path.

## 8.9 R14 — Isolated Java 25 Gateway Runtime

R14 introduced an isolated Java 25 runtime for the gateway while keeping the Minecraft/NeoForge side on Java 21.

This is now the intended split:

```text
Manager / Minecraft workers / NeoForge build = Java 21
Conduit gateway                      = Java 25
```

R14 updater debugging uncovered several Windows/PowerShell packaging problems:

### `$HOME` collision

PowerShell variable names are case-insensitive. A script variable named `$home` collided with the built-in read-only `$HOME` variable.

Fix:

```text
$home -> $javaHome
```

### Java 25 false-negative validation

The first Java validation relied too heavily on `java -version`, whose output behavior on Windows/PowerShell can be awkward because the version string is written to stderr.

The validation was changed to prefer the JRE `release` metadata and use a process-based fallback.

### Mixed-state rollback

A failed R14 update exposed incomplete rollback/normalization behavior. The installation could contain a mixture such as:

```text
Manager R13
Node R5
Gateway R14
Conduit 1.8.0
Java 25 runtime
```

A targeted mixed-state repair was created to normalize the install.

### Cluster Doctor / Deep Clean

Portable maintenance utilities were added to:

- audit the current Cluster directory;
- identify version drift;
- identify duplicate/stale JARs;
- identify historical build clutter;
- preserve state areas;
- quarantine rather than permanently delete cleanup targets;
- normalize active Node/Gateway artifacts.

The directory audit found historical Node/Gateway builds, logs, reports, caches, and stale proxy plugin artifacts worth cleaning.

## 8.10 Test Lab R13+ Hotfix

Several tester/UI failures were corrected:

- WebUI live-test buttons referenced undefined busy-state helpers;
- Desktop Safe/Full test tasks could be garbage-collected/destroyed before signal completion;
- version expectations were too hard-coded for rapidly changing revisions.

The tester hotfix made version checks more dynamic and improved task retention.

## 8.11 R15 — Current Integration Revision

R15 was built as a combined networking/stability/UI pass.

Target component versions:

```text
Manager:        0.0.6a.R15
Cluster Node:   0.0.6a.R7
Gateway plugin: 0.0.6a.R15
Proxy:          Conduit 1.8.0
```

Major R15 work includes:

### WebUI drawer behavior

Implemented requested sidebar behavior:

- click outside closes the drawer;
- Escape closes the drawer;
- slide animation retained;
- explicit close button retained.

### Migration-aware graceful-stop design

R15 extends the concept of worker stop so an active physical migration can outlive the initial short graceful-stop window instead of being immediately killed at the old 10-second deadline.

This still needs final live acceptance under a successful seamless migration.

### Node R7 dataplane/persistence cleanup

R7 builds on the R6 BlockEntity work with goals including:

- local fencing of stale authority writes;
- less repeated HTTP 409 spam;
- duplicate replica suppression;
- reduced `changedBlocks=0` INFO noise;
- preservation of the improved BlockEntity write behavior.

Recent R15 logs show a dramatic improvement compared with the previous tens-of-thousands-of-commits flood: only a small initial set of BlockEntity commits appears during normal startup/gameplay in the test window.

### Foundations Conduit R15 bridge

R15 includes a patched/overlaid Conduit seamless configuration handler intended to make NeoForge seamless physical switching possible.

The bridge loads and Conduit boots, but **the physical transfer still stalls during destination configuration and times out**. Therefore the bridge is not yet considered complete.

### Gateway config corruption hotfix

A severe `velocity/conduit.toml` corruption was discovered.

Observed reported size:

```text
27,451,269,029 bytes
```

That caused Conduit to crash in `Files.readString()` / `ConduitConfigMigrator` with:

```text
OutOfMemoryError: Required array size too large
```

Two same-version R15 gateway-config hotfixes were created.

Final repair behavior:

- validate the config size before Conduit reads it;
- stop only relevant Foundations Manager/Gateway processes if necessary;
- wait for Windows file locks to release;
- quarantine the corrupt TOML instead of attempting to copy it;
- recreate a small sane config;
- preserve workers/world/runtime state;
- validate Java 25 and the R15 proxy bridge.

After repair, Conduit boots successfully and stays up.

---

# 9. Current Proven Runtime State

Based on the latest R15 test logs, the following are proven:

## Manager

- R15 starts successfully.
- Security vault loads.
- stored workers are discovered.
- world seed and world identity load.
- Layer Planner initializes.
- Region Authority initializes/restores.
- player state/profile stores initialize.
- persistent block and BlockEntity journals load.
- simulation data plane initializes.
- WebUI/API binds to `127.0.0.1:8765`.
- Manager reaches READY.

## Workers

- stored workers can be reused/restarted;
- worker agents register READY;
- region authority is restored;
- new workers can be restarted/spawned;
- R7-era BlockEntity persistence behavior is much quieter than the earlier R5 flood.

## Gateway

After the conduit.toml repair:

- Java 25 validates;
- Conduit 1.8.0 boots;
- `max-known-packs` override is accepted;
- handshake cache is enabled;
- NeoForge compatibility is enabled;
- legacy Forge compatibility is enabled;
- seamless server switching is enabled;
- the R15 Foundations Gateway plugin loads;
- the R15 bridge announces itself;
- the gateway binds `0.0.0.0:25565`;
- a normal player can connect to the initial worker.

## Real physical transfer pipeline

The Manager successfully performs:

```text
request physical transfer
capture source profile
preload destination
destination reports ready
capture final source profile
prepare destination profile
arm proxy switch
```

The audit records the transfer as `physical=true`.

The unresolved failure occurs after the proxy opens the destination backend connection.

---

# 10. Current Main Blocker — NeoForge Seamless Configuration

This is the most important active issue.

Observed flow:

```text
Player on worker-002
        |
        v
Physical transfer requested
        |
        v
Source snapshot succeeds
        |
        v
Destination preload succeeds
        |
        v
Final source snapshot succeeds
        |
        v
Velocity / Conduit switch armed
        |
        v
Conduit opens worker-003 connection
        |
        v
Destination enters NeoForge configuration/login work
        |
        v
~6 second stall
        |
        v
read timeout
        |
        v
Manager aborts transition
```

Latest observed gateway behavior:

```text
Shnewbs -> worker-003 has connected
~6 seconds
read timed out
worker-003 disconnected
```

Latest Manager behavior:

```text
Backend transfer handoff final source snapshot ... switch armed
...
Transition ABORTED ... Velocity backend switch rejected
```

What this means:

- source profile capture is not the blocker;
- destination preload is not the blocker;
- Manager physical transfer state is not the blocker;
- basic destination TCP/backend connection is not the blocker;
- RAM/OOM was not indicated in this transfer test;
- the remaining issue is inside the destination login/configuration negotiation path.

## 10.1 Next debugging step

Do not blindly rewrite the handoff coordinator again.

Instead instrument the exact configuration exchange.

We need to trace:

- connection state transitions;
- configuration task start/finish;
- custom payload channel IDs;
- NeoForge REGISTER / channel negotiation;
- Proxy Compatible Forge messages;
- registry/known-pack negotiation;
- configuration finish/ack packets;
- any packet suppressed by seamless mode;
- which side is waiting at the timeout;
- whether the destination is waiting on the client, proxy, or an expected modded response.

The target result is a specific statement such as:

```text
Destination sent task X / payload Y
Foundations/Conduit did Z
Expected response W never arrived
```

Only after that should the bridge behavior be changed.

## 10.2 Required fallback

If a destination handshake is incompatible with seamless bridging, the system should have a safe policy:

```text
seamless physical switch if compatible
otherwise
normal physical switch with visible reconfiguration
```

The system must never silently revert to R8-style logical-only migration simply to hide the reconfiguration screen.

---

# 11. Current Secondary Issues

## 11.1 Intentional shutdown incorrectly triggers emergency failover

During a deliberate full cluster shutdown, the gateway sees backend loss and emergency logic begins classifying workers as unhealthy/crashed and trying to redirect/fail over.

Desired behavior:

```text
clusterShuttingDown = true
```

When true:

- suppress emergency backend-loss quarantine;
- suppress emergency session failover;
- suppress backend-loss redirect;
- suppress crash classification for expected exit code 0;
- suppress noisy region SUSPECT transitions caused only by intentional shutdown;
- still capture final player state where safe;
- still stop gateway cleanly after workers.

This should apply to both WebUI/desktop “full cluster shutdown” and shutdown initiated from the launcher.

## 11.2 Java 21 JDK build-tool discovery

`build_all.bat` now reaches a much more accurate failure:

```text
jar.exe was not found. A Java 21 JDK is required to build the Manager.
```

The machine currently finds a Java 21 launcher/runtime path, but the build needs a full JDK containing:

```text
bin/java.exe
bin/javac.exe
bin/jar.exe
```

Desired discovery order:

```text
FOUNDATIONS_JAVA21_HOME
JAVA_HOME
Program Files\Java\jdk-21*
Program Files\Eclipse Adoptium\jdk-21*
Program Files\Microsoft\jdk-21*
PATH only if java + javac + jar all resolve together
```

Preferred long-term behavior:

- detect a proper Java 21 JDK automatically;
- if missing, optionally provision an isolated JDK 21 under the Foundations runtime/tooling directory;
- never replace the gateway Java 25 runtime;
- clearly print which JDK is being used;
- fail with a useful actionable message rather than closing the window.

## 11.3 Graceful stop needs final successful-migration validation

Earlier revisions aborted migrations at a fixed 10-second worker-stop deadline.

R15 introduced migration-aware logic, but the feature cannot be considered fully proven until the underlying seamless transfer itself succeeds.

Acceptance:

- worker enters DRAINING;
- authority is evacuated;
- player handoff starts;
- if handoff is actively progressing, source remains alive beyond the short stop grace;
- handoff commits;
- source worker stops only after zero players remain;
- hard maximum timeout still exists to prevent permanent hangs.

## 11.4 Gateway fallback behavior during intentional full shutdown

Conduit’s own fallback router may attempt to bounce a player between workers while both workers are intentionally stopping.

During cluster shutdown, gateway fallback should be disabled or given explicit shutdown awareness so it does not attempt:

```text
worker-002 -> worker-003 -> worker-002 -> worker-003
```

when the entire cluster is intentionally going offline.

---

# 12. BlockEntity Persistence — History and Current Direction

This area produced one of the largest performance problems during testing.

Earlier behavior scanned dynamic BlockEntities frequently and published enormous numbers of journal commits.

Examples repeatedly involved:

- chests;
- mob spawners;
- dispensers;
- beehives;
- vaults;
- trial spawners.

At one point the journal increased by tens of thousands of revisions within minutes.

Fix evolution:

1. store block state with BlockEntity NBT;
2. introduce coalescing;
3. increase scan interval;
4. reduce scan budget;
5. track last persisted time;
6. prevent max-age logic from republishing constantly changing entities every few seconds;
7. avoid force-publishing every entity simply because a chunk unloads;
8. preserve lifecycle flush for actual pending mutations;
9. reduce Manager summary logging frequency;
10. add Node-side duplicate/authority controls.

Current recent R15 logs show only a small number of commits during the observed test period, which is a major improvement.

Still required before final signoff:

- longer soak test;
- redstone-heavy area;
- hopper/dispenser test;
- spawner/trial chamber test;
- beehive test;
- chunk load/unload loop;
- worker restart;
- region authority change;
- verify no legitimate BlockEntity state is lost because coalescing became too aggressive.

---

# 13. Region Authority

Current concepts:

```text
region key
owner worker
lease
state
epoch
```

Goals already partially implemented:

- one authoritative owner at a time;
- epoch changes on ownership transfer/failover;
- startup recovery grace;
- ACTIVE/SUSPECT/failover transitions;
- worker evacuation transfers authority away;
- control-plane fencing;
- Node-side stale-authority write suppression.

Long-term improvements:

- stronger data-plane write fencing;
- explicit region snapshot ownership transfer;
- persistent transfer journal;
- proof that old owners cannot write after epoch loss;
- multi-host storage support;
- better UI visualization of region ownership and epoch history.

---

# 14. Layers and Population Phasing

The long-term layering model is inspired by large MMO population layering while preserving Minecraft persistence.

Desired behavior:

- multiple population layers can exist for the same world/cell;
- player-built bases remain visible regardless of population layer;
- moving across a layer boundary should be seamless;
- layers may split temporary population/simulation;
- layers must never duplicate authoritative persistent builds;
- layer creation/merge should react to capacity;
- admins retain manual controls.

Current pieces already present:

- Layer Planner;
- layer IDs;
- cells;
- worker placement;
- layer create/freeze/drain states;
- placement reasoning;
- layer-based transfer targets.

Future work:

- robust split/merge acceptance;
- affinity/stickiness so groups do not bounce unnecessarily;
- party/guild/group-aware placement hooks;
- safe temporary entity ownership model;
- layer population targets;
- chunk/cell pressure metrics;
- cross-machine placement.

---

# 15. Player Session Model

Current persistent session ideas distinguish:

- **anchor worker:** physical network/backend connection;
- **simulation worker:** intended simulation location;
- **layer:** population/simulation placement;
- **region authority:** persistent-world owner.

Important lesson from R8:

> Anchor and simulation must not be confused when proving physical migration.

A true physical migration test should prove:

```text
before:
anchor = worker-A
simulation = worker-A

after:
anchor = worker-B
simulation = worker-B
```

and the proxy/backend logs should independently confirm the player moved.

---

# 16. Player Profile Handoff

The working handoff pattern is:

```text
1. request transfer
2. capture source profile/snapshot
3. preload destination
4. destination reports ready
5. request final source snapshot
6. capture final source profile
7. prepare destination profile
8. arm physical proxy switch
9. destination completes login/configuration
10. commit handoff
11. update anchor + simulation
12. release/stop source when appropriate
```

Failure rules:

- never commit before destination is actually usable;
- abort safely if destination rejects the player;
- preserve source session until commit whenever possible;
- use idempotent handoff IDs;
- avoid duplicate concurrent handoffs for the same player;
- preserve enough profile history for recovery.

---

# 17. Emergency Failover

Emergency failover is separate from graceful migration.

It exists for cases such as:

- worker process crash;
- connection reset;
- sudden JVM death;
- host loss;
- backend health failure.

Current groundwork includes:

- backend-loss reports;
- quarantine;
- emergency session placement;
- profile preparation on a surviving worker;
- gateway emergency redirect;
- region failover.

Still needed:

- stronger debouncing of repeated backend-loss reports;
- shutdown suppression;
- clearer distinction between CRASHED, KILLED, OFFLINE, DRAINED, and intentionally STOPPED;
- configurable retry limits;
- no redirect loops;
- better player-facing failure messages;
- eventual warm-spare preference.

---

# 18. Worker Lifecycle

Desired worker states include concepts such as:

```text
OFFLINE
STARTING
READY
ACTIVE
DRAINING
DRAINED
UNHEALTHY
CRASHED
KILLED
STOPPING
```

Administrator actions:

- Start;
- Restart;
- Drain;
- Undrain;
- Graceful Stop;
- Force Stop / Kill;
- Reserve capacity;
- inspect logs;
- inspect assigned layers/regions/players.

Rules:

- Graceful Stop must attempt evacuation first.
- Force Stop is explicitly allowed to disconnect players.
- intentional exit code 0 should not be reported as a crash.
- worker port assignments should remain stable where practical.
- worker directories should remain reusable after restart.

---

# 19. Scaling Modes

The design keeps human oversight even when automation is available.

## MANUAL

- automation does not create/remove workers on its own;
- admin can start/stop workers;
- monitoring and recommendations still run.

## ASSISTED

- Cluster proposes actions;
- admin approves them;
- examples: “Start another worker”, “Drain worker-004”, “Split layer”.

## AUTOMATIC

- Cluster may scale within configured limits;
- manual controls remain available;
- all actions are audited;
- limits prevent runaway worker creation.

Target scaling signals:

- TPS/MSPT;
- CPU;
- worker heap;
- host available RAM;
- GC pressure;
- active players;
- chunk count;
- entity count;
- layer population;
- network throughput/latency;
- disk/region transfer pressure.

---

# 20. RAM / Capacity Policy

A worker merely being able to boot is not enough to make it a good migration target.

Destination selection should reject or deprioritize a worker when:

```text
heap usage is too high
host available RAM is too low
GC pressure is excessive
TPS/MSPT health is bad
worker is draining/unhealthy
required destination region/layer is not ready
```

A sensible target policy is to preserve migration headroom rather than filling every worker to Xmx.

Possible initial thresholds:

```text
avoid migration target above ~80–85% heap
reserve host RAM for Manager + gateway + OS + burst
allow configurable hard/soft worker memory limits
```

The exact values should be config-driven and tested with the actual modpack.

---

# 21. WebUI Target State

The WebUI should become the primary operational dashboard.

## Dashboard

Show:

- Cluster version/status;
- operating mode;
- active workers;
- players;
- gateway health;
- alerts;
- average/max MSPT;
- memory pressure;
- region/layer state;
- scaling state.

## Workers

Per worker:

- ID;
- PID;
- port;
- state;
- uptime;
- heap used/max;
- process RSS;
- CPU;
- TPS/MSPT;
- player count;
- loaded chunks;
- region count;
- layer count;
- last heartbeat;
- actions.

## World Map

Future map should show:

- region ownership;
- worker colors/labels;
- active player locations at an appropriate privacy level;
- layer/cell boundaries;
- failed/suspect regions;
- transfer history.

## Layers

Show:

- layer ID;
- world/dimension;
- cell;
- slot;
- worker;
- population;
- state;
- creation reason;
- controls for freeze/drain/merge where safe.

## Regions

Show:

- region key;
- authority worker;
- epoch;
- lease health;
- state;
- transfer action;
- transfer/failover history.

## Players

Show:

- name;
- current anchor;
- current simulation worker;
- layer;
- region;
- session status;
- physical transfer command;
- diagnostic history.

Raw UUID data should not be unnecessarily exposed to ordinary players. Admin-only views may use it where operationally necessary.

## Gateway

Show:

- bind address;
- process PID;
- ownership/adoption;
- Java runtime;
- Conduit version;
- Gateway plugin version;
- forwarding mode;
- backend topology;
- seamless-switch status;
- last proxy error;
- health.

## Scaling

Show:

- mode;
- thresholds;
- recommendations;
- recent scale events;
- manual spin-up;
- capacity reservation.

## Logs

Unified searchable view for:

- Manager;
- Gateway;
- workers;
- bootstrap/build logs;
- desktop errors.

## Alerts

Examples:

- worker high MSPT;
- low host RAM;
- gateway down;
- region authority suspect;
- worker crash;
- repeated handoff aborts;
- persistent journal growth anomaly;
- config corruption;
- duplicate active JARs;
- version drift.

## Settings

Should clearly distinguish:

- live reloadable;
- restart Manager required;
- restart Gateway required;
- restart workers required.

## Audit

Every destructive or topology-changing admin action should be recorded.

---

# 22. Logging and Diagnostics

Central logs should remain one of the strongest features of the runtime.

Desired sources:

```text
cluster-manager.log
manager-stdout.log
manager-stderr.log
velocity.log
velocity-bootstrap.log
worker logs
audit.log
desktop-stderr.log
build-all.log
template-bootstrap.log
```

Future diagnostic bundle command/button should gather:

- current config with secrets redacted;
- component versions;
- Java versions;
- process list;
- port ownership;
- gateway status;
- worker status;
- recent logs;
- audit tail;
- region/layer/session summaries;
- disk free space;
- host memory;
- checksums of active binaries;
- duplicate-JAR scan;
- no raw secret vault contents.

---

# 23. Security Requirements

The runtime currently uses encrypted internal security state and internal API secrets.

Long-term requirements:

- bind admin WebUI locally by default;
- explicit configuration to expose remotely;
- TLS/reverse-proxy guidance for remote operation;
- role-based admin access;
- no secrets in browser responses;
- no blockchain/private keys on clients for future Economy bridges;
- narrow service tokens;
- audit all admin mutations;
- protect worker registration;
- protect cross-host control plane;
- redact sensitive data from diagnostic bundles.

---

# 24. Storage Strategy — Long-Term

Standalone/local mode may use local filesystem state where appropriate.

Distributed mode will need stronger shared storage abstractions.

Potential architecture:

## SQL / PostgreSQL

Good for:

- Economy ledger;
- market state;
- durable metadata;
- admin/audit data;
- session metadata;
- ownership records when transactional semantics are useful.

## Redis or equivalent fast shared state

Potentially useful for:

- transient coordination;
- fast session lookup;
- distributed locks/leases where appropriate;
- pub/sub;
- cache;
- presence.

## World / Region Storage

Persistent Minecraft world data must be handled carefully.

Potential approaches include:

- authoritative region ownership with transfer snapshots;
- shared high-performance storage with strong fencing;
- asynchronous replicas plus one writer;
- explicit install/commit protocol;
- checksummed transfer manifests.

The project already contains reference region-transfer/snapshot concepts that can be developed further.

---

# 25. Build and Tooling Requirements

The project should be buildable on Windows without fragile shell assumptions.

Required toolchain separation:

```text
Java 21 JDK:
- Manager compilation
- Node / NeoForge compilation
- Gateway plugin compilation if API-compatible

Java 25 JRE/JDK:
- Conduit runtime
```

Build scripts should:

- detect correct JDKs;
- preserve spaces/backslashes in Windows paths;
- use argfiles safely;
- show errors instead of closing instantly;
- write `build-all.log`;
- fail-fast per component;
- verify produced JAR metadata/version;
- optionally install built Node/Gateway into the active dev Cluster only when explicitly requested.

`BUILD_ALL_VISIBLE.bat` should remain available for double-click debugging.

---

# 26. Updater / Repair Standards

Every revision updater should:

1. Prompt for Cluster root.
2. Verify the actual installed baseline from built artifacts.
3. Refuse unknown baselines unless explicitly a repair package.
4. Require relevant processes/ports to be stopped.
5. Back up every file it changes.
6. Preserve config/operator changes.
7. Preserve worlds/runtime/profiles/security/journals.
8. Replace Node in template and existing workers when Node changes.
9. Remove stale duplicate Foundations plugin JARs.
10. Validate output versions.
11. Roll back if the update fails.
12. Leave the backup path clearly printed.
13. Detect partial/mixed-state installs rather than silently reporting “already updated”.
14. Prefer transactional replacement for gateway/proxy/config changes.

Special lessons from R14/R15:

- rollback must include all pieces changed by bootstrap scripts;
- config files need sanity-size checks;
- do not copy a gigantic/corrupt config into backup if a same-volume move/quarantine is safer;
- a “process stopped” check should consider both ports and matching command lines;
- Windows file locks must be handled explicitly.

---

# 27. Maintenance Utilities Already Added

## Cluster Doctor

Purpose:

- audit only by default;
- report active versions;
- report directory sizes;
- report duplicates/drift;
- report old build artifacts;
- identify cleanup opportunities without modifying state.

## Deep Clean

Purpose:

- safe cleanup;
- quarantine instead of permanent deletion;
- remove old reports/logs/build artifacts when approved;
- preserve state;
- normalize active artifacts where explicitly requested.

## Mixed-State Repair

Purpose:

- normalize partial R13/R14 installs;
- repair Manager/Node/Gateway version mismatch;
- remove stale plugin artifacts;
- repair build/start scripts.

## Gateway Config Hotfix

Purpose:

- repair corrupt/oversized `conduit.toml`;
- handle locked files safely;
- preserve runtime state;
- recreate known-good R15 proxy config.

---

# 28. Intentional Shutdown Design

Desired full shutdown sequence:

```text
1. Manager enters SHUTTING_DOWN state
2. stop accepting new scale/migration/admin topology actions
3. disable emergency failover classification
4. optionally capture final player state
5. stop/drain workers in controlled order
6. do not bounce players among workers that are all shutting down
7. mark expected exits as STOPPED, not CRASHED
8. stop gateway
9. flush journals/audit
10. stop Manager
```

On next startup:

- stored workers may be rediscovered;
- region authority uses startup recovery grace;
- stale state should recover without being interpreted as an emergency from the previous intentional shutdown.

---

# 29. Seamless Migration Acceptance Definition

A migration is only accepted as **physical and seamless** when all of the following are true:

- source player is on worker A;
- destination worker B is READY;
- destination is eligible by RAM/health/capacity;
- source snapshot is captured;
- destination preload succeeds;
- final source snapshot succeeds;
- proxy opens the destination;
- destination completes NeoForge configuration/login;
- proxy reports successful backend switch;
- Manager commits handoff;
- anchor changes A → B;
- simulation changes A → B;
- player remains connected;
- player state/inventory/position are correct;
- no duplicate player session exists;
- no source write occurs after authority/ownership loss;
- client does not display the Reconfiguring screen for a compatible seamless path;
- no emergency failover is triggered by the normal switch.

If seamless compatibility cannot be achieved for a specific case, the accepted fallback is:

> real physical migration with normal Minecraft reconfiguration — never a fake logical-only move.

---

# 30. Test Matrix Before Leaving 0.0.6a

## Startup

- [ ] fresh startup from stopped Cluster;
- [ ] stored worker reuse;
- [ ] gateway starts on Java 25;
- [ ] workers remain on Java 21;
- [ ] no duplicate active Foundations JARs;
- [ ] no giant/corrupt Conduit config;
- [ ] all expected versions match.

## Normal Join

- [ ] player joins gateway;
- [ ] placement chooses valid worker/layer;
- [ ] profile preparation succeeds;
- [ ] player reaches joined-game state;
- [ ] Manager session matches proxy backend.

## Physical Transfer

- [ ] worker A → B;
- [ ] worker B → A;
- [ ] repeated transfers;
- [ ] inventory preserved;
- [ ] position preserved;
- [ ] advancements/effects/etc. preserved where expected;
- [ ] anchor + simulation both move;
- [ ] no duplicate login/session;
- [ ] seamless path produces no Reconfiguring screen.

## Graceful Stop

- [ ] drain source worker;
- [ ] evacuate authority;
- [ ] migrate player;
- [ ] source remains alive while migration progresses;
- [ ] source stops after successful handoff;
- [ ] no unnecessary emergency logic.

## Crash Failover

- [ ] hard-kill worker with player;
- [ ] detect backend loss;
- [ ] quarantine failed worker once, not repeatedly;
- [ ] choose healthy destination;
- [ ] restore profile;
- [ ] redirect or give controlled disconnect if impossible;
- [ ] region authority fails over safely.

## Intentional Cluster Shutdown

- [ ] no emergency failover storm;
- [ ] no fallback bounce loop;
- [ ] workers exiting 0 classified expected;
- [ ] gateway stops after workers;
- [ ] Manager exits cleanly;
- [ ] next startup recovers authority cleanly.

## Persistence

- [ ] placed block survives worker restart;
- [ ] chest survives;
- [ ] BlockEntity state survives;
- [ ] chunk unload does not produce commit flood;
- [ ] dynamic BlockEntity soak does not produce commit flood;
- [ ] region ownership switch preserves persistent state;
- [ ] stale worker cannot overwrite new owner.

## Data Plane

- [ ] no sustained 409 spam during stable authority;
- [ ] stale publisher fences itself;
- [ ] duplicate replica frames are coalesced;
- [ ] zero-change replicas do not flood INFO log;
- [ ] meaningful changes still replicate.

## WebUI

- [ ] all pages clickable;
- [ ] side drawer opens;
- [ ] outside click closes side drawer;
- [ ] Escape closes side drawer;
- [ ] Test Lab buttons work;
- [ ] version/status refresh works;
- [ ] actions produce audit entries;
- [ ] no stale JS cache after hard refresh.

## Desktop

- [ ] Safe Suite works;
- [ ] Full Suite works;
- [ ] no “Signal source has been deleted” exception;
- [ ] Manager timeout is reported without crashing;
- [ ] close/reopen remains stable.

## Build

- [ ] Java 21 JDK auto-detected or provisioned;
- [ ] `build_all.bat` succeeds;
- [ ] Manager JAR created;
- [ ] Node JAR created;
- [ ] Gateway JAR created;
- [ ] correct versions embedded;
- [ ] visible build BAT remains open on error.

## Update / Rollback

- [ ] updater from previous accepted baseline;
- [ ] correct backup created;
- [ ] partial failure rolls back every changed component;
- [ ] no mixed-state Manager/Node/Gateway remains;
- [ ] re-running updater on complete target validates instead of blindly no-oping;
- [ ] worlds/profiles/security/journals untouched.

---

# 31. Performance Philosophy

The cluster should scale because it improves the server, not because each worker is allowed to become unhealthy.

Principles:

- keep worker heaps reasonable;
- prefer multiple healthy workers over one enormous GC-heavy heap;
- reserve memory for transitions;
- avoid excessive polling/scanning;
- make persistence mutation-driven where possible;
- coalesce dynamic state;
- keep monitoring overhead small;
- avoid INFO-level spam in hot loops;
- collect enough diagnostics to explain failures without turning logging into the failure.

---

# 32. Future Distributed-System Work

After local-cluster semantics are stable, the next large architectural steps include:

## Host Nodes

- node registration;
- host health;
- host resource inventory;
- worker placement by host;
- remote process control;
- secure transport;
- host draining.

## Shared Storage

- PostgreSQL-backed metadata/economy services;
- optional Redis-style transient coordination;
- region snapshot/object storage strategy;
- checksummed transfer manifests;
- fencing across hosts.

## Cross-Machine Networking

- advertise worker endpoints safely;
- internal-only backend ports;
- secure control-plane networking;
- network latency awareness;
- gateway-to-worker topology updates.

## Higher Availability

Long-term possibilities:

- standby Manager/control-plane recovery;
- shared persistent orchestration state;
- redundant gateway instances;
- stable public endpoint/load balancer;
- transfer-pack/cookie secret persistence across proxies;
- no single proxy process as the permanent single point of failure.

---

# 33. Economy Integration Boundary

Foundations Economy remains independent from orchestration.

Cluster can expose Economy operational data, but Economy should use Framework/service abstractions rather than Cluster internals.

Desired separation:

```text
Foundations Cluster
- worker/process/topology
- sessions
- regions/layers
- runtime persistence coordination

Foundations Framework
- stable service APIs
- registries/events/UI hooks

Foundations Economy
- wallet
- ledger
- escrow
- transactions
- markets hooks
```

Distributed Economy storage can later use shared database providers without making Economy responsible for worker lifecycle.

---

# 34. Administration / Human Oversight

Even in automatic mode, administrators should always be able to:

- see why a worker was started;
- see why a player was placed somewhere;
- see why a region moved;
- see why a layer split;
- manually start a worker;
- reserve capacity;
- drain a worker;
- transfer a region;
- physically transfer a player for testing;
- freeze/unfreeze layers where safe;
- inspect logs;
- override scaling mode;
- shut down the Cluster.

Every topology mutation should be auditable.

---

# 35. Recommended Immediate Work Order

The next work should be deliberately narrow.

## Priority 1 — Instrument NeoForge destination configuration

Add detailed proxy-side and worker-side tracing for a single physical handoff.

Need timestamps for:

- switch request;
- backend TCP connect;
- forwarding handshake;
- configuration state entry;
- each custom payload/channel negotiation step;
- configuration task list;
- task acknowledgements;
- finish-configuration packets;
- login/join transition;
- read-timeout timer.

Goal: identify the exact unanswered message/task.

## Priority 2 — Fix the seamless bridge from evidence

Once the exact waiting task is known:

- proxy/bridge only the required NeoForge negotiation;
- reuse safe cached initial-handshake data when valid;
- do not fabricate registry state blindly;
- prove both directions;
- maintain fallback to normal physical switch.

## Priority 3 — Add global intentional-shutdown gate

Prevent emergency systems and Conduit fallback from treating planned shutdown as a crash storm.

## Priority 4 — Fix Java 21 build toolchain

Make build scripts reliably locate or install a full JDK 21.

## Priority 5 — Re-run complete acceptance suite

Do not add unrelated features until:

- gateway stable;
- transfer stable;
- shutdown stable;
- persistence stable;
- build stable.

---

# 36. What We Should Not Do Right Now

Until the current networking failure is understood:

- do not rewrite the entire handoff coordinator;
- do not regress to logical-only R8 behavior;
- do not add large unrelated scaling features;
- do not change persistent world semantics without a reproducible reason;
- do not keep stacking proxy patches without packet-level evidence;
- do not declare seamless solved just because the Manager reaches COMMIT/PROXY_READY;
- do not consider a backend TCP connection sufficient proof of successful transfer.

---

# 37. Criteria to Close 0.0.6a

`0.0.6a` can be considered complete when the following are true:

1. Manager/Node/Gateway versions are normalized and build reproducibly.
2. Gateway boots reliably after restart/update.
3. BlockEntity persistence remains bounded under real gameplay.
4. Region authority survives restart/failover without duplicate writers.
5. Normal physical transfer succeeds both directions.
6. Seamless physical transfer succeeds on compatible NeoForge workers without client Reconfiguring.
7. Incompatible transfers safely fall back to real normal physical transfer.
8. Graceful stop successfully migrates players before worker exit.
9. hard crash emergency failover behaves predictably.
10. intentional full shutdown does not trigger emergency-failover storms.
11. WebUI and Desktop tests are clickable/stable.
12. `build_all.bat` works on a clean Windows environment with a discoverable/provisioned JDK 21.
13. updater rollback cannot leave a mixed version state.
14. Safe Suite and Full Suite pass against the release candidate.
15. source package + portable updater + release notes are generated from the same validated code.

Only then should the project advance to the next base alpha and expand scope again.

---

# 38. Post-0.0.6a Roadmap

Once local multi-JVM semantics are stable, development can expand toward the original full architecture.

## Phase A — Production-quality local cluster

- complete local physical/seamless migration;
- final persistence fencing;
- production build/update tooling;
- resource-aware worker placement;
- warm-spare support;
- better diagnostic bundles;
- admin roles.

## Phase B — Automatic local scaling

- automatic worker spin-up/down;
- load-based layer split/merge;
- capacity reserve;
- predictive warm worker;
- hysteresis/cooldown controls;
- safe automatic drains.

## Phase C — Multi-host workers

- remote node agent;
- host metrics;
- secure worker launch;
- distributed topology;
- shared persistence services;
- network-aware scheduling.

## Phase D — Full dynamic layering

- high-population chunk/cell layering;
- affinity/group placement;
- base persistence across layers;
- seamless boundary crossing;
- temporary entity layer semantics;
- layer merge/split at scale.

## Phase E — Production region transfer

- explicit region snapshots;
- checksums/manifests;
- one-writer fencing;
- transfer journal;
- recovery after interrupted transfer;
- remote storage/install endpoints.

## Phase F — High availability

- redundant proxy design;
- durable proxy secrets/session support;
- control-plane recovery;
- node loss recovery;
- multi-machine failover testing.

---

# 39. Definition of Success

The Foundations Cluster Runtime is successful when a server owner can:

1. extract/install the server package;
2. run one Windows launcher;
3. have the Manager start the required services;
4. join one public Minecraft address;
5. scale workers manually or automatically;
6. see all workers/regions/layers/players in one dashboard;
7. move players and regions without corrupting the world;
8. restart/drain workers without unnecessary disconnects;
9. survive worker crashes with controlled recovery;
10. expand from one physical server to multiple hosts without redesigning the modpack;
11. keep Economy and other Foundations modules modular;
12. preserve one coherent persistent Minecraft world from the player's perspective.

The high-end goal is:

> **A player experiences Foundations as one persistent Minecraft server while the runtime invisibly manages many JVMs, layers, regions, and eventually physical machines behind it.**

---

# 40. Current Snapshot — Short Form

As of the end of the latest R15 test cycle:

```text
Manager                R15          WORKING
Cluster Node            R7           WORKING
Workers                              WORKING
WebUI                                WORKING / drawer polish added
Desktop/TestLab                     substantially repaired
Region authority                     WORKING, further fencing tests needed
Layers                               WORKING groundwork
Player profile snapshots             WORKING
Physical handoff preparation         WORKING
Physical backend connect             WORKING
Seamless NeoForge completion         BLOCKED by ~6s config/read timeout
Conduit 1.8.0                        WORKING after config repair
Gateway Java 25                      WORKING
Minecraft Java 21                    WORKING
BlockEntity flood                    MAJORLY IMPROVED
Intentional shutdown handling        NEEDS GLOBAL SUPPRESSION GATE
build_all.bat                        BLOCKED by Java 21 JDK/jar.exe discovery
Updater/repair tooling               MUCH IMPROVED, continue transactional hardening
```

The project should now stay focused on the remaining narrow blockers rather than expanding feature scope until this line passes acceptance.

---

# 41. Living Document Rule

This file should be updated at every meaningful revision with:

- version/component matrix;
- implemented changes;
- bugs discovered;
- bugs closed;
- acceptance results;
- changed architecture decisions;
- new migration/persistence invariants;
- remaining blockers;
- exact next-revision scope.

That makes this document the master handoff/reference when development moves between chats, machines, or future maintainers.
