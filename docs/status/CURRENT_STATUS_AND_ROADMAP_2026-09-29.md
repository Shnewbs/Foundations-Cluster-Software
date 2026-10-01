# Foundations Cluster — Current Status, Work Completed, and Roadmap

**Status date:** 2026-09-29  
**Current source:** `0.0.6a.R19-HF11` / Node `0.0.6a.R13`  
**Minecraft:** 1.21.1  
**NeoForge:** 21.1.250  
**Architecture target:** World Replication v2.2 Candidate, frozen pending spike evidence

---

# 1. Current Source of Truth

The source in this handoff package is the current implementation baseline:

```text
Package revision       0.0.6a.R19-HF11
Manager runtime/API    0.0.6a.R19
Cluster Node           0.0.6a.R13
Gateway plugin         0.0.6a.R19
Conduit                existing 1.8.0 R19 bridge/runtime
Manager/Node Java      Java 21
Minecraft              1.21.1
NeoForge               21.1.250
```

Key version files:

```text
source/Cluster/cluster-manager/src/main/java/com/foundations/cluster/manager/ClusterVersion.java
source/Cluster/minecraft-agent/gradle.properties
source/Cluster/minecraft-agent/src/main/resources/META-INF/neoforge.mods.toml
```

The `source/Cluster/` tree is the working baseline. Older R15/HF9 material remains historical and must not replace it.

---

# 2. High-Level Project Goal

The long-term Foundations Cluster goal remains:

> A player experiences one persistent Minecraft server while the runtime invisibly manages many JVMs, simulation cells/regions, replicas, and eventually multiple physical hosts behind it.

To reach that goal the project must provide both:

```text
correctness
and
client-visible continuity
```

A transfer that preserves data but visibly replaces the surrounding world is not the final target.

---

# 3. Implemented and Working Foundations

The current runtime already contains substantial infrastructure.

## 3.1 Manager / control plane — implemented

Implemented areas include:

```text
Manager startup and lifecycle
worker registration and supervision
worker start/stop/restart/drain flows
worker evacuation groundwork
region authority records and epochs
session directory
layer groundwork
persistent configuration
security vault / internal API secrets
WebUI/API
metrics/alerts/audit groundwork
support ZIP capture
Test Lab / acceptance tooling
backup/update tooling
```

The Manager runtime version intentionally remains `0.0.6a.R19`; hotfix package revisions are tracked separately.

---

## 3.2 Real physical backend handoff — implemented

Earlier revisions corrected the old "fake seamless" model where only logical simulation ownership moved.

The accepted physical handoff model requires both:

```text
network/backend anchor moves
AND
simulation/authority ownership moves as intended
```

The project now uses a real physical backend migration path through the Gateway/Conduit layer.

---

## 3.3 NeoForge/Conduit seamless negotiation work — substantially implemented, still not final

The R19 line contains guarded NeoForge handshake/configuration replay and compatibility work.

HF9 live evidence showed three physical handoffs reaching the expected backend negotiation endpoint with experimental seamless enabled.

Measured historical stages included approximately:

```text
proxy SWITCH_REQUEST → SWITCH_ACCEPTED: 474 / 97 / 100 ms
full Manager request → commit: ~3.974 / 6.013 / 3.860 s
```

These measurements describe different stages. They do not prove zero client-visible movement stall or perfect same-world continuity.

---

# 4. Persistence Work Completed

## 4.1 Persistent block and BlockEntity journals — implemented

The project includes durable block and BlockEntity persistence/journaling, including the `host-block-v3` lineage and later hardening.

Implemented behavior includes:

```text
authoritative block mutation capture
BlockEntity state persistence
write coalescing
region/epoch fencing
acknowledged durable saves before selected ownership transitions
replay/hydration
wrong-region validation
pending/failed write diagnostics
```

---

## 4.2 Chest persistence / BlockEntity transfer — live evidence passed

HF9 live acceptance verified the exact 17-diamond chest through source/destination/return checks.

