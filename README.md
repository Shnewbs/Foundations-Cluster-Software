# Current source candidate: 0.0.6a.R19-HF11 / Node R13

This source archive includes the compiled HF11 Manager and compiled Node R13.
Gateway R19/Conduit are unchanged. For an existing installation, use the separate
HF11 updater; do not overwrite your installed configuration or runtime folders.
Personal worlds, journals, keys, worker instances and gateway state are excluded.
See `docs/R19_HF11_NATURAL_GROWTH_VALIDATION.md` for the reproduced growth bug,
executed tests, bounded simulation scope and installation instructions.

The earlier README below is retained for historical/background guidance; its
version-specific statements do not supersede the HF11 validation report.

---

# Foundations Cluster Runtime — 0.0.6a.R19-HF10

**HF9 (September 28 UTC):** fixes a worker returning **DRAINED** after a separate graceful Stop → Restart. Temporary stop fencing is now distinct from manual drain policy; manual drain/maintenance settings stay intact. Restart-test errors show the actual worker state. Your HF8 logs confirm identical inventory samples, 17-diamond chest continuity, generated-tree hashes and three committed player transfers. Node R11 and the gateway remain unchanged. See `docs/R19_HF9_VALIDATION.md`.


**HF8 (September 28 UTC):** ships **Node R11**, automatic support ZIPs and fixes for the saved-fire restore blocker and chunk coordinate fencing. Open **Diagnostics → Support ZIPs → Create & Download Latest**, or enable captures on failed Test Lab checks / a schedule. Archives stay in `support-bundles/` with configurable retention and size limits; nothing uploads automatically. Full offline backups now include logs. Real two-worker NeoForge tests passed saved-fire hydration, chest transfer, generated-tree state round-trip, acknowledged creeper damage after a hard kill, time/weather failover and Manager restart. Client-visible seamlessness and full replica simulation remain unproven. See `docs/R19_HF8_VALIDATION.md`.


**HF7 (September 28 UTC):** ships **Node R10**, per-region destination restoration acknowledgements, stale-replay protection, plant-safe snapshot reconstruction, and forced-save/coalesced-NBT ordering fixes. The chest test verifies the exact stack and reports the worker/stage/response. A new Generated Tree test compares all 6,144 block states across an authority round-trip. Your latest run passed physical migration and inventory continuity; chest/tree and visible seamlessness still need live validation. Non-authoritative replica growth remains incomplete. See `docs/R19_HF7_VALIDATION.md`.

**HF10 (September 28 UTC):** closes the observed natural-world replica freshness gap without changing the accepted HF9 seamless gateway path. Durable authoritative block and BlockEntity commits now coalesce and fan out fenced `CHUNK_INVALIDATE` frames to active remote anchors. Node R12 clears replica freshness metadata whenever its simulation route token changes, forcing the new anchor/epoch to request authoritative chunk state rather than inheriting a stale TTL. Existing LevelChunk mutation capture remains the source for random-tick growth, fluids, fire, explosions, commands and mod block writes. Manager R19 and Gateway R19/Conduit are otherwise unchanged. See `docs/R19_HF10_VALIDATION.md`.

**HF6 (September 28 UTC):** compares NeoForge data-map declarations by registry/map ID while retaining mandatory flags. Also corrects unordered enum-class and feature-flag declaration comparisons; enum member order stays exact. Rejections name actual declaration differences. Startup prints `package 0.0.6a.R19-HF6`. See `docs/R19_HF6_VALIDATION.md`. Live-client seamless acceptance remains required.

**HF5 (September 28 UTC):** compares registry/tag maps by name, preserving every tag member ID and its sequence. Real tag differences now identify missing/added names or the first differing member. HF4 live logs passed all 30 frozen snapshots and then rejected tags; the precise tag difference remains unknown. Startup prints `package 0.0.6a.R19-HF5`. See `docs/R19_HF5_VALIDATION.md`. Live-client seamless acceptance remains required.

