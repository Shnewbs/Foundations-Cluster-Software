# Foundations Cluster — World Replication v2.2 Design Review Addendum

**Status:** Final pre-spike addendum. Prose is frozen after this; further changes come from spike evidence recorded in §70.
**Applies to:** World Replication v2.2 Candidate Design
**Entry format:** Previous assumption / Concern / Decision / Reason / Validating spike / Kill criterion

Entries R1–R9 record decisions reached in review. Entries P1–P3 close gaps found while writing this addendum and are **ACCEPTED** (with the corrections recorded below).

---

# R1 — Player/world operations are a durable, idempotent state machine

**Previous assumption:** Intents (§15–17) are execute-then-ack; player-side consequences (item use, durability, exhaustion) follow the world result implicitly.

**Concern:** Either ordering fails under a crash. World first, then anchor crash: world changed, player kept the item (duplication). Player first, then authority crash: item consumed, no world change (loss).

**Decision:**

```text
PREPARED
   ↓
WORLD_EXECUTED
   ↓
WORLD_RESULT_DURABLE
   ↓
PLAYER_COMMITTED
   ↓
CLIENT_ACKED

or

PREPARED → ABORTED
```

- ABORTED is allowed only before WORLD_RESULT_DURABLE. After that the operation always completes, idempotently.
- PREPARED reserves an inventory resource bound to `inventoryRevision + slot + expected stack identity + expected count`. The client cannot silently swap or drop it.
- The authority operation journal is the durability barrier. Before PLAYER_COMMITTED it durably stores: operationId, cell + epoch, preconditions, authoritative result, resulting block/BE mutations, result frame, dedup status.
- Dedup state lives with what it protects: `appliedWorldOperations` in cell persistence, `appliedPlayerDeltas` in player persistence. Records are compacted at coherent checkpoints.
- Recovery: load vanilla save → load cell journal → replay/verify durable operations newer than the world checkpoint → reconcile player commit state.

**Reason:** Avoids a chunk fsync per click while surviving independent world and player save schedules.

**Validating spike:** A, plus the V0 crash matrix.

**Kill criterion:** Any crash-matrix case yields a duplicated or lost item, or the journal barrier (with group commit) pushes break/place ack p95 past the V0 budget.

---

# R2 — Operation identity is player-persistent, not session-scoped

**Previous assumption:** `operationId = playerSession + sequence`.

**Concern:** A reconnect that lands on a different anchor gets a new session and cannot match an in-flight operation.

**Decision:** Identity is `world/cluster identity + player UUID + persisted 64-bit counter`. Player persistence stores `nextOperationCounter`, `reservations`, `appliedPlayerDeltas`, `unfinishedOperations`. Session ID is metadata only.

**Reason:** Any anchor can resume or deduplicate the exact operation.

**Validating spike:** V0 crash matrix, including reconnect to a different worker.

**Kill criterion:** Any reconnect path that re-executes or drops an unfinished operation.

---

# R3 — Entity ownership is a separate, generation-fenced protocol

**Previous assumption:** Cell ownership is the only authority model; entities are replicated for visibility (§51–52).

**Concern:** A cow crossing a seam changes owners. Without a protocol, a promoted old checkpoint can resurrect it, or both sides can simulate it.

**Decision:**

- Each logical entity carries `UUID, clusterEntityIdentity, ownershipGeneration, ownerCell, ownerEpoch, role`.
- Transfer: `OWNED_A → PREPARING_B → TRANSFERRING → OWNED_B`. Source keeps simulating during PREPARING. Only the final fence freezes new authoritative interactions, with a strict timeout. Fenced interactions are queued in causal order or rejected/retried, never executed by both copies.
- The fence sends **full serialized entity state**, not presentation state.
- **In-place role flip:** each worker has at most one local object per logical entity. Destination promotes its existing shadow to authority; source demotes its authority object to a shadow. No despawn/respawn.
- Source continuation state holds a tombstone (`X gen 41 TRANSFERRED_TO B, next gen 42`). The higher committed generation wins on promotion. Tombstones are covered by the recovery durability/RPO policy.

**Reason:** Prevents resurrection, duplication, and visible pop on the observing worker.

