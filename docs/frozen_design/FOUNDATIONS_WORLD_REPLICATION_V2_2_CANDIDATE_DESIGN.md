# Foundations Cluster — World Replication v2.2 Candidate Design

**Project:** Foundations Cluster Runtime  
**Target line:** Post-HF11 / Node R13  
**Minecraft:** 1.21.1  
**NeoForge:** 21.1.250  
**Status:** **Candidate architecture — spike-gated, not yet frozen for implementation**  
**Supersedes:** World Replication v2.1 design + design-review addendum  
**Primary goal:** Make multiple backend workers behave like one continuous Minecraft world by keeping player simulation local to the player's anchor, routing world-changing intent to the correct authority, simulating each world domain exactly once, continuously replicating observable results, maintaining promotion-grade continuation state where required, and switching workers only after revision convergence.

---

# 1. Executive Summary

Foundations Cluster is moving from snapshot-oriented replication toward an authoritative distributed simulation model.

The design now centers on six rules:

```text
1. PLAYER LOCALITY FIRST
   The real ServerPlayer lives and simulates on its network anchor.

2. ONE WORLD WRITER
   A simulation cell has exactly one authority for world state.

3. ROUTE WORLD-CHANGING INTENT
   Player/world actions that target another authority are executed there.

4. REPLICATE RESULTS, NOT DUPLICATE SIMULATION
   Visual replicas apply authoritative outcomes.

5. REPLICATE CONTINUATION STATE TO PROMOTION CANDIDATES
   A hot replica must be able to continue from a coherent recovery boundary.

6. SWITCH ONLY AFTER REVISION CONVERGENCE
   Backend handoff occurs only when the destination is observably caught up.
```

The architecture is divided into:

```text
FOUNDATIONS CONTROL PLANE
    authority / epochs / leases / routing / topology

PLAYER PRESENCE + EFFECT PLANE
    anchor → authority: remote player presence
    authority → anchor: player effects

AUTHORITY INTENT PLANE
    player/world actions that must execute on the owner

LIVE STATE PLANE
    blocks / BlockEntities / lighting / entities / presentation events

CONTINUATION PLANE
    scheduler state / durable entity state / BE state / checkpoints / failover log
```

Before v2.2 is frozen for implementation, three required spikes must answer:

```text
A. What actually stops simulating when the real player is anchored elsewhere?
B. What is the measured end-to-end worker handoff latency?
C. What does Minecraft 1.21.1 actually preserve through a normal save/reload?
```

A fourth recommended spike should determine practical simulation-cell size.

The design below is normative where settled and explicitly marks spike-dependent decisions.

---

# 2. Terminology

## 2.1 Network Anchor

The worker hosting the real connected `ServerPlayer`.

It owns:

```text
network connection
movement packet processing
player movement simulation
player inventory
player health
player effects
player statistics/advancements
menu protocol state unless explicitly proxied
```

The real player's simulation and the network anchor are coupled by construction.

---

## 2.2 Simulation Cell

The proposed unit of simulation authority.

A Simulation Cell is intentionally separate from Minecraft's `.mca` storage region.

Possible future sizes:

```text
1×1 chunks
2×2 chunks
4×4 chunks
8×8 chunks
16×16 chunks
```

Final size is spike/benchmark driven.

The storage layer may remain:

```text
32×32 chunk .mca region
```

while authority is assigned at a finer Simulation Cell scale.

---

## 2.3 Authority

The worker allowed to mutate authoritative world state for a Simulation Cell.

Authority owns:

```text
random ticks
scheduled block ticks
scheduled fluid ticks
BlockEntity simulation
entity simulation
world-changing callbacks
spawning
world-side events
world-side mod logic
```

---

## 2.4 Visual Replica

A non-authoritative copy maintained for:

```text
client continuity
handoff preparation
near-boundary visibility
remote-player anchor support
```

A Visual Replica is not automatically eligible for failover promotion.

---

## 2.5 Hot Replica

A Visual Replica with enough continuation state to satisfy the defined recovery correctness target.

A Hot Replica is eligible for authority promotion if:

```text
epoch is valid
checkpoint is coherent
continuation log is current enough
required RPO policy is satisfied
```

---

## 2.6 Authority Affinity Group

A set of Simulation Cells intentionally co-located on the same authority worker because they are strongly coupled.

Cells keep separate identity/epochs/checkpoints.

Affinity is placement, not destructive region merging.

Example:

```text
Cell A ─┐
Cell B ─┼── worker-003 authority
Cell C ─┘
```

---

# 3. Core Invariants

## 3.1 One World Writer

For each Simulation Cell and authority epoch:

```text
exactly one worker may perform authoritative world mutation
```

No two workers may authoritatively simulate the same cell in the same epoch.

---

## 3.2 Player State and World State Have Different Writers

The anchor is the single writer of player state.

The Simulation Cell authority is the single writer of world state.

Therefore:

```text
anchor may not directly mutate remote-authority world state

authority may not directly mutate remote-anchor player state
```

Cross-domain effects must use explicit operations.

---

## 3.3 No Cross-JVM Tick Consistency

Worker-local Minecraft tick counters are diagnostic only.

Never gate handoff or failover on:

```text
workerA.tick >= workerB.tick
```

Consistency uses:

