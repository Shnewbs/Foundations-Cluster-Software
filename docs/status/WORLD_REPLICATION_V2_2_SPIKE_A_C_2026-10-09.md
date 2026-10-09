# World Replication v2.2 — HF15 Spike A/C build

Baseline: HF14, Minecraft 1.21.1, NeoForge 21.1.250.
Build: Manager API R19 / package HF15 / Node R14 / gateway R19.

## Scope

This release builds and runs the first real NeoForge action experiments.
It does **not** enable remote Survival editing or complete Spike A, Spike C,
V0-CONTROL or V0-SEAM. The Creative-only runtime route remains fenced.
Node now tells a Survival player why a remote edit is denied instead of failing
silently. The operator command foundations_interaction_status reports the gate.

AuthorityBreakExecutor validates the server thread, current ownership, Survival
mode, loaded chunk, reach, world border, vanilla interaction permission, exact
block state, selected slot, full item components/count and unbreakable blocks.
It calls NeoForge's patched ServerPlayerGameMode.destroyBlock. Mining
progression/left-click protocol and durable effect coordination belong to its
future caller. No network endpoint or command invokes this executor on a live
world in this release.

The separate spike source set is a disposable test mod. It is excluded from
the runtime Node JAR and ordinary server/client runs. It compares per-operation
FakePlayer and detached ServerPlayer actors through actual break/place/use
paths. Synthetic identities are used; actors are never logged in or added to
the player list. This is a game-path comparison, not a connected-client baseline.

AuthorityResultJournal retains complete bounded opaque result payloads,
including real Minecraft result NBT in the integration fixture. It forces the
journal before returning a receipt, rejects conflicting retries and stale
epochs, protects record framing against length corruption, and restores stable receipts/frame order after reopen. CRC32C,
exclusive writers, bounded records and torn-tail handling are exercised.
There is no compaction, batching, chunk replay or claim of coherent checkpoints.
The player protocol model now consumes receipts from this real journal in tests.

## Run

Java 21 and Python 3 are required:

    python tools/validate.py
    python tools/validate_survival_spike.py

The spike runner downloads SHA256-pinned Gradle 8.14.3 and the pinned NeoForge
dependencies. It cleans only minecraft-agent/build and uses its own disposable
world at minecraft-agent/build/survival-spike-world, spawn 0,60,0. It never uses
an installation's worker/world directories or agent credentials.
CI runs both suites on Windows and Linux and retains the full game-test logs.

The new checks include 20 real NeoForge GameTests plus portable journal checks:

- Real stone/dirt breaks with correct/incorrect tools for both actor types.
- Tool wear, final durability consumption, vanilla loot, and cancelled
  BreakEvent/BlockDropsEvent behavior.
- Changed components, held-slot swaps, changed blocks, epoch fencing, reach,
  game-mode restrictions, and unbreakable bedrock.
- Modded removal refusal after tool wear: EXECUTED is not a guarantee of an air block.
- Actual Survival placement consumption and lever use for both actors.
- Explicit fake-player protection-policy divergence and statistics differences.
- Real break result NBT and deterministic drop UUIDs through authority/player
  journal reopen; retry does not execute a second game action.
- Vanilla/NeoForge chunk serialization to disk and readback of both the changed
  block and a chunk-attached applied-operation marker in the same image.
- Actual compiled Node upgrade through Manager template/worker synchronization.
- 33 authority journal checks in addition to the existing protocol regressions.

Game tests demonstrate these scoped semantics in a single JVM. The journal
tests reopen files; they do not simulate sudden power loss or independently
crashing live Minecraft workers. CI release gates verify the exact candidate
with stock upstream build tooling. The local container needed a temporary NFRT
launcher-path fallback because ProcessHandle did not expose its executable;
that environment-only workaround is not committed or included in the release.

## Spike evidence / decisions (frozen §70 format)

**Previous assumption:** a generic player-like actor can execute owner-side
actions while preserving ordinary player semantics.

**Evidence:** real NeoForge paths run for both candidates. FakePlayer suppresses
mining statistics and uses no-op advancement handling. A protection-style event
handler that rejects automation cancels FakePlayer while permitting the detached
actor. This is a deliberately injected test policy, not third-party mod
certification. A detached ServerPlayer constructor touches PlayerList statistics
and advancement caches even without login; it must not use real player identity
as a production workaround.

**Decision:** retain the frozen architecture; do not select a production actor or
enable networked Survival edits yet. Explicit player effects and actual
protection-mod acceptance are required. A context-only block set is rejected as
a substitute: it bypasses the player-aware vanilla/NeoForge path.

**Reason:** successful block removal is insufficient evidence for claims,
advancement, presence and player inventory equivalence.

**Previous assumption:** applied-operation data can travel in the same save
image as block mutations.

**Evidence:** a registered serialized chunk attachment and block data round-trip
together through the actual ChunkSerializer and compressed NBT on disk.
The NeoForge serializer catches attachment serialization exceptions, so the
normal-path test alone cannot prove fail-closed atomic-marker behavior.

**Decision:** continue Spike C with fail-closed save validation, entity-storage
reconciliation and coherent checkpoint retention. Do not infer safe replay from
a missing live drop UUID.

**Reason:** separate entity saves and saved-then-merged/picked-up outputs still
allow duplication without the full P3 protocol.

## Remaining gates before live Survival

1. Final actor selection, L0/L1/L2 presence, claims/mod events, stats/advancements,
   effects and mining progression compared with a real one-worker control.
2. Anchor-owned inventory revisions/reservations, slot guards, and atomic
   idempotent player delta persistence across reconnect/anchor change.
3. Real chunk/entity checkpoint/replay adapters; deterministic output reconciliation
   including saved-then-consumed drops, and the five-stage process-kill matrix.
4. Deliberate time skew through actual scheduled ticks and representative mod
   timers; the existing remaining-delay model is insufficient.
5. Continuous result frames/revision barriers and the two-worker V0 seam proof.
   P1 resource pickup, P2 entity-generation CAS and B/D measurements remain open.

No frozen design prose, user worlds, active settings or seamless-switch policy
is changed.