The test isolated the expected item stack rather than relying only on generic "chest exists" checks.

---

## 4.3 Generated structure persistence — live evidence passed

The Generated Tree acceptance test compares a 6,144-block sample across source/destination/return.

HF9 live acceptance reported matching generated-oak states through the physical ownership round trip.

Important limitation:

```text
/place feature or generated-fixture success
!=
proof of natural random-tick growth ownership
```

That distinction led directly to HF11.

---

## 4.4 Explosion/terrain persistence — implemented and previously validated

The R19 line expanded authoritative chunk mutation capture to terrain mutations such as creeper damage and command-driven changes.

Earlier real-worker integration demonstrated acknowledged creeper terrain damage surviving hard-kill/recovery.

---

# 5. Environment State Work Completed

The runtime includes an overworld environment coordinator for shared clock/weather state and ownership failover.

Real-worker integration previously passed:

```text
time/weather propagation
environment-owner failover
Manager restart recovery
```

Limitations remain for broader global state such as:

```text
sleep/time-skip semantics
raids
patrol/phantom spawners
datapack scheduled functions
map/global SavedData
dragon fight state
mod-specific calendars such as TFC seasons
```

These are accounted for in the v2.2 architecture roadmap but are not fully solved.

---

# 6. HF8 / HF9 Stabilization Work Completed

## HF8

Key retained behavior included:

```text
support ZIP creation/download
optional failed-test capture
bounded diagnostic retention
hydration/replay hardening
callback suppression during snapshot ingestion
final-state capture after nested mutations
chunk/snapshot region validation
```

## HF9

HF9 fixed worker Stop → Restart policy handling so a temporary stop fence no longer permanently left a restarted worker DRAINED when operator policy was not actually drained.

HF9 live Full Acceptance passed the then-current suite, including:

```text
inventory continuity
17-diamond chest round trip
generated tree state comparison
physical handoffs
worker graceful evacuation / restart returning READY
```

This remains important historical acceptance evidence.

---

# 7. HF10 Repair Work Completed and Retained in HF11

HF10 exposed deployment/version-consistency problems rather than a world-data failure.

Issues found and fixed before HF11:

```text
Manager initially expected the wrong Node version
Node R12 was built but existing workers could remain on R11
WebUI version checks were still hard-coded to R11
startup could report Node OUTDATED even when R12 artifact existed
```

The resulting Manager/Node version-recognition and dynamic WebUI checks are retained in the HF11 source.

Do not reintroduce hard-coded browser-side expected Node versions.

---

# 8. HF11 / Node R13 — Current Implemented Growth Work

HF11 is the current source baseline.

## 8.1 Problem reproduced

R12 could produce this state:

```text
replica is tick-eligible and grows tree locally
        │
        ├── replica mutation is non-authoritative and discarded
        │
playerless authority only snapshot-loads chunk
        │
        └── authority never becomes native tick-eligible

result:
replica visually grows
but authority remains a sapling
```

---

## 8.2 HF11 implementation

HF11 / Node R13 adds:

### `GrowthTickMixin`

Only the current managed, ACTIVE, hydrated, unsealed authority with valid/fresh Manager control is allowed to run the native `ServerLevel.tickChunk` random/weather chunk pass.

Replicas suppress that pass.

### `RemoteTickLeases`

Accepted replica-view chunk requests create bounded transient forced-ticking interest on the authority so a playerless authority can run its normal native chunk tick pass.

Properties:

```text
bounded TTL
bounded chunk count
server-thread owned
released on expiry
released on ownership/epoch/control loss
released on sealing/shutdown
not persisted as permanent vanilla forced chunks
```

### Existing replication reused

HF11 reuses:

```text
authoritative mutation capture
block journal
mutation invalidation
replica snapshot path
```

Gateway/Conduit physical handoff code was intentionally left unchanged.

---

# 9. HF11 Validation Completed

Executed HF11 validation included:

```text
Manager Java 21 compilation                     PASS
Node compilation against mapped NeoForge        PASS
R12 natural-growth reproduction                 5 checks PASS
R13 two-worker natural-growth test               10 checks PASS
retained real-worker persistence/environment     9 checks PASS
RemoteTickLeases unit tests                      20 PASS
Node startup/deployment fixtures                 33 PASS
retained Manager tests                           148 PASS
retained WebUI tests                             110 PASS
installer/rollback transaction checks            70 PASS
```

The natural-growth fixture used two real headless NeoForge workers plus a real Manager.

It demonstrated:

```text
native authority growth
replica random-tick suppression
durable log/leaf capture
zero pending/failed writes at acceptance
identical 6,144 sampled block states before authority move
ticket expiry
authority A → B → A replay
```

Important limitation:

No Minecraft client joined that fixture. It does **not** prove the absence of client-visible world pop, movement stall, entity discontinuity, or backend-switch artifacts.

---

# 10. Current User-Observed Problem

After HF11, natural growth is more correct, but the user still reports that tree growth / world continuity is **visibly not seamless** across workers.

This is expected from the remaining architecture:

```text
authority mutation
    ↓
journal / invalidation
    ↓
replica snapshot refresh
    ↓
replica apply
```

This path is too coarse for a world the player is actively observing.

HF11 should therefore be treated as an important correctness bridge, not the final world-replication architecture.

---

# 11. Work Not Yet Implemented — Major Gaps

The following are **not implemented** in the current HF11 source unless otherwise noted as partial groundwork.

## 11.1 Continuous live block delta replication — NOT STARTED

Needed:

```text
per-Simulation-Cell frame sequence
packed block deltas
per-frame coalescing
replica apply context
revision barrier
bulk-change crossover
```

Current runtime still relies too heavily on snapshots for replica convergence.

---

## 11.2 Simulation Cells separate from `.mca` regions — NOT STARTED

Current region infrastructure exists, but the v2.2 proposal introduces a finer authority unit called a Simulation Cell.

Cell size must be measured, not guessed.

---

## 11.3 Player Presence seam protocol — NOT STARTED

Needed:

```text
L0 ticket presence
L1 targetable proxy presence
L2 player-compatible presence if actually required
velocity-scaled prewarm
hysteresis
remote-anchor deadline
```

The accepted design treats remote presence as a bounded seam mechanism, not permanent split-player simulation.

---

## 11.4 Authority Intent Plane — NOT STARTED

Needed for player actions arriving on a worker that does not own the target world state.

Initial scope:

```text
block break
block place
simple block/item use
```

Entity interaction comes later after minimal entity replication exists.

---

## 11.5 `AuthorityActionPlayer` model — SPIKE REQUIRED, NOT IMPLEMENTED

The authority likely needs a player-like execution context for real vanilla/NeoForge break/place/use paths.

Spike A must compare at least:

```text
FakePlayer-style actor
detached non-listed ServerPlayer-like actor
purpose-built action context where feasible
```

Do not assume any is correct before testing permissions, claims, events, stats, advancements, item consumption and mod behavior.

---

## 11.6 Transactional player/world operations — NOT STARTED

Accepted state machine:

```text
PREPARED
WORLD_EXECUTED
WORLD_RESULT_DURABLE
PLAYER_COMMITTED
CLIENT_ACKED

or

PREPARED → ABORTED
```

Requirements include:

```text
persistent player operation counter
inventory reservation/versioning
cell-side appliedWorldOperations
player-side appliedPlayerDeltas
durable authority journal barrier
recovery reconciliation
ack only after result frame is applied locally
```

---

## 11.7 Authority-initiated resource transfers — NOT STARTED

Item pickup is not equivalent to damage/knockback.

Accepted future protocol:

```text
PROPOSE
RESERVE acceptedCount
COMMIT authority entity reduction/removal
APPLY player grant idempotently
CLEAR
```

This is part of Spike A / V0.

---

