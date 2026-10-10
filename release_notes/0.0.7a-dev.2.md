# 0.0.7a-dev.2 — installable connected-player observations

Baseline: 0.0.7a-dev.1 source / HF16 operator installation.
Package: 0.0.7a-dev.2. Manager API and gateway: 0.0.6a.R19.
Required Node: 0.0.6a.R15. Minecraft 1.21.1 / NeoForge 21.1.250.

This is an installable development increment, NOT completed two-worker Survival.
Remote Survival breaking/placement remain disabled. The new live runtime path is
read-only observation and save comparison, not authority action execution.

## Implemented

- Opt-in `/foundations_playtest` commands integrated into the Node: start a control
  or seam-labeled capture, mark a step, inspect status, compare the last saved
  inventory, and stop. Only an operator's own connected real player can start.
  Fake players cannot generate connected-player evidence. Recording defaults OFF.
- Observe mining start/stop/abort events, break/place/use event cancellation state,
  a later loaded-chunk state sample, mining statistics, dropped-item events and
  post-pickup counts. No observer changes cancellation, sends action packets,
  mutates an inventory/block, invokes an authority action, or changes ownership.
- Record persistent-component inventory digests, changed-slot item/count/damage,
  selected slot, sampled player position and server tick/encoding timings.
  Raw item NBT, custom names, lore, chat, IPs, authentication tokens and full .dat
  data are not written to reports. Coordinates and item identifiers are included.
- Read-only last-player-save inspection: validates player UUID, all ordinary slot
  mappings including armor/offhand, selected slot, malformed/duplicate/unknown
  slot entries and bounded compressed NBT. MATCH/MISMATCH never restores data.
  Nonpersistent components, open menus/cursors and unsupported layouts are marked
  unavailable, not silently accepted as inventory equivalence.
- Bounded asynchronous diagnostic writer: four active/flushing captures per worker,
  five minutes per capture, 5,000 records, 256 queued records, 16 KiB per record,
  4 MiB per report, 32 existing report files per directory. Old reports are never
  automatically removed. Overflow/error stops capture; dropped records or missing
  footer invalidate complete telemetry. No per-event fsync claim or crash recovery.
- Offline Python summary validates JSON framing, contiguous sequences, monotonic
  elapsed timestamps, terminal counts and schema. It reports coverage and gaps,
  never infers gameplay correctness from counts or calls a capture a seam PASS.
- Additional real vanilla scheduled block/fluid tick serialization tests export
  at source time 1200 and import at 850. They check remaining delays, priority,
  overdue scheduling and exclusion of ticks belonging to a different chunk.

## Deployment choice

The recorder is embedded in the existing Node, not registered as a new mod. HF16
fingerprints loaded non-infrastructure mod IDs/versions and selected packs. Adding
an unrelated diagnostic mod would change that compatibility fingerprint even when
installed on every worker. Node's existing infrastructure exclusion stays intact;
no world-identity checks are disabled or canonical fingerprints rewritten.

The Node entrypoint is generated from its retained source template with the version
from gradle.properties. The rest of the entrypoint is unchanged. Compiled version,
metadata and Manager selection are checked before packaging.

Manager now expects R15 and uses the existing version-checked template/worker
synchronization and backups. The updater stages the compiled Node and matching
Manager; no operator Gradle build is needed. HF16 UI shutdown/restart and per-worker
controls remain included. Worlds, player saves, journals, configuration, security
and existing report files are not updater payloads. No user data is touched by CI.

## Evidence and limits

The portable writer/report tests run from tools/validate.py. Ten new NeoForge
GameTests extend the 35 retained tests to 45. GameTests cover the actual save codec,
all 41 slots, component round trips, read-only mismatch, invalid player saves,
compressed disk readback, malformed file preservation, cursor refusal, synthetic
actor refusal, real Node command registration, permission and mod-ID isolation,
and scheduled-tick persistence. Exact-candidate Windows/Linux execution is required
before publication. Test sources alone are not passing evidence.

GameTests do not connect a real Minecraft client. Your opt-in capture supplies
that next evidence category. A trace called `control` does not itself prove the
server is single-worker; the test topology and initial state must be recorded.
Canceled status is the value at this observer's LOWEST listener, not necessarily
later listeners at the same priority. Deferred result samples can contain other
intervening changes and are NOT unique operation attribution or client ACKs.
Server tick/serialization samples are not client latency or a performance benchmark.

Captures end on logout, player-instance replacement, backend handoff or shutdown.
Start another capture on the destination; this release does not collect uninterrupted
cross-worker/client timing. Nonstandard equipment, ender inventories and full
player effects are not ordinary-inventory equivalence. A .dat mismatch may simply
be unsaved gameplay since the last vanilla save. `check-save` is an explicit,
bounded synchronous read, not a periodic server-thread disk poll. Old saves are
not upgraded with a DataFixer. The tool never creates or overwrites a .dat file.

Scheduled-tick tests exercise vanilla's real codecs and compressed NBT, not
running redstone/fluid behavior after worker promotion, chunk/entity checkpoints,
mod timers, joined-client continuity or sudden power loss.

## Next remaining 7a gates

Use joined-player observations to refine production actor/presence/player effects.
All-mutator inventory interception, trusted intent/receipt transport, actual player
.dat/journal reconciliation, world/entity checkpoint coordination, consumed-drop
reconciliation, real worker-crash recovery, running timer-skew acceptance, continuous
frames/ack barriers, pickup transactions, cow/entity generations and V0-SEAM remain.
The dev.1 journal/adapters remain compiled groundwork, not live inventory authority.

Install/test/rollback instructions: `docs/PLAYTEST_DEV2_GUIDE.md`.
Frozen architecture documents are unchanged. This development branch is not main.