**Validating spike:** D (crossing frequency), V0-E1, V0 entity crash cases.

**Kill criterion:** Any resurrected, duplicated, or dual-authority entity in the crash matrix.

**Known limit (documented, not hidden):** In-place role flip fixes the pop for an observer who stays on one backend (**V0-E1, must pass**). A player changing backends while watching the entity still sees different vanilla integer entity IDs (**V0-E2**, may fail). That is the later Conduit/entity-ID continuity problem and is recorded as such.

---

# R4 — `AuthorityActionPlayer` remains a hypothesis until Spike A

**Previous assumption:** Break/place/use can run on the authority with no player object.

**Concern:** Vanilla and NeoForge paths are player-aware (events, permissions, loot context, advancement triggers, stats). Protection mods may treat a `FakePlayer` as automation.

**Decision:** Spike A tests at least: a NeoForge `FakePlayer`-style actor; a detached, non-listed `ServerPlayer`-like actor; a purpose-built authority action context where feasible. Every side effect is classified: world-authority effects execute on the authority; anchor-owned effects (durability, item consumption, exhaustion, stats, advancement-worthy results) become explicit player deltas. The actor is per-operation and never a second persisted player. Permissions, claims, and mod callbacks are part of the spike result.

**Reason:** The cost of being wrong is discovering it after the Intent Plane is built.

**Validating spike:** A.

**Kill criterion:** No actor model runs real break/place/use semantics without either unsafe duplicate-player behavior or unrecoverable protection/permission divergence from a single-worker control.

---

# R5 — Time is converted at serialization boundaries only

**Previous assumption:** Silent on game time; persisted state may embed absolute times.

**Concern:** Workers have different local game times and tick rates. Scheduled ticks and timers persisted or transferred as absolute values shift.

**Decision:**

- Local level game time is never rewritten.
- Continuation state crossing JVMs is exported as remaining delay / canonical persisted representation and imported relative to the destination's local clock.
- Classify: global world time (gameTime, dayTime, weather) / cell-relative continuation time (scheduled delays, cooldowns we serialize) / unknown mod absolute times (compatibility concern).
- Record effective per-cell TPS. If adjacent interactive cells run at visibly different speeds, first remedy is placement/affinity. Wall-clock anchoring is a last resort.
- Requirement: authority transfer may not change a persisted timer's remaining duration beyond the recovery oracle's allowed error.

**Reason:** Preserves normal local scheduling while keeping persisted timers correct.

**Validating spike:** C, with deliberate skew (authority +200 ticks, replica −150) across redstone, fluid, machine, entity, and mod BE timers.

**Kill criterion:** Important timer classes cannot be migrated within the oracle's tolerance.

---

# R6 — Gates are relative to a scripted single-worker control

**Previous assumption:** Absolute thresholds.

**Concern:** Raw thresholds are arbitrary and noisy without a baseline.

**Decision:** The scripted single-worker control run (V0-CONTROL) is V0 Artifact #1. It fixes world seed, fixture coordinates, gamerules, initial inventories, entity locations, input script, block operations, and timing. Nondeterministic systems (mob AI) are scripted or excluded from tight comparisons. Provisional gates vs control:

```text
block visibility          ≤ control + 1 tick typical
break/place ack p95       ≤ control + defined latency budget
entity seam crossing      no visible remove/respawn pop
player seam crossing      no material position correction / rubberband
duplicate operations      zero
replication CPU           < 5% typical target; sustained > 15% forces architecture review
snapshot fallback         zero during ordinary V0 scenario
```

Spike B sets the real latency numbers before interaction/handoff thresholds are frozen.

**Validating spike:** B, V0.

**Kill criterion:** Sustained replication cost approaching duplicated simulation.

---

# R7 — Continuation starts from vanilla serialization, not a bespoke scheduler log

**Previous assumption:** Periodic scheduler checkpoint plus a live scheduler operation log (§41).

**Concern:** A custom scheduler WAL is a likely source of long-lived subtle bugs.

**Decision:** Prototype first: dirty chunk/cell detection → serialize vanilla-compatible state → incremental, deduplicated transfer (chunk continuation snapshot + dirty BlockEntities + entity-storage delta). Introduce fine-grained continuation operations only where profiling shows serialization is too expensive.