## 11.8 Entity presentation — NOT STARTED

Minimal entity shadowing is intentionally pulled into V0.

Initial target:

```text
cow
item
```

Full entity replication is later.

---

## 11.9 Entity ownership transfer — NOT STARTED

Accepted model:

```text
OWNED_A
PREPARING_B
TRANSFERRING
OWNED_B
```

Requirements:

```text
cluster entity identity
ownership generation
owner cell + epoch
full-state final fence
in-place authority/shadow role flip
source tombstone
durable generation commit
```

Manager is the V0 generation compare-and-set arbiter.

---

## 11.10 Complex entity transfer — NOT STARTED

Deferred beyond V0:

```text
passengers
leashes
generic projectiles
complex vehicles
modded entity graphs
```

---

## 11.11 Authority affinity scheduler — NOT STARTED

Continuous cross-cell systems such as redstone/fluid/pipes should often be co-located instead of distributed forever.

Future affinity inputs include:

```text
cross-cell ops
neighbor traffic
fluid/redstone seam traffic
player/vehicle seam presence
mod write violations
shared mod network identity
handoff frequency
```

---

## 11.12 Lighting replication/revision — NOT STARTED

Accepted correctness-first direction:

```text
authority resolves light
changed light sections replicated
destination tracks light revision
handoff waits for light convergence
```

Replica relighting may be evaluated later as an optimization.

---

## 11.13 Hot Replica / failover continuation — NOT STARTED

Current runtime has persistence and emergency-failover groundwork, but not the v2.2 Hot Replica model.

Future distinction:

```text
Visual Replica = continuity only
Hot Replica    = promotion-grade continuation state
```

---

## 11.14 Semi-sync durability mode — NOT STARTED

Accepted future behavior:

```text
authority keeps ticking
frame goes to hot replica
visual fan-out waits for hot ACK
on timeout, release frame and mark DEGRADED_ASYNC
```

Do not stall world simulation indefinitely on a slow hot replica.

---

## 11.15 Recovery RPO/RTO — NOT YET DEFINED BY MEASUREMENT

The project explicitly no longer promises generic "zero rollback / zero stall" after a hard crash.

Future release criteria must define and measure:

```text
RPO
RTO
no dual writer
no duplicate committed operation
```

---

## 11.16 Continuation serialization — SPIKE REQUIRED

Current accepted starting direction is to prototype vanilla-compatible dirty state serialization before inventing a custom scheduled-tick WAL.

Candidate continuation units:

```text
dirty chunk/cell state
dirty BlockEntities
entity storage delta
canonical scheduled tick persistence
```

Only add bespoke operation logs where profiling proves serialization is too expensive.

---

## 11.17 Per-mod write policy — NOT STARTED

Accepted policies:

```text
AUTO
ROUTE
REJECT
PIN
```

Replica write violations must feed compatibility reporting and affinity decisions.

---

## 11.18 Cross-cell discrete operation protocol — NOT STARTED

Needed for:

```text
explosions
structure/tree placement
bulk scripted mutation
```

Requires causal operation IDs, preconditions/revalidation, idempotency and epoch checks.

---

## 11.19 Stateful remote menu sessions — NOT STARTED / DEFERRED

Menus are ordered sessions, not single intents.

Player locality wins over container locality.

Complex remote menus are not part of V0.

---

## 11.20 Full global-state coordination — PARTIAL

Time/weather coordination exists.

Still needs explicit architecture/coverage for:

```text
raids
sleep/time-skip
scoreboards/teams/boss bars where appropriate
patrol/phantom spawners
scheduled datapack commands/functions
dragon fight
map data
other SavedData
mod global state
```

---

# 12. Frozen Architecture Documents

The accepted design direction is preserved verbatim in:

```text
docs/frozen_design/FOUNDATIONS_WORLD_REPLICATION_V2_2_CANDIDATE_DESIGN.md
docs/frozen_design/WORLD_REPLICATION_V2_2_DESIGN_REVIEW_ADDENDUM.md
```

