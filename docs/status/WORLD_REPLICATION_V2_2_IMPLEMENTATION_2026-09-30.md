# World Replication v2.2 - first implementation slice

Date: 2026-09-30. Baseline: HF11 / Manager R19 / Node R13, Minecraft 1.21.1,
NeoForge 21.1.250. Runtime version markers remain unchanged.

## Implemented

`reference/.../replication/PlayerOperationLedger.java` is an isolated Spike A
protocol model. It implements persistent cluster/player/counter identity,
slot reservations bound to inventory revision, stack identity and count,
the accepted PREPARED -> WORLD_EXECUTED -> WORLD_RESULT_DURABLE ->
PLAYER_COMMITTED -> CLIENT_ACKED state machine, pre-durability aborts, immutable
durable result identity, and acknowledgement gating by locally applied
cell/epoch/frame. Repeated transitions with the same result are idempotent.
Deterministic output entity IDs derive from operation identity and output index.

The prototype uses a versioned, length-delimited, CRC32C journal with a single
writer file lock and force-before-publication. Recovery restores unfinished
operations, reservations, result receipts and the next counter. Incomplete tail
records are truncated; complete checksum-invalid records fail closed. There is
no journal compaction yet. This fsync-per-transition implementation measures
protocol behavior first; it is not the production group-commit design.

`ContinuationTime.java` starts Spike C with export/import of remaining tick
delay at serialization boundaries. It does not change a level's local clock.
Overflow and negative time/delay fail closed.

## Executed validation

Windows host, Java 21.0.12.1. Topology: local filesystem protocol model only,
no Manager or NeoForge workers, no Minecraft client.

Run `powershell -File tools/test_replication_v22.ps1` from the repository.

- Existing region-transfer reference self-tests: PASS.
- Existing persistent-region reference self-tests: PASS.
- New replication protocol model: 92 checks PASS.

Checks cover close/reopen after all five transaction states, counter continuity,
reservation lifetime, pre/post-durability abort rules, illegal state skips,
missing receipt rejection, duplicate acknowledgements, wrong cluster/player,
wrong cell/epoch/stale frame rejection, exclusive writers, incomplete tails,
checksum corruption, deterministic entity output identity, and deliberate
source +200 / destination -150 timer skew.

These tests are model restart tests, not real process-kill or item-conservation
tests. They do not establish real Minecraft durability or client continuity.

## Evidence and decision

Previous assumption: the accepted durable operation state machine and timer
conversion can be represented independently of vanilla player implementation.
Evidence: the isolated Java 21 model passes its restart/validation tests.
Decision: retain the frozen design and proceed to runtime adapters.
Reason: protocol scaffolding is testable; the architecture's gameplay and
durability claims still require Spike A/C and the V0 kill test.
No frozen architectural prose was rewritten and no spike is marked complete.

## Next required work

1. Spike A: compare FakePlayer, detached non-listed ServerPlayer and feasible
   purpose-built context using real NeoForge break/place/use paths. Classify
   authority vs anchor side effects; measure claims/permissions/events/stats,
   advancements, consumption, durability and protection-mod behavior. Measure
   the L0/L1/L2 presence matrix from the frozen spec.
2. Integrate player-persistent ledger and slot guards with actual inventory
   revisioning and atomic/idempotent appliedPlayerDeltas. A receipt string in
   this model is caller-supplied evidence, not proof of an authority fsync.
   Build the real cell-owned authority journal and durability adapter.
3. Spike C: make appliedWorldOperations markers atomic with chunk saves;
   reconcile deterministic output entities against live and persisted storage,
   including saved-then-merged/picked-up drops. Retain journal records until a
   coherent chunk/entity checkpoint. Compare vanilla save/reload with promotion
   across the full oracle and deliberate time skew. The timer utility alone
   does not demonstrate migration of vanilla or mod timers.
4. Implement P1 resource-transfer reservation/commit/apply and P2 Manager
   generation CAS with durable destination staging. Neither is implemented here.
5. Instrument Spike B's T0-T6 stages and perform Spike D's offline cell-size
   analysis. Then run V0-CONTROL before V0-SEAM, including the real crash matrix.

Continue only on the frozen spike/V0 gates. Do not add routine snapshots to
substitute for continuous replication, or publish this prototype as seamless
world replication. No gameplay handler, worker data, world, security state,
configuration, gateway or production rollout is changed by this slice.