**HF4 (September 27):** compares frozen-registry manifests as sets and snapshots by registry name, retaining exact snapshot bytes and rejecting real ID/alias/membership differences. Missing/added registries now appear in errors. Startup prints `package 0.0.6a.R19-HF4`. See `docs/R19_HF4_VALIDATION.md`. Live-client acceptance remains required.

**HF3 (September 27):** answers the NeoForge CONFIG ping that HF2 swallowed, blocking the handshake after network negotiation. Test Lab now reports the specific handoff abort immediately. Startup prints `package 0.0.6a.R19-HF3`. See `docs/R19_HF3_VALIDATION.md` for reproduction, tests, installation and remaining issues. Live-client seamless acceptance remains required.

**HF2 (September 27):** fixes Manager startup selecting cached Node R8 and replacing the updater-installed R9. Worker synchronization now selects the expected version; old workers cannot pass Save Health. Startup prints `package 0.0.6a.R19-HF2`. See `docs/R19_HF2_VALIDATION.md`.

**HF1 (September 27):** fixes R19 draining registry packets during login capture. Install this package even if seamless mode is disabled. Component JAR versions remain R19/R9/R19. See `docs/R19_HF1_VALIDATION.md`.

Minecraft 1.21.1 · NeoForge 21.1.250 · Manager R19 · Node R9 · Gateway R19 · Conduit 1.8.0

R19 adds authoritative terrain mutation persistence, confirmed saves before region ownership changes, a guarded NeoForge handshake bridge, and save-health/recovery tools. A real creeper crater survived source hard-kill after save acknowledgement; a fresh chest with 17 diamonds survived immediate region transfer. Real-client seamless switching still needs acceptance. See `docs/R19_VALIDATION.md` and the complete `docs/MASTER_STATUS_AND_ROADMAP_2026-09-25.md`.

R16 introduced the operator workspace and runtime hardening retained here. It preserves the existing local-cluster, layer, region, profile, journal and physical-handoff systems. The WebUI follows the supplied Foundations dashboard mockup and now exposes host health, transfer diagnostics and the complete feature-status matrix.

## Update an existing R15 through R19 installation

1. Shut down the entire cluster from Settings. Wait for workers, gateway and Manager to exit.
2. Extract the separate `FoundationsCluster-0.0.6a.R19-HF9-UPDATER.zip` anywhere, including Downloads.
3. Run `APPLY_R19_UPDATE.bat` and select the existing Cluster folder.
4. Keep the printed backup path. Start `start_foundations_server.bat`.
5. Open `http://127.0.0.1:8765/`, sign in and run Test Lab → Safe Suite.
6. With two READY workers, test an actual player transfer, inventory round trip, graceful restart, hard-crash recovery and intentional shutdown before enabling automatic scaling.

The updater validates the installed Manager version and payload hashes, backs up changed program files, normalizes Foundations Node/Gateway JARs, and restores changed files if an update step fails. It preserves the active configuration, worlds, profiles, security vault and journals. Re-running on R19 validates/reinstalls instead of silently skipping a mixed state.

