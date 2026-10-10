# 0.0.7a development 1 — inventory commit and save-image experiments

Baseline: HF16 / Manager API R19 / Node R14 / Minecraft 1.21.1 / NeoForge 21.1.250.
This is an implementation increment toward playable two-worker Survival, NOT the
completed 0.0.7a release and NOT an operator updater. HF16 remains the installable
baseline. Existing runtime version markers and remote Survival gates are unchanged.

## Implemented

- PlayerInventoryJournal: a bounded append-only log stores a full inventory image
  and its applied operation ID together in each forced COMMIT. Explicit bootstrap,
  monotonic inventory revision/operation counter/anchor epoch, persistent reservation,
  identical-retry deduplication, conflicting-retry rejection, and strict replay order.
  A duplicate old operation cannot reinstall an old image over later inventory changes.
- InventoryImageCodec: canonical bounded NBT for all 41 ordinary player inventory
  slots and the selected slot. Preserves persistent stack components, names, lore,
  enchantments, custom data, armor and offhand. Decodes the entire image before any
  slot is installed. Unknown or nonpersistent components fail closed, not silently
  dropped. Open menus/nonempty cursors and nonstandard layouts are refused.
- AnchorInventorySession: actual Minecraft selected/offhand slot delta preparation,
  reservation revalidation, durable commit before memory publication, explicit restore,
  epoch fencing and idempotent application. Publication failure fences the session.
  Even duplicate results require the latest durable inventory to be present in memory;
  a reopened session cannot use dedup to bypass required restoration.
- ChunkSaveFence: validates coordinates and the exact expected applied-operation
  attachment in the actual ChunkSerializer output. Missing/altered markers are not
  accepted as checkpoint evidence. A deliberately failing registered codec exercises
  NeoForge's attachment-omission path.

These classes are compiled but no live endpoint, login callback, inventory mixin or
ordinary save pipeline invokes them. Current callers are disposable GameTests only.

## Evidence categories

Local Java 21/Linux: full portable validation passed, including 1,887 new inventory
journal checks and four forcibly terminated JVMs. Existing Manager, UI, HTTP and
reference recovery suites also passed. CI must verify the exact pushed candidate.

The source adds 13 NeoForge inventory GameTests and two additional chunk-save tests,
retaining the original 20 (35 total). Tests cover actual break/placement paths,
last-use tool consumption, full inventory restore, later-change preservation,
held-slot/component conflicts, offhand/empty stacks, stale anchors, malformed final
slots, unreserved grants, transient components, reopened-session publication and
serializer omission. Results are evidence only after actual execution; consult CI.

All game actors have synthetic identities and are never connected clients. A
reopened journal/new epoch in the same GameTest is not a real worker migration.
Journal-process termination is not Minecraft worker termination or sudden power loss.
The publication-only regression uses a synthetic receipt explicitly; break/placement
fixtures separately use the actual forced AuthorityResultJournal with result NBT.
No real user world, inventory, profile, credentials or active configuration is used.

## Scope and restrictions

One operation is pending per player. Ordinary mutation interception is NOT wired;
`inventoryMutationAllowed` is an adapter contract, not proof all vanilla/mod mutation
paths are blocked. Trusted transport/coordinator integration must supply epochs and
verify receipt identity and payload. A WorldReceipt is not an authentication token.
The local file lock is not a distributed lease. Disk force does not certify hardware
power-loss behavior or parent-directory durability. No automatic bootstrap on login,
no automatic overwrite of newer player .dat files, and no timeout-based abort.

Bounds: 1 MiB inventory images, 256 MiB journal, 4,096 committed operations until a
coherent checkpoint implementation exists. Hitting a bound stops new work, never
silently compacts history. Per-operation fsync/full-image encoding are experiment
costs; latency/throughput remain unmeasured and this is not production group commit.

The chunk fence checks marker completeness before persistence; it is not installed
in ordinary saves and does not coordinate independent chunk/entity/player storage.
There is no crash-safe entity-output reconciliation, world replay or checkpoint
promotion claim. Non-inventory effects (exhaustion, stats, advancements, mod effects),
menus, death and pickup transactions are outside this adapter.

## Architecture evidence (frozen design unchanged)

Previous assumption: player dedup can be recorded separately from its inventory.
Evidence: the new storage boundary keeps the inventory and marker in one record;
byte-truncation and process-termination checks recover both together or neither.
Decision: carry this boundary into the live adapter only after .dat reconciliation,
all-mutator interception and authority-result verification are established.
Reason: an ordinary stage-only receipt is not proof the player's inventory persisted.

Previous assumption: successful ChunkSerializer return proves operation data survived.
Evidence sought in the real NeoForge test: an injected codec failure returns an
image without its marker; the additional fence must reject that image.
Decision: require explicit marker validation before admitting a coherent checkpoint.
Reason: serializer success alone cannot authorize operation replay/dedup decisions.

## Remaining 0.0.7a gates

1. Connected-client single-worker baseline; final production actor/presence and
   protection-mod behavior; complete mining progression and player-side effects.
2. All inventory mutators intercepted, real player-save reconciliation, trusted
   request/receipt transport and anchored operation recovery across worker reconnects.
3. Coherent world/entity checkpoints and saved-then-consumed output reconciliation;
   actual Minecraft process-kill matrix; scheduled-tick and mod-timer skew tests.
4. Continuous ordered block-result frames and local revision/ack barriers.
5. Item pickup protocol, cow/item ownership generations and two-worker V0 seam proof;
   measured handoff latency/cell-size/performance budgets and joined-client acceptance.

## Build artifacts

The separately named 0.0.7a-dev.1 EXPERIMENTS ZIP contains a runnable inventory-journal
test JAR, exact tracked source, checksums and actual NeoForge test logs. It contains
no operator updater and no replacement Node JAR. Run the portable test JAR with Java
21 or later in a disposable development environment. Full game tests require the
source toolchain and dependencies via tools/validate_survival_spike.py.