**Reason:** Matches the save/reload oracle by construction.

**Validating spike:** C.

**Kill criterion:** Serialization cost at bounded granularity exceeds the replication budget in R6.

---

# R8 — Spike schedule and gates

**Decision:**

```text
Critical path:  Spike A (presence + AuthorityActionPlayer + transactional player/world effects)
                Spike C (recovery oracle + time skew)
Parallel:       Spike B (instrument existing handoff; latency distributions)
                Spike D (offline trace analysis: entity chunk crossings and
                         cross-boundary neighbor updates per candidate cell size)
```

Spikes become architecture gates. Each records: continue if / reconsider if (see §70 format).

| Spike | Continue if | Reconsider if |
|---|---|---|
| A Presence | Required mechanics reproducible without a second persisted player | Critical mechanics need unsafe duplicate `ServerPlayer` semantics |
| B Handoff | p99 fits the velocity-derived prewarm window | Realistic fast travel outruns achievable catch-up |
| C Persistence | Promoted state matches the save/reload oracle; timers survive skew | Important state cannot be captured generically |
| D Cell size | Cross-cell coupling manageable | Small cells cause constant ownership/affinity churn |

---

# R9 — V0 Seam Proof is the architecture kill test

**Decision:**

```text
V0 — Seam Proof
2 workers / 1 seam / 1 reproducible fixture

CONTROL FIRST
  ✓ scripted single-worker baseline

IN
  ✓ Simulation Cells, single-writer fence, minimal seam presence
  ✓ persistent player operation counter, inventory reservation
  ✓ authority operation journal, durable world-result barrier
  ✓ player/world dedup on both sides
  ✓ block intent prepare/execute/commit/ack
  ✓ AuthorityActionPlayer prototype
  ✓ block delta replication, cell epoch + frame sequence, minimal revision barrier
  ✓ minimal entity presentation
  ✓ cow ownership transfer: in-place role flip, generation + tombstone, full-state fence
  ✓ dropped item + pickup via PlayerEffect
  ✓ ordinary player seam crossing

OUT
  ✗ hot-replica failover, semi-sync
  ✗ replicated lighting, dynamic affinity scheduler
  ✗ complex menus, broad mod support
  ✗ passenger / leash / generic projectile transfer
  ✗ stable vanilla entity IDs
```

Scenario script: grow tree; break block; place block; swap held item while an operation is in flight; force an authority rejection; drop + pick up item; cow crosses seam (V0-E1); player walks/sprints across seam; V0-E2 as an informational test.

**Crash matrix:**

```text
PLAYER/WORLD OPERATION
  after PREPARED
  after WORLD_EXECUTED, before durable record
  after WORLD_RESULT_DURABLE
  after PLAYER_COMMITTED
  before CLIENT_ACKED

ENTITY TRANSFER
  during PREPARING
  during TRANSFERRING
  after B accepts, before source demotion persisted
  after generation/tombstone commit
```

Every case must show: no duplicated item, no lost committed item without a corresponding world result, no duplicated cow, no resurrected stale cow, no dual authority, and deterministic eventual resolution.

**Kill criterion:** If V0 cannot approach single-worker behavior under the R6 gates, stop and fix the architecture before building further.

---

# P1 — ACCEPTED: Authority-initiated player effects split into unilateral and resource-transfer classes

**Previous assumption:** R1's state machine covers every player/world interaction, and pickup is "a PlayerEffect" (§10) handled like damage or knockback.

**Concern:** R1 is anchor-initiated and keyed by the player's operation counter. Authority-initiated effects run the other way, and they are not all alike. Damage, knockback, and status effects can be committed by the authority alone. Item pickup cannot: the authority does not own the player's inventory, so it cannot decide the grant by itself (inventory full, partial stacks, inventory changed mid-pickup).

**Decision:** `PlayerEffect` has two classes, both using cell-scoped IDs (`cell + epoch + effect sequence`).