```text
world identity
cell identity
authority epoch
frame sequence
state revisions
checkpoint generation
continuation-log position
```

---

## 3.4 Replica Writes Are Mechanically Fenced

"Replicas should not simulate" is not enough.

A central predicate must exist:

```text
isAuthoritative(level, cell)
```

Every authoritative path should be audited through it:

```text
random ticks
scheduled block ticks
scheduled fluid ticks
entity ticks
BlockEntity tickers
spawners
world-side level tick handlers
commands / command blocks where world mutation occurs
mod mutation hooks where interceptable
```

Any replica-side world mutation outside `ReplicationApplyContext` is a violation.

---

# 4. Replica Write Guard

## 4.1 Development Behavior

```text
illegal replica mutation
    ↓
reject
    ↓
capture stack trace
    ↓
record mod namespace / block / entity / cell / callsite
    ↓
surface compatibility warning
```

---

## 4.2 Production Behavior

Do not blindly redirect arbitrary writes.

Possible policy:

```text
known routable operation:
    route as intent

unknown mutation:
    fence/reject
    emit rate-limited warning
    record compatibility violation
```

Blind redirect is unsafe because a mod may already have:

```text
consumed energy
decremented inventory
changed an internal Java field
advanced recipe progress
```

before reaching the intercepted block write.

---

# 5. Per-Mod Compatibility Policy

Each mod namespace may acquire a policy:

```text
AUTO
ROUTE
REJECT
PIN
```

## AUTO

Default observation mode.

Violations are collected and scored.

## ROUTE

Known mutation semantics can be safely converted to authority operations.

## REJECT

Unsafe remote mutation is rejected and surfaced.

## PIN

Affected cells/content are strongly affinity-pinned to one authority.

Repeated violations feed:

```text
compatibility report
affinity score
pin recommendation
operator diagnostics
```

---

# 6. Player Locality Rule

The real `ServerPlayer` remains on the worker that owns the network anchor.

Because movement is processed there, player movement/physics is inherently anchor-local.

Therefore:

> **Do not intentionally keep a player anchored remotely from the Simulation Cell they physically occupy for long periods.**

Remote-anchor operation is a temporary seam condition only.

---

# 7. Player Movement vs World Mutation

Movement-side processing is split by ownership domain.

## 7.1 Player-Local / Kinematic Effects

The anchor may evaluate effects that modify only player-local state and are required for movement continuity.

Candidate examples:

```text
collision shape
movement slowdown
friction
velocity adjustment
camera/pose consequences
player-local swimming/motion handling
```

Exact classification must be tested.

---

## 7.2 World-Authoritative Effects

Callbacks that mutate the world or cause authoritative gameplay must execute on the cell authority.

Examples:

```text
farmland trampling
pressure-plate state
tripwire state
redstone mutation
block destruction
GameEvent emission
portal/world-transition initiation
world-changing mod callbacks
```

---

## 7.3 Unknown Mod Callback Default

If a mod callback cannot be safely classified as player-local:

```text
default → authority-side evaluation
```

Do not allow arbitrary replica callback execution merely because the real player ticks there.

---

# 8. Seam-Band Player Presence

Remote Player Presence exists to bridge the short period before the network anchor and world authority become co-located.

It is not intended as a permanent architecture.

Flow:

```text
player approaches authority seam
        │
        ▼
destination/preferred authority receives remote presence
        │
        ▼
chunks/entities/state prewarm
        │
        ▼
revision barrier approaches ready
        │
        ▼
network anchor handoff
        │
        ▼
old remote presence removed
```

---

# 9. Presence Tiers

The exact minimum presence representation is spike-dependent.

Plan for three levels.

## 9.1 L0 — Ticket Presence

Purpose:

```text
chunk interest
tick interest
prewarm
```

No entity/player semantics.

---

## 9.2 L1 — Targetable Entity Proxy

Purpose:

```text
entity lookup presence
mob targeting
nearby-entity interactions
GameEvent source/receiver support
world-side collision proximity
```

Exact implementation TBD.

---

## 9.3 L2 — Player-Compatible Presence

Used only where vanilla/mod logic requires player-like behavior.

Risks:

```text
duplicate UUID assumptions
player-list assumptions
statistics/advancement hooks
mod listeners
login/logout side effects
ticket semantics
```

L2 must be proven by spike before becoming a dependency.

---

# 10. Player Presence → Player Effect

The remote authority must not directly write real player state.

Authority produces:

```text
PlayerEffect
{
    playerSession
    sourceCell
    sourceEpoch
    causalOperationId
    effectType
    payload
}
```

Possible effects:

```text
damage
knockback
status effect
pickup result
portal request
interaction result
```

The anchor validates and applies the effect to the real player.

---

# 11. Velocity-Scaled Prewarm Distance

A fixed seam halo is insufficient for fast movement.

Prewarm distance should scale with observed velocity and measured latency.

Concept:

```text
prewarmDistance =
    baseMargin
    +
    speed × (
        handoffLatencyP99
        + replicationCatchupP99
        + safetyMargin
    )
```

Clamp to configurable:

```text
minimum prewarm
maximum prewarm
```

Test at least:

```text
walking
sprinting
boat
minecart
elytra
elytra + rocket
modded vehicles
```

---

# 12. Seam Hysteresis