The addendum is authoritative when it sharpens or supersedes an assumption in the candidate design.

Important accepted review decisions include:

```text
R1 durable player/world operation state machine
R2 persistent operation identity
R3 generation-fenced entity ownership protocol
R4 AuthorityActionPlayer remains a Spike A hypothesis
R5 time conversion only at serialization boundaries
R6 scripted single-worker control is V0 Artifact #1
R7 continuation starts from vanilla-compatible serialization
R8 spike ordering and go/no-go gates
R9 V0 Seam Proof is the architecture kill test
P1 unilateral vs resource-transfer PlayerEffect split
P2 Manager CAS is the V0 entity ownership generation commit point
P3 deterministic operation-generated entity IDs + persisted recovery reconciliation
```

Do not rewrite this architecture based only on speculation. New changes require spike evidence.

---

# 13. Immediate Roadmap — Phase 0 Architecture Spikes

The next work is **not** to implement the complete v2.2 architecture.

The next work is to answer the highest-risk assumptions cheaply.

---

## 13.1 Spike A — Player Presence + AuthorityActionPlayer + Transactional Player/World Effects

**Priority:** CRITICAL PATH

### Goals

Determine:

```text
what presence representation vanilla/NeoForge mechanics actually require
whether real break/place/use can execute correctly on remote authority
how player-owned side effects are separated from world-owned effects
whether inventory reservation + durable operation commit is viable
```

### Presence modes to test

```text
chunk loaded only
block/forced ticking ticket
entity-ticking ticket
L0 ticket presence
L1 targetable entity proxy
L2 player-compatible presence
```

### Actor models to test

```text
NeoForge FakePlayer-style actor
detached non-listed ServerPlayer-like actor
purpose-built authority action context where feasible
```

### Gameplay to inspect

```text
break
place
right click / item use
permissions/claims
NeoForge events
loot context
enchantments
durability
item consumption
exhaustion
stats
advancement-worthy outcomes
mod callbacks
```

### Transaction cases

```text
PREPARED → ABORTED
PREPARED → WORLD_EXECUTED → WORLD_RESULT_DURABLE → PLAYER_COMMITTED → CLIENT_ACKED
reconnect to different anchor while unfinished
swap/drop reserved item attempt
pickup resource-transfer effect
```

### Continue if

Critical mechanics can be reproduced without unsafe duplicate persisted-player semantics and the durability barrier can stay within the V0 latency budget.

### Kill/reconsider if

```text
no actor model preserves required semantics safely
crash matrix duplicates/loses items
durability barrier makes interaction latency unacceptable
protection/permission behavior cannot match a single-worker control
```

---

## 13.2 Spike C — Persistence Oracle + Time Skew

**Priority:** CRITICAL PATH

### Goals

Define exactly what Foundations promises to recover by comparing against a real clean save/reload.

### Control scenario

Include:

```text
pending redstone
flowing fluid
crop/growth state
furnace
hopper/chest
villager
hostile mob
item/projectile
minecart/passenger example
status effects
modded BlockEntity
NeoForge attachment
SavedData/global-state examples
```

### Branch A

```text
normal save
stop
reload
```

### Branch B

```text
Foundations coherent checkpoint
promotion/recovery prototype
```

### Deliberate time skew

Test source/destination with different local game times, for example:

```text
authority +200 ticks
candidate -150 ticks
```

Do not continuously rewrite local level time.

Export/import timer state through canonical persisted/relative representations.

### Compare

```text
blocks
BlockEntities
scheduled block ticks
scheduled fluid ticks
entities
inventories
attachments
SavedData
POI/global state where applicable
remaining timer durations
```

### Continue if

Promotion/recovery can match the documented vanilla/NeoForge save/reload oracle within defined tolerances.

### Kill/reconsider if

Important continuation state cannot be captured generically or timer semantics cannot survive cross-JVM serialization boundaries.

---