Rollback before starting again:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\apply_r19_update.ps1 -ClusterRoot "C:\Foundations\Cluster" -Rollback "C:\Foundations\Cluster\update-backups\R19-..."
```

The actual backup directory printed by the updater replaces `R19-...`.

## Fresh source package

The source package contains code, UI, build tools, documentation and built Manager R19 / Gateway R19 / Node R11 JARs. It deliberately excludes the uploaded installation's live state, personal configuration, forwarding secrets, runtime downloads, dependency caches and logs. Do not extract it over a live installation; use the updater.

On a fresh folder, `start_foundations_server.bat` creates `config/cluster.properties` from defaults, locates or provisions a checksum-verified JDK 21, and opens first-run administrator setup. NeoForge, proxy prerequisites and the isolated Java 25 gateway runtime are provisioned through the existing setup pipeline. Internet access is required for a fresh installation. Minecraft EULA acceptance remains explicit.

## What changed

- Dark dashboard with consistent vector icons, six metrics, dual-axis CPU/heap/MSPT chart, worker table, quick actions and real region-coordinate map.
- Eighteen administration pages, including new Host Nodes, Diagnostics and Feature Status.
- Ctrl+K navigation/worker search, deep links, sortable worker inventory, CSV export, log pause/download and audit search.
- Responsive mobile layout, keyboard focus handling, partial-outage indicators and protection for unsaved configuration forms.
- Global intentional-shutdown gate across startup, scaling, layer/region planners, emergency failover and operator mutations.
- Migration destination checks for freshness, state, heap headroom, MSPT and player capacity.
- Persisted worker pins that prevent automatic scale-down; manual stop stays available.
- Local host RAM/CPU/storage measurements, bounded cluster history and one-hour worker history. History resets on Manager restart.
- Persisted alert acknowledgements, redacted operational diagnostic ZIPs, transfer-stage correlation and bounded proxy configuration tracing.
- Atomic configuration validation and bounded worker-log reads. Stopped/stale workers no longer contribute to cluster metrics.
- JDK 21 discovery/provisioning, visible build errors, consistent tooling paths, transactional updater and offline backup/verification tool.
- Lightweight Python/Tkinter desktop client for worker controls and logs. The old PySide source was absent from the uploaded archive; this is a new optional client.

## Seamless transfer status

Normal physical migration with Minecraft reconfiguration remains the default. `gateway.seamless.enabled=true` opts into the R19 guarded Conduit bridge. A full cluster restart and fresh client login are required. The bridge captures the actual client's completed configuration replies, verifies destination registries, channels and configuration, and rejects changed or unsupported tasks with a specific error. This targets the enabled-mode `neoforge:register` stall in the supplied R18 logs.

Client-visible seamlessness is not yet proven. Diagnostics includes a normal-transfer recovery button; apply its saved policy with a full restart. Automatic retries to the same failed destination are throttled, and unsuccessful switches preserve the source connection when available.

## Builds and validation

`BUILD_ALL_VISIBLE.bat` keeps errors visible. `build_all.bat` builds without installing; use `build_all.bat --install` explicitly to install Node/Gateway artifacts.

- HF7 Manager and Node R10 compile to Java 21; the Node uses mapped NeoForge 21.1.250 artifacts. See the HF7 validation report for the offline Gradle setup limitation and unrun live checks.
- Gateway and Conduit overlay compile with JDK 25, targeting Java 21 bytecode.
- Historical R19 / Node R9 two-worker integration (not rerun for HF7): seven checks cover fresh chest transfer, acknowledged creeper terrain recovery after hard kill, environment propagation, owner loss and Manager restart.
- Manager: 65 retained assertions plus nine R19 save-barrier assertions. Handshake fixtures: 20 checks. Browser/API: 55 checks. Test Lab: 20 acceptance and 16 button cases. Gateway bootstrap: 48 fixture assertions.
- Updater apply, protected-state preservation, binary normalization, core overlay and rollback are checked in a Linux PowerShell fixture with Windows process discovery mocked. Windows launchers, process discovery, folder picker and native desktop behavior require target-machine acceptance.

See `docs/R19_VALIDATION.md` for evidence, reproduction and remaining limits. Earlier validation files describe historical releases.

## Backups

`BACKUP_CLUSTER.bat` asks for the stopped Cluster folder and writes a SHA-256-verified ZIP beside it. Python 3 is required. The tool holds the Manager lock, refuses detected Java processes, rejects symlinks, avoids runtime caches and never overwrites an existing backup.

```text
python tools/cluster_backup.py verify path/to/Foundations-Backup.zip
```

Backups include private runtime state and vault material; keep them private. The diagnostic ZIP from the WebUI is a separate, allowlisted operational summary. Automated world restore and live snapshots are not implemented.

## Remaining scope

The in-app Feature Status page tracks all 48 design areas. Major open work includes live seamless NeoForge negotiation, complete replica/entity/mod simulation semantics, production region snapshot installation, granular admin roles, named per-worker profiles, multi-host agents, shared storage/messaging and high availability. These are tracked as partial, acceptance-required or planned; they are not represented as finished controls.