Entering and exiting seam/prewarm state should use different thresholds.

Avoid:

```text
A → B → A → B
```

while a player stands near a boundary.

Example concept:

```text
enter prewarm at distance X
exit prewarm only after distance Y
where Y > X in the return direction
```

Exact policy TBD.

---

# 13. Remote-Anchor Deadline

A player must not remain indefinitely anchored away from the cell they physically occupy.

Track:

```text
remoteAnchorStartedAt
remoteAnchorDuration
```

After configurable deadline:

```text
force handoff
or
co-locate relevant authority
or
expand affinity placement
or
mark topology degraded
```

Never silently permit permanent split locality.

---

# 14. Vehicles and Passenger Stacks

Vehicles create strong affinity.

Treat:

```text
vehicle
rider
passengers
```

as one simulation/locality unit.

Do not independently assign their authorities during normal movement.

Fast vehicles also increase prewarm distance.

---

# 15. Authority Intent Plane

The Intent Plane routes actions to the cell authority.

Phase-1 scope should begin with block/world interactions that do not require full entity-shadow support.

Examples:

```text
block break
block place
simple item use
simple block interaction
one-shot world mutation
```

Entity interaction is deferred until entity replication primitives exist.

---

# 16. InteractionIntent

Concept:

```text
InteractionIntent
{
    sessionId
    inputSequence

    sourceAnchor
    targetCell
    targetEpoch

    causalOperationId
    actionType

    targetPosition?
    targetClusterEntity?
    face?
    hand?

    heldItemContext
    playerPosition
    playerRotation
    selectedSlot
}
```

Authority validates the intent against current state before executing it.

---

# 17. Prediction and Client Ack Ordering

For predicted client actions:

```text
anchor must not release acknowledgement
before authoritative result is visible locally
```

Required ordering:

```text
intent sent
    ↓
authority executes
    ↓
result enters live frame R
    ↓
anchor applies through R
    ↓
anchor releases ack(inputSequence)
```

Rule:

```text
ack(inputSequence)
only after
appliedSequence >= resultFrameSequence
```

This avoids visible:

```text
client predicts
server acks too early
prediction reverts
result arrives
state reapplies
```

---

# 18. Cross-Cell Discrete Operations

Use `CrossCellOperation` for discrete multi-cell events:

```text
explosion
structure/tree placement
one-shot bulk edit
explicit scripted multi-cell mutation
```

Concept:

```text
CrossCellOperation
{
    operationId
    originCell
    originEpoch

    affectedCells[]
    mutationGroups[]

    preconditions[]
}
```

Each target authority applies only its own mutation group.

---

# 19. Preconditions and Revalidation

A source cell may compute an operation from a replica that is 0–1 frames old.

Therefore target authorities must revalidate.

Example:

```text
explosion computed affected block X
    ↓
target authority receives operation
    ↓
precondition no longer matches
    ↓
recompute/revalidate/reject affected mutation
```

Never assume the origin's replica of another authority is perfectly current.

---

# 20. Continuous Cross-Cell Coupling

Do not model tightly coupled systems as endless distributed RPC.

Examples:

```text
redstone networks
piston chains
fluid seams
observers/comparators
neighbor/shape updates
pipe grids
large mod energy/fluid/item networks
```

Preferred solution:

> **co-locate strongly coupled Simulation Cells through authority affinity.**

---

# 21. Authority Affinity Scoring

Affinity inputs should include:

```text
cross-cell operation rate
neighbor-update rate
fluid seam traffic
redstone seam traffic
player seam presence
vehicle seam presence
repeated mod write violations
shared mod-network identity
handoff frequency
```

---

# 22. Affinity Hysteresis

Avoid placement flapping.

Concept:

```text
high coupling sustained
    → co-locate

coupling drops
    → wait cooldown / hysteresis

sustained low coupling
    → cells eligible for separation
```

---

# 23. Affinity Group Size Cap

Co-location is not free.

Define limits based on:

```text
worker MSPT
heap
entity count
loaded chunks
replication demand
host capacity
```

If a group exceeds capacity:

```text
identify weakest coupling cut
split at safe barrier
or
move group to larger-capacity worker
or
mark topology pressure
```

---

# 24. Storage Regions vs Simulation Cells

Do not automatically use `.mca` 32×32-chunk storage regions as simulation authority units.

Separate:

```text
Storage Region:
    disk layout / vanilla persistence

Simulation Cell:
    authority / replication / handoff unit
```

Benefits:

```text
smaller affinity moves
smaller handoff scope
less mega-region behavior
more precise load placement
```

Costs:

```text
more ownership metadata
more cell boundaries
more potential cross-cell operations
```

Final cell size is spike/benchmark driven.

---

# 25. Stateful Menus Are Sessions

A remote container is not a one-shot intent.

Typical menu protocol:

```text
open
state ID
slot sync
click
drag
quick move
carried item
more clicks
close
```

Modded menus may have custom packets and assume a real local player.

---

# 26. Menu Locality Rule

Default:

> **Player locality wins over container locality.**

Do not normally migrate the player's anchor away from where the player's body physically stands merely to open a remote chest.

---

# 27. Menu Session Strategy

## 27.1 Local Menu

If anchor and container authority are co-located:

```text
use normal vanilla/NeoForge menu behavior
```

---

## 27.2 Remote Simple Menu

Future ordered session protocol may proxy:

```text
open
container/menu identity
state IDs
slot revisions
click order
drag state
quick move
close
```

This is a separate feature from simple intents.

---

## 27.3 Unsupported / Complex Mod Menu

Fallback:

```text
raise affinity
pin relevant cell
or
briefly migrate only if locality remains safe and measured latency supports it
```

Do not force a generic distributed-menu implementation for every mod.

---

## 27.4 Epoch Change During Menu

If authority changes mid-session:

```text
close session cleanly
reopen only after new authority is ready if safe
never duplicate item transfer
```

---

# 28. Live State Plane

Purpose:

```text
make visual replicas look like authority now
```

Carries:

```text
block changes
BlockEntity visible state
lighting
entity movement/state
entity spawn/remove
presentation events
selected global state
```

Target:

```text
0–1 replication frames behind authority
```

---

# 29. Per-Cell Frame Sequence

Each authoritative Simulation Cell maintains:

```text
cellId
authorityEpoch
frameSequence
```

Frame may contain:

```text
blockRevision
blockEntityRevision
lightRevision
entityRevision
eventRevision
```

Replica reports:

```text
APPLIED
cell=X
epoch=E
throughSequence=S
```

This is the convergence primitive.

---

# 30. Block Delta Capture

Use authoritative mutation capture into frame-local buffers.

Concept:

```text
CellDeltaFrame
{
    cellId
    epoch
    sequence

    blockChanges[]
    blockEntityChanges[]
    lightChanges[]
    events[]
}
```

Block representation should prefer compact primitives:

```text
packed position
state ID
```

---

# 31. Per-Frame Coalescing

Multiple changes to the same block before flush should coalesce to final state where semantics permit.

```text
0 → 1 → 2 → 3
```

becomes:

```text
0 → 3
```

unless intermediate states themselves produce separately required presentation/simulation events.

---

# 32. Capture Completeness

Primary context-aware hook:

```text
LevelChunk.setBlockState
```

Potential deeper detection:

```text
LevelChunkSection.setBlockState
```

but section-level hooks lack world/cell context and must be wrapped carefully.

Do not assume one hook catches:

```text
worldgen
bulk tools
mod direct writes
structure operations
```

---

# 33. Integrity Sweep

Do not fingerprint every active section every frame by default.

Use rolling integrity checks:

```text
bounded sections per tick
priority for handoff cells
priority after compatibility violations
priority after suspected bypass
```

Escalate to immediate scan when necessary.

Goal:

```text
detect silent bypass
without exceeding capture budget
```

---

# 34. Bulk Mutation Scope

Large operations should declare or be detected as bulk changes.

Representation crossover:

```text
few changes
    → packed block deltas

medium density
    → section delta

dense changes
    → section/chunk replacement
```

Thresholds are benchmark driven.

---

# 35. ReplicationApplyContext

Replica application must suppress authoritative gameplay callbacks.

It may update:

```text
block state/palette
chunk bookkeeping
heightmaps where required
BlockEntity lifecycle
light data
tracking dirty state
client-facing vanilla tracking
```

It must not create:

```text
duplicate drops
duplicate neighbor gameplay
duplicate redstone simulation
duplicate fluid simulation
duplicate mod side effects
```

---

# 36. Lighting — Default Policy

Correctness-first default:

> **replicate authority-resolved light data for changed sections.**

Reasons:

```text
deterministic
verifiable
coalescible
handoff barrier can prove applied revision
avoids waiting on replica async light completion
```

Replica recomputation remains an optional later optimization for small changes if benchmarks prove worthwhile.

Maintain:

```text
lightRevision
```

---

# 37. BlockEntity Compatibility

Three tiers.

## Tier A — Native

Uses correct:

```text
setChanged
update notifications
NBT
attachments
```

## Tier B — Integrity

Periodically serialize/hash authoritative state in active/handoff-critical cells.

Adaptive frequency.

## Tier C — Provider API

Optional mod-specific replication hooks.

Generic cluster software cannot reconstruct state a mod never exposes.

---

# 38. Continuation Plane

A visual replica is not enough for failover.

Continuation state defines what the Hot Replica must have to safely become authority.

The minimum recovery correctness baseline is:

> **equivalent to a clean vanilla/NeoForge save → stop → reload for the same scenario, unless Foundations explicitly documents stronger guarantees.**

---

# 39. Recovery Correctness vs Seamless Continuity

## Recovery Correctness

Required.

Equivalent to clean vanilla/NeoForge persistence semantics.

## Seamless Continuity

Optional stronger preservation.

May preserve more transient state than vanilla save/reload if useful and supportable.

Do not make stronger continuity a prerequisite unless measured value justifies complexity.

---

# 40. Coherent Checkpoint Boundary

A checkpoint must represent one coherent frame boundary.

Invalid/torn example:

```text
blocks at revision 100
BlockEntities at 99
scheduler at 101
entities at 98
```

Checkpoint metadata should include:

```text
cell
epoch
checkpointGeneration
throughFrameSequence
component revisions
```

Promotion must never use an internally torn checkpoint.

---

# 41. Scheduler Continuation

Do not resend full scheduler queues every frame.

Use:

```text
periodic coherent scheduler checkpoint
+
live scheduler operation log
```

Operations:

```text
schedule block tick
consume block tick
cancel/replace block tick

schedule fluid tick
consume fluid tick
cancel/replace fluid tick
```

---

# 42. Entity Recovery Baseline

Entity persistence must be treated as separate from chunk-only persistence.

Recovery comparison must include the real mechanisms used by 1.21.1 for:

```text
entity storage
chunk state
BlockEntities
block/fluid scheduled ticks
SavedData
attachments where applicable
POI/global state where relevant
```

Do not assume `ChunkSerializer` alone defines complete world recovery.

---

# 43. Replica Classes

## Visual Replica

```text
live blocks
visible BE state
lighting
entity presentation
events
```

Used for:

```text
handoff continuity
nearby visibility
seam support
```

## Hot Replica

Visual Replica plus:

```text
coherent checkpoints
scheduler continuation
promotion-grade BE/entity state
continuation operation log
```

Used for:

```text
failover promotion
```

Recommended starting topology:

```text
1 authority
1 hot replica
N visual replicas
```

---

# 44. Semi-Synchronous Hot Replica

Do not stall Minecraft simulation waiting for hot-replica ACK.

Authority keeps ticking.

Semi-sync gates **visual fan-out**, not authority simulation.

Flow:

```text
authority generates frame N
        │
        ├── local simulation immediately continues
        │
        ├── frame N → hot replica
        │                   │
        │                  ACK
        │                   │
        └──────────────► release frame N to visual replicas
```

---

# 45. Semi-Sync Timeout and Degraded Mode

Hot replica must not be allowed to stall visible world delivery indefinitely.

If ACK exceeds threshold:

```text
timeout
    ↓
release visual frame
    ↓
region durability mode = DEGRADED_ASYNC
    ↓
alert operator
```

Authority continues ticking throughout.

Expose:

```text
SEMI_SYNC
ASYNC
DEGRADED_ASYNC
```

in telemetry.

---

# 46. Failover Guarantees

Do not claim "zero rollback / zero stall" generically.

Define explicit:

```text
RPO = maximum accepted lost authoritative frames/state
RTO = maximum target recovery time
```

Initial values are implementation/benchmark dependent.

Hard guarantees:

```text
no dual writer
no duplicate committed causal operation
epoch fencing maintained
promotion uses coherent checkpoint/log
```

---

# 47. Visual Replica Ahead of Hot Replica

A dangerous state:

```text
visual replica applied frame 100
hot replica durable through frame 97
authority crashes
```

Promoting hot replica could visibly roll players back.

Policy options:

```text
semi-sync gate visual fan-out
or
accept documented bounded RPO
```

v2.2 prefers semi-sync for selected/critical cells with timeout degradation.

---

# 48. Presentation Events

Replicate presentation effects separately when state alone is insufficient.

Examples:

```text
sounds
particles
explosion visual/audio
block break effects
entity status animation
selected block events
```

---

# 49. Simulation Events

Gameplay-affecting events execute only on authority.

Examples:

```text
GameEvent / sculk
entity damage
explosion gameplay
neighbor updates
block-event logic
```

Replicate resulting state and required presentation.

---

# 50. Cluster-Global State

Explicitly account for:

```text
time
weather
world border
difficulty
selected gamerules
scoreboards
teams
boss bars
raids
wandering trader state
patrol/phantom spawner state
sleep / time skip
datapack scheduled commands/functions
dragon fight state
map data
global SavedData where relevant
```

Some may remain coordinated by existing Foundations environment/global systems rather than per-cell replication.

---

# 51. Entity Live Replication — Later Phase

Entity shadows remain high risk and are not Phase 1.

Authority owns:

```text
AI
pathfinding
physics
collision
damage
combat
drops
despawn
gameplay
```

Replica shadow maintains only presentation/tracking state.

---

# 52. Canonical Entity Identity

Do not require identical vanilla integer entity IDs across workers initially.

Use:

```text
Minecraft UUID
+
Foundations cluster entity identity
```

Each worker may have:

```text
cluster entity X

worker A local vanilla ID 193
worker B local vanilla ID 648
```

Maintain mapping.

Stable vanilla integer IDs remain an experimental optimization only.

---

# 53. Handoff Revision Barrier

Destination handoff readiness uses revisions, never worker tick numbers.

Example required state:

```text
worldIdentity
cellId
epoch

requiredSequence
blockRevision
blockEntityRevision
lightRevision
entityRevision
eventRevision
```

Destination must prove at least those revisions.

---

# 54. Replication Continues During Handoff

Never intentionally stop live replication around backend switch.

```text
authority
    │
    ├── live frames
    ▼
destination catches up
    │
    ▼
barrier passes
    │
    ▼
Conduit switch begins
    │
    ├── live frames continue
    ▼
switch commits
```

---

# 55. Authority Migration

Graceful authority move:

```text
destination live state converges
        │
        ▼
destination continuation state converges
        │
        ▼
checkpoint/log boundary agreed
        │
        ▼
old authority ends at boundary
        │
        ▼
epoch increments
        │
        ▼
new authority starts
```

Never allow both workers to commit authoritative state in the same epoch.

---

# 56. Direct Worker Data Plane

Manager remains control plane.