## 13.3 Spike B — Existing Handoff Latency Instrumentation

**Priority:** PARALLEL

Instrument the existing physical handoff rather than redesigning it first.

Record:

```text
T0 seam crossing predicted
T1 destination prewarm begins
T2 destination visible state ready
T3 revision-ready equivalent / current readiness point
T4 proxy switch begins
T5 destination accepts
T6 client interaction resumes
```

Measure:

```text
median
p95
p99
worst
```

Profiles:

```text
walk
sprint
boat
minecart
elytra
elytra + rocket
fast modded vehicle if available
```

Determines:

```text
velocity-scaled prewarm band
hysteresis safety margin
remote-anchor deadline
interaction/handoff budget
whether any temporary migration strategy is viable
```

Kill/reconsider if realistic fast travel outruns achievable prewarm/catch-up.

---

## 13.4 Spike D — Offline Simulation Cell / Boundary Trace Analysis

**Priority:** PARALLEL

This does not require the full cluster implementation.

Collect/replay a representative busy vanilla/modded trace and evaluate candidate cell sizes:

```text
1×1 chunks
2×2
4×4
8×8
16×16
32×32
```

Count/estimate:

```text
entity boundary crossings
neighbor/block update crossings
redstone/fluid seam traffic
player seam dwell/crossing
potential affinity-group formation
ownership metadata overhead
```

Continue if a practical cell size gives manageable cross-cell coupling without making placement/handoff too coarse.

Reconsider if small cells generate constant ownership/affinity churn or large cells defeat scaling.

---

# 14. V0 — Seam Proof / Architecture Kill Test

V0 begins only after Spike A and Spike C establish viable semantics and B/D provide measurements.

## 14.1 V0-CONTROL — Artifact #1

One normal NeoForge worker.

Scripted reproducible fixture fixes:

```text
world seed
coordinates
gamerules
initial player inventory
entity locations
block operations
movement/input script
test timing
```

Capture:

```text
block visibility latency
break/place acknowledgement latency
player position corrections
entity lifecycle events
MSPT/CPU
packet/event timing where instrumented
```

Nondeterministic mob behavior is scripted or excluded from tight numerical comparison.

---

## 14.2 V0-SEAM

Topology:

```text
2 workers
1 seam
same scripted fixture
```

### IN

```text
Simulation Cells
single-writer fence
minimal seam presence
persistent player operation counter
inventory reservation
authority operation journal
durable world-result barrier
player/world dedup on both sides
block prepare/execute/commit/ack
AuthorityActionPlayer prototype
block delta replication
cell epoch + frame sequence
minimal revision barrier
minimal entity presentation
cow ownership transfer
in-place authority/shadow role flip
ownership generation + tombstone
full-state final entity transfer fence
dropped item + pickup PlayerEffect
normal player seam crossing
```

### OUT

```text
Hot Replica failover
semi-sync
replicated lighting
dynamic affinity scheduler
complex menus
broad mod support
passenger transfer
leash transfer
generic projectile transfer
stable vanilla integer entity IDs
```

---

# 15. V0 Script

At minimum:

```text
1. grow tree
2. break block
3. place block
4. attempt held-item swap while operation is in flight
5. force an authority rejection
6. drop item
7. pick up item across seam semantics
8. cow crosses seam (V0-E1)
9. player walks/sprints across seam
10. V0-E2 informational: player changes backend while observing entity
```

V0-E1 must pass.

V0-E2 may expose the later local vanilla entity-ID / Conduit continuity limitation and should be recorded honestly.

---

# 16. V0 Crash Matrix

## Player/world operation

Crash at:

```text
after PREPARED
after WORLD_EXECUTED before durable record
after WORLD_RESULT_DURABLE
after PLAYER_COMMITTED
before CLIENT_ACKED
```

Must show:

```text
no duplicated item
no lost committed item without corresponding world result
idempotent recovery
no operation re-execution after reconnect
```

## Entity transfer

Crash at:

```text
during PREPARING
during TRANSFERRING
after B stages/accepts, before ownership CAS
after Manager generation CAS, before source demotion persists
after generation/tombstone commit
```

Must show:

```text
no duplicated cow
no resurrected stale generation
no dual authority
one deterministic owner after recovery
```

---

# 17. V0 Gates

Compare against the scripted single-worker control.

Provisional gates:

```text
block visibility       <= control + 1 tick typical
break/place ack p95    <= control + measured/approved latency budget
entity seam crossing   no visible remove/respawn pop in V0-E1
player seam crossing   no material rubberband/position correction
duplicate operations   zero
snapshot fallback      zero during ordinary V0 scenario
replication CPU        <5% typical target
sustained >15%         mandatory architecture review
```

Spike B must set the final interaction/handoff latency budget before V0 sign-off.

If V0 cannot approach single-worker behavior under these gates:

> **STOP. Fix the architecture before implementing the long roadmap.**

---

# 18. Roadmap After V0 Passes

Only proceed if V0 proves the core model.

## Phase 1 — Production block delta replication

Expand the V0 block stream:

```text
packed encoding
coalescing
bulk mutation crossover
repair/snapshot fallback
integrity sweep
backpressure
```

## Phase 2 — Lighting convergence

Implement authority-resolved changed light-section replication and `lightRevision` handoff barrier.

## Phase 3 — Affinity scheduler

Implement dynamic cell co-location using measured coupling signals and hysteresis/capacity limits.

## Phase 4 — BlockEntity compatibility

Implement:

```text
native dirty tracking
integrity hashing
attachments
per-mod AUTO/ROUTE/REJECT/PIN policies
optional provider API
```

## Phase 5 — Continuation / Hot Replica

Build coherent checkpoint and promotion-grade continuation using the Spike C oracle.

Prefer vanilla-compatible dirty serialization first.

## Phase 6 — Semi-sync durability

Hot Replica ACK gates visual fan-out, not world simulation.

Add timeout degradation to ASYNC/DEGRADED mode.

## Phase 7 — Full entity ownership

Expand beyond V0 cow/item:

```text
projectiles
passengers
leashes
vehicles
modded entities
entity graphs
```

## Phase 8 — Entity failover continuation

Promotion-grade entity persistence and ownership-generation recovery.

## Phase 9 — Events and global state

Presentation events + missing cluster-global state coordination.

## Phase 10 — Remote menu sessions

Only where measured need justifies it.

Prefer locality/affinity/pinning for unsupported complex mod menus.

## Phase 11 — Foundations-aware Conduit continuity

Only after backend world/entity equivalence is proven.

Goal: avoid unnecessary clear/repopulate behavior for certified same-world handoffs.

## Phase 12 — Direct worker replication transport

Move high-frequency live stream off Manager relay while preserving Manager control/lease/epoch authority.

## Phase 13 — Performance hardening

```text
buffer pooling
primitive collections
adaptive compression
interest management
backpressure tuning
stress/soak profiling
```

## Phase 14 — Multi-host / HA expansion

Return to retained larger roadmap:

```text
remote worker hosts
secure cross-host control plane
shared/durable storage abstractions
network-aware placement
proxy/control-plane HA
node-loss recovery
```

---

# 19. Deferred / Not Started Long-Term Areas

Still outside the immediate V0 proof:

```text
full dynamic automatic cell split/merge policy
large-scale automatic scaling production acceptance
multi-host worker placement
shared distributed metadata/storage
high availability Manager
redundant gateway/public endpoint
complete mod compatibility catalog
TFC/custom calendar replication
complex remote menus
production entity-ID continuity optimization
cluster-global sleep voting
all SavedData/global systems
full multi-machine failover soak testing
```

---

# 20. Key Current Source Files

## Manager