```text
UNILATERAL EFFECT   (damage, knockback, status effect, ...)
  authority durably records effect
    → anchor applies idempotently, keyed by effect ID
    → authority retries until acked

RESOURCE-TRANSFER EFFECT   (item pickup; XP/resource transfer later)
  1. PROPOSE   authority proposes entity + item identity + amount
  2. RESERVE   anchor validates inventory revision/capacity, reserves
               acceptedCount, returns it (0 = reject)
  3. COMMIT    authority revalidates the entity, durably commits
               committedCount <= acceptedCount:
                 entity consumed/reduced by committedCount
                 player grant = committedCount
  4. APPLY     anchor commits grant idempotently into appliedPlayerDeltas
               and releases any excess reservation
  5. CLEAR     both sides clear their records
```

- Before step 3's durable commit, the transfer can abort and the reservation is released (with a timeout). After it, the grant always completes idempotently.
- Recovery reconciles a durable authority commit with a still-present entity by removing/reducing the entity and redelivering the grant.
- This keeps pickup symmetrical with R1's player-initiated reservation model.

**Validating spike:** A, V0 pickup crash cases.

**Kill criterion:** Any pickup crash case yields a duplicated or lost item, or pickup completion latency exceeds the control-relative budget in R6.

---

# P2 — ACCEPTED: Entity generation commit is a Manager compare-and-set

**Previous assumption:** R3's generation increment happens when "B accepts."

**Concern:** Two workers cannot atomically agree on a bump. A crash between "B accepts" and "A demotes" leaves both claiming ownership, and each side's local record is stale on restart.

**Decision:** The generation increment is a single durable compare-and-set at the arbiter that already holds epochs and leases (the Manager in V0).

```text
A owns generation 41
        ↓
B receives full state and durably stages it
        ↓
Manager CAS:
  expected = A / gen 41
  new      = B / gen 42
        ↓
DURABLE COMMIT
        ↓
B promotes (from its staged state)
A demotes
```

- Before the commit, the transfer can abort and A still owns the entity.
- After the commit, it cannot revert to A; every participant converges to B / gen 42.
- B must durably stage the received full state **before** the CAS, so a B crash after the commit cannot lose the entity.
- On restart, local worker claims are informational. The durable arbiter record wins.
- If Spike D shows one Manager transaction per crossing is too costly, move to batched crossing commits or a cell-pair transfer lease without changing ownership semantics.

**Validating spike:** D (crossing rate), V0 entity crash cases.

**Kill criterion:** Arbiter latency × observed crossing rate cannot fit the seam-crossing gate.

---

# P3 — ACCEPTED: Replay is idempotent for entity side effects; dedup markers are atomic with chunk saves

**Previous assumption:** R1 replay of "resulting block/BE mutations" is idempotent.

**Concern:** (a) Block sets are idempotent, but a break also spawns drops, which are entities with their own save schedule; replay can duplicate or lose them. (b) If a chunk save can contain the block change without the `appliedWorldOperations` marker, replay double-applies. (c) Checking only the live entity map is not enough: a drop can be saved, its chunk unloaded, and the server crash before the marker is written; on restart, replay may run before that entity storage loads, recreate the same UUID, and collide when the old entity loads.

**Decision:**

- Derive output entity UUIDs deterministically from `operationId + index`.
- Store the applied-operation marker in chunk-attached persisted data, so it is written in the same chunk save as the mutations it covers.
- The operation record includes `spawnedEntityIds[]` (and their chunk positions). Before recreating any output, recovery reconciles those IDs against **both** live entities **and** persisted entity storage / restored entity state.
- Rule: operation-generated entity identity is deterministic, **and** persistent recovery knows those identities belong to that operation.

**Validating spike:** A, C. Spike C must include this case: a drop is saved, then merged or picked up, then the server crashes before the marker is written. Absence of the UUID is not proof it was never spawned. Journal entries are therefore retained until a coherent checkpoint covers both chunk and entity storage.

**Kill criterion:** The marker cannot be made atomic with the chunk save without falling back to a per-operation fsync or reconciliation by world diffing, or the saved-then-consumed case cannot be resolved without duplicating an item.

---

# Freeze

P1–P3 are accepted. Design prose is frozen: no further architecture revisions unless a spike produces evidence that forces one. Next work: Spike A, Spike C, instrumented Spike B, offline Spike D, then V0-CONTROL and V0-SEAM. Record every spike result in §70 using: previous assumption / evidence / decision / reason.