High-frequency replication should eventually use a persistent stream abstraction:

```text
Manager:
authority / epoch / authorization / routing

Worker A ═════════════► Worker B
         live stream
```

Define the stream interface early.

Initial implementation may use Manager relay as a transport backend.

Later direct worker streaming should not require redesigning replication semantics.

---

# 57. Transport Requirements

```text
ordered frames
cell + epoch identity
sequence numbers
gap detection
bounded queues
backpressure
repair requests
checksums
frame-size limits
health telemetry
adaptive compression
```

Avoid one HTTP request per mutation.

---

# 58. Divergence Detection

Layered checks may include:

```text
frame sequence
section revision
chunk checksum
cell checkpoint checksum
BlockEntity hash
entity-set checksum
```

Mismatch:

```text
mark destination NOT READY
    ↓
repair
    ↓
revalidate
```

---

# 59. Backpressure

```text
small lag:
    continue

moderate lag:
    coalesce more aggressively

large lag:
    replace queued detail with section/chunk repair

critical lag:
    destination NOT HANDOFF READY
```

Queues remain bounded.

---

# 60. Performance Philosophy

Replication must cost less than duplicate simulation.

Authority:

```text
simulation
+
delta capture
```

Visual replica:

```text
no duplicate authoritative simulation
+
state apply
+
vanilla client tracking
```

Hot replica adds continuation overhead only where needed.

---

# 61. Initial Performance Targets

Engineering targets, not guarantees:

```text
authority capture:
< 0.5 ms/tick typical

replica apply:
< 1.0 ms/tick typical

total replication main-thread overhead:
preferably < 2.0 ms/tick typical

normal live lag:
0–1 frames

normal queue:
0–1 frames

routine snapshot fallback:
near zero
```

---

# 62. Phase 0 — Required Architecture Spikes

Do not freeze v2.2 implementation details until Spikes A–C complete.

Spike D is strongly recommended.

---

## 62.1 Spike A — Playerless Authority Simulation

### Question

What actually stops or changes when all real players are anchored elsewhere?

### Test modes

```text
1. chunk loaded only
2. forced/block-ticking ticket
3. entity-ticking ticket
4. L0 ticket-only presence
5. L1 targetable proxy
6. L2 player-compatible presence
```

### Measure

```text
random crop/tree ticks
scheduled block ticks
scheduled fluid ticks
BlockEntity ticks
normal entity ticks
mob spawning
mob despawning
mob targeting
pressure plates
tripwires
GameEvents / sculk
item pickup
portal contact
farmland trampling
player/world callbacks
```

### Exit condition

A matrix defining the minimum presence tier required for each gameplay category.

---

## 62.2 Spike B — Real Handoff Latency

### Measure timestamps

```text
T0 seam crossing predicted
T1 destination prewarm starts
T2 destination live state ready
T3 revision barrier reached
T4 proxy switch begins
T5 destination accepts
T6 client interaction resumes
```

### Report

```text
median
p95
p99
worst
```

### Movement profiles

```text
walking
sprinting
boat
minecart
elytra
elytra + rocket
modded fast vehicle if available
```

### Determines

```text
prewarm distance
velocity lookahead
remote-anchor deadline
whether any menu-related temporary migration is viable
handoff safety margin
```

---

## 62.3 Spike C — Vanilla/NeoForge Persistence Oracle

### Scenario

Create controlled world state containing:

```text
pending redstone
flowing fluid
growing crop
furnace
hopper
chest
villager
hostile mob
projectile
minecart
passenger relationship
effects
modded BlockEntity
NeoForge attachment
SavedData/global-state examples
```

### Branch A

```text
normal vanilla/NeoForge save
stop
reload
```

### Branch B

```text
Foundations coherent checkpoint
hot-replica promotion
```

### Compare

```text
blocks
BlockEntities
block/fluid scheduled ticks
entities
inventories
attachments
SavedData
POI where relevant
global state where relevant
```

### Exit condition

A documented Recovery Correctness oracle and explicit list of state that vanilla itself does not preserve.

---

## 62.4 Spike D — Simulation Cell Size

Recommended.

Test:

```text
1×1
2×2
4×4
8×8
16×16
32×32 chunks
```

Measure:

```text
ownership metadata
cross-cell op rate
neighbor traffic
affinity frequency
replication volume
handoff prep cost
scheduler placement flexibility
```

Do not choose cell size solely from storage format convenience.

---

# 63. Implementation Order After Phase 0

## Phase 1A — Seam-Band Presence + Block Intents

Implement:

```text
cell authority lookup
replica write guard
remote seam presence scaffolding
block break/place/use intents
input sequencing
causal IDs
ack release after result frame
```

No entity-interaction acceptance requirement yet.

---

## Phase 2 — Block Delta v1

Implement:

```text
per-cell frame sequence
block capture
coalescing
compact encoding
replica apply
snapshot fallback
```

Acceptance:

```text
natural tree growth reaches replica within 0–1 frame
without routine chunk snapshot refresh
```

---

## Phase 3 — Minimal Revision Barrier

Implement:

```text
world identity
cell identity
epoch
frame sequence
block revision
```

Acceptance:

```text
handoff cannot begin on stale block state
```

---

## Phase 4 — Capture Completeness

Implement:

```text
lower-level capture validation
rolling integrity sweep
bulk mutation mode
adaptive section/chunk fallback
```

---

## Phase 5 — Authority-Replicated Lighting

Implement:

```text
changed light-section capture
light revision
replica light apply
handoff light barrier
```

---

## Phase 6 — Affinity Scheduler

Implement:

```text
affinity scoring
cell co-location
hysteresis
group size/capacity limits
compatibility violation input
player/vehicle seam input
```

---

## Phase 7 — Continuation + Hot Replica

Implement:

```text
coherent checkpoint boundaries
scheduler checkpoint/log
semi-sync fan-out gating
timeout degradation
RPO/RTO telemetry
```

---

## Phase 8 — BlockEntity Compatibility

Implement:

```text
Tier A native tracking
Tier B integrity hashes
attachments
provider API
per-mod policy integration
```

---

## Phase 9 — Events + Global State

Implement:

```text
presentation events
simulation-event ownership
global-state coordination
```

---

## Phase 10 — Remote Menu Sessions

Only after Phase 0 latency/locality findings.

Implement only where justified.

Fallback to affinity/pinning for unsupported menus.

---

## Phase 11 — Entity Replication Prototype

Start simple.

Implement:

```text
cluster identity
worker-local ID mapping
shadow lifecycle
movement/state delta
spawn/remove
```

---

## Phase 12 — Entity Interaction Intent

Now extend Phase 1:

```text
attack remote entity
interact remote entity
vehicle interaction
pickup semantics
```

---

## Phase 13 — Entity Continuation / Failover

Implement recovery-grade entity persistence and promotion behavior based on the Spike C oracle.

---

## Phase 14 — Foundations-Aware Conduit Continuity

Only after backend state equivalence is proven.

Avoid unnecessary clearing/repopulation for certified same-world handoffs.

---

## Phase 15 — Direct Worker Streaming

Swap transport backend from Manager relay to authorized direct stream without changing replication semantics.

---

## Phase 16 — Performance Hardening

```text
buffer pooling
primitive structures
adaptive compression
adaptive encoding
interest management
backpressure tuning
stress profiling
```

---

# 64. Acceptance Matrix

## Player Locality / Seam

```text
walk across seam
stand exactly on seam
rapid back/forth movement
sprint across seam
elytra across seam
vehicle with passengers across seam
```

Requirements:

```text
no authority flapping
no indefinite remote anchor
correct world callbacks
no duplicate player effects
```

---

## Block Interaction

```text
break remote-authority block
place across seam
simple use interaction
farmland/trample scenario
pressure plate/tripwire
portal contact
```

Requirement:

```text
single authoritative result
prediction ack ordered after result frame
no duplicate world mutation
```

---

## Cross-Cell Coupling

```text
redstone across seam
piston chain across seam
fluid across seam
observer/comparator
large pipe/energy network
```

Requirement:

```text
either correct cross-cell behavior
or
scheduler co-locates cells by affinity
```

---

## Menus

```text
local chest
remote chest
hopper
furnace
modded container
rapid shift-click
drag
authority change while open
```

Requirement:

```text
no duplication
no ghost items
ordered state IDs
clean close/recovery
```

---

## Failover

Crash authority during:

```text
scheduled redstone
fluid flow
machine progress
entity activity
player near seam
```

Measure:

```text
RPO
RTO
duplicate operations
dual-writer violations
visual rollback
```

---

# 65. Observability

Cluster dashboard should show:

```text
authority cells
affinity groups
hot replicas
visual replicas
presence tier
remote-anchor duration
prewarm distance
intent latency
frame sequence
replication lag
continuation lag
durability mode
RPO estimate
RTO history
write-fence violations
mod compatibility policy
```

---

# 66. Suggested Diagnostics

```text
/foundations_replication_status

/foundations_cell_status <x> <z>

/foundations_presence_status <player>

/foundations_affinity_status <x> <z>

/foundations_intent_trace <player>

/foundations_replication_compare <workerA> <workerB>

/foundations_write_violations [modid]

/foundations_checkpoint_status <cell>

/foundations_handoff_trace <player>
```

Permission-gated.

---

# 67. Success Criteria

World Replication v2.2 is successful when:

1. the real player remains local to the worker responsible for their movement except for bounded seam transitions;
2. remote player presence provides exactly the simulation interest required without becoming a permanent duplicate player;
3. world-mutating callbacks execute on the correct cell authority;
4. replica-side illegal writes are mechanically detected/fenced;
5. per-mod policy can route, reject, or pin incompatible behavior;
6. continuous cross-cell systems are co-located when distributed execution becomes unsafe or too expensive;
7. Simulation Cell size is chosen from measurements rather than `.mca` convenience;
8. player interaction acknowledgements are not released before authoritative results are locally applied;
9. stateful menus never duplicate or lose inventory state;
10. live block mutation reaches visual replicas within the defined frame budget;
11. authority-resolved lighting is converged before handoff;
12. Hot Replica checkpoints are coherent at a frame boundary;
13. Recovery Correctness matches the documented vanilla/NeoForge save/reload oracle;
14. failover has explicit measured RPO/RTO instead of an absolute zero-loss claim;
15. semi-sync degradation never stalls authority simulation indefinitely;
16. no dual writer exists for the same cell/epoch;
17. handoff uses revisions/epochs, not cross-JVM tick counters;
18. entities can later be replicated without requiring stable vanilla integer IDs;
19. Manager remains control plane rather than permanent high-frequency bottleneck;
20. the player perceives one continuous Minecraft world.