```text
cluster-manager/src/main/java/com/foundations/cluster/manager/ClusterVersion.java
cluster-manager/src/main/java/com/foundations/cluster/manager/RegionAuthorityManager.java
cluster-manager/src/main/java/com/foundations/cluster/manager/HandoffManager.java
cluster-manager/src/main/java/com/foundations/cluster/manager/SessionManager.java
cluster-manager/src/main/java/com/foundations/cluster/manager/PersistentBlockJournal.java
cluster-manager/src/main/java/com/foundations/cluster/manager/PersistentBlockEntityJournal.java
cluster-manager/src/main/java/com/foundations/cluster/manager/DataPlaneManager.java
cluster-manager/src/main/java/com/foundations/cluster/manager/WorldEnvironmentCoordinator.java
cluster-manager/src/main/java/com/foundations/cluster/manager/WorkerManager.java
```

## Node

HF11 growth-related source:

```text
minecraft-agent/src/main/java/com/foundations/cluster/node/FoundationsClusterNode.java
minecraft-agent/src/main/java/com/foundations/cluster/node/RemoteTickLeases.java
minecraft-agent/src/main/java/com/foundations/cluster/node/mixin/GrowthTickMixin.java
minecraft-agent/src/main/java/com/foundations/cluster/node/PersistenceProbe.java
```

Review all existing mixins/capture code before adding new mutation hooks.

## Gateway

```text
velocity-gateway/src/main/java/com/foundations/cluster/velocity/FoundationsVelocityGateway.java
```

Conduit overlay/bridge material is retained under `tools/` and the active proxy artifacts are under `velocity/`.

## WebUI

```text
web/app.js
web/index.html
web/styles.css
web/operations.js
```

Current browser logic derives the required Node version from Manager. Do not reintroduce hard-coded R11/R12 checks.

---

# 21. Build and Update Standards to Preserve

Every future coordinated update should preserve the established quality bar:

```text
prompt for Cluster root
support folder picker/manual fallback where used
verify starting version
require relevant processes stopped
back up every changed file
preserve worlds/runtime/profiles/security/config
replace Node in template AND existing workers
reject stale duplicate Node/plugin JARs
validate embedded output versions
rollback on partial failure
print backup location
handle mixed/partial installs explicitly
```

Do not require the BAT to live in the project root.

---

# 22. Testing Philosophy

Do not substitute fixture/unit success for live-client acceptance.

Keep evidence categories separate:

```text
unit/source tests
Manager HTTP fixture tests
headless NeoForge integration
actual Windows updater execution
actual joined-client gameplay
recorded client-visible seamlessness
```

A successful generated tree fixture is not proof of natural growth.

A successful headless natural-growth test is not proof of client-visible seamlessness.

A READY Node badge is not proof of world continuity.

---

# 23. Agent Continuation Checklist

Before changing code:

```text
[ ] Read READ_ME_FIRST.md
[ ] Verify ClusterVersion.java = HF11 / required Node R13
[ ] Verify Node gradle.properties/mods.toml = R13
[ ] Read HF11 natural-growth validation
[ ] Read v2.2 candidate design
[ ] Read final design-review addendum
[ ] Do not alter frozen design without spike evidence
```

Then begin:

```text
[ ] Spike A harness/spec
[ ] Spike C harness/spec
[ ] add Spike B instrumentation
[ ] build Spike D offline trace analyzer
[ ] record all evidence using previous assumption / evidence / decision / reason
```

After spikes:

```text
[ ] build V0-CONTROL first
[ ] preserve its artifacts
[ ] build V0-SEAM
[ ] execute transaction crash matrix
[ ] execute entity transfer crash matrix
[ ] compare against V0 gates
```

Only then proceed to the long replication roadmap.

---

# 24. Definition of the Next Milestone

The next meaningful milestone is **not HF12 simply existing**.

It is:

> **A measured V0 seam proof showing that two workers can approach a scripted single-worker control for block interaction, natural world mutation, one simple entity ownership transfer, item pickup, and player seam crossing without duplicate state or visible architectural failure.**

That milestone is the gate for the remainder of World Replication v2.2.