---

# 68. Decisions

## D1 — Player Locality Wins

The real player remains anchored where their movement simulation occurs.

Remote anchoring is bounded seam behavior only.

---

## D2 — World State Has a Separate Authority

The player's anchor does not gain authority to mutate remote world state simply because the real player ticks there.

---

## D3 — Presence Is Tiered

Do not prematurely require a fake/full player.

Determine the minimum presence representation empirically.

---

## D4 — Simulation Ownership Is Finer Than Storage Ownership

Simulation Cells are decoupled from `.mca` region size unless benchmarks prove otherwise.

---

## D5 — Prefer Affinity Over Infinite Distributed Coupling

Strongly coupled cells should usually run on the same authority worker.

---

## D6 — Menus Are Sessions

Do not treat complex containers as one-shot intents.

---

## D7 — Authority Lighting Is the Correctness Baseline

Replica relighting is an optimization, not the initial correctness path.

---

## D8 — Recovery Baseline Is Vanilla/NeoForge Save/Reload Equivalence

Foundations may exceed it later, but should not accidentally require impossible transient-state preservation.

---

## D9 — Semi-Sync Gates Fan-Out, Not Simulation

A slow Hot Replica may degrade durability mode but does not pause world ticking indefinitely.

---

## D10 — Stable Vanilla Entity IDs Are Optional Research

Canonical cluster identity comes first.

---

# 69. Rejected / Deferred Alternatives

## Permanent Remote Player Anchors

Rejected as default because player movement/callback behavior becomes split across workers indefinitely.

Remote anchoring remains a bounded seam mechanism only.

---

## Blind Replica Write Redirection

Rejected because the intercepted write may be only the final side effect of already-mutated mod state.

Use per-mod policy.

---

## Full Physical Region Merge/Split

Deferred in favor of affinity groups with stable Simulation Cell identity.

---

## `.mca` Region as Mandatory Authority Unit

Rejected pending measurement.

Storage format should not dictate simulation topology.

---

## Cross-JVM Tick Equality

Rejected as a consistency primitive.

Use epoch/sequence/revisions.

---

## Every Replica Is Failover-Ready

Rejected as unnecessarily expensive.

Use Visual vs Hot Replica classes.

---

## Zero-Loss / Zero-Stall Hard-Crash Promise

Rejected as an unconditional requirement.

Use measured RPO/RTO.

---

## Stable Vanilla Entity Integer IDs as Early Dependency

Rejected/deferred due allocator/tracker/mod compatibility risk.

---

## Routine Chunk Snapshots for Live Gameplay

Rejected as the primary synchronization model.

Snapshots remain bootstrap/repair/bulk-fallback mechanisms.

---

# 70. Evidence / Spike Appendix

This section is intentionally empty until the pre-implementation spikes are executed.

Each spike result should record:

```text
date
build
Node version
Manager version
Minecraft / NeoForge
test topology
hardware
configuration
raw metrics
observed behavior
conclusion
architectural decision changed?
```

Required entries:

```text
Spike A — Playerless Authority Simulation
Spike B — Real Handoff Latency
Spike C — Persistence Oracle
Spike D — Simulation Cell Size (recommended)
```

Do not silently rewrite the architectural assumptions after a spike.

Record:

```text
previous assumption
evidence
decision
reason
```

---

# 71. Final Architectural Rule

The refined Foundations model is:

```text
KEEP PLAYER SIMULATION LOCAL

PROJECT TEMPORARY PRESENCE ACROSS THE SEAM

ROUTE WORLD INTENT TO THE OWNER

SIMULATE WORLD STATE ONCE

CO-LOCATE STRONGLY COUPLED CELLS

REPLICATE OBSERVABLE RESULTS

REPLICATE COHERENT CONTINUATION STATE
TO HOT REPLICAS

FENCE EVERY STALE OR ILLEGAL WRITER

VERIFY REVISION CONVERGENCE

SWITCH ONLY WHEN READY
```

Not:

```text
LET REPLICAS RUN GAMEPLAY

KEEP PLAYERS REMOTELY ANCHORED INDEFINITELY

COMPARE JVM TICK NUMBERS

DISTRIBUTE EVERY REDSTONE NETWORK AT ANY COST

BLINDLY REDIRECT MOD WRITES

PROMOTE A VISUAL REPLICA JUST BECAUSE IT LOOKS CURRENT

FIX EVERYTHING LATER WITH CHUNK SNAPSHOTS
```

---

# 72. Target Player Experience

A player should be able to:

```text
walk or fly across a worker seam
break/place blocks
open containers
ride vehicles
watch trees grow
watch fluids flow
use redstone
run modded machines
fight mobs
hear/see explosions
survive a worker failure
```

while Foundations may internally change:

```text
network anchor
Simulation Cell authority
affinity placement
Hot Replica assignment
replication topology
authority epoch
worker process
```

without exposing those boundaries as a visible server replacement event.

That is the intended end state of **Foundations Cluster World Replication v2.2**.
