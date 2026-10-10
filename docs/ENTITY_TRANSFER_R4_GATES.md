# Foundations Cluster 0.0.7a — Entity continuity acceptance gates (R4)

**Scope:** Minecraft 1.21.1 / NeoForge 21.1.250; package `0.0.7a-dev.2.R4`, Node `0.0.6a.R18`. This is **development-only**. Source-controlled in the `feature/0.0.7a-survival-foundation` branch; HF16 main and older release assets remain unchanged.

## What is actually implemented

- Automatic, opt-in, interest-bounded and **display-only** entity previews for **exact vanilla cows, pigs, sheep, chickens and dropped items**. Additional types are explicit adapters with Java GameTests for their actual vanilla spawn/metadata packets. Older enum ordinals remain unchanged for compatibility with existing persisted diagnostic pair selections. The Manager still verifies source region authority, worker instance, world identity and version. Different UUIDs are never guessed to be the same entity.
- A **standalone persistent Manager-side model** in `protocol/EntityOwnershipLedger`: source owner + epoch + generation; stage exact full-state bytes; durable generation CAS to the destination; preserve previous-owner tombstone metadata; idempotent retry; stale owner rejection; precommit abort; closed-on-corruption. Only an atomic local snapshot file and process lock are provided; these are **not** a distributed lease or evidence of destination world persistence.
- A **standalone persistent pickup protocol model** in `protocol/PickupTransferLedger`: PROPOSE → RESERVE → WORLD_COMMITTED → PLAYER_COMMITTED → CLEARED or precommit ABORTED. The world commit is irreversible. Competing transfers for the same item revision are rejected; partial pickup can retry after the previous transfer's player grant clears. Committed IDs persist for duplicate suppression until a future coherent checkpoint. **Neither model is called by ordinary Minecraft gameplay or hooked to live player inventory saves.**

## Mandatory gates for actual cross-worker gameplay

| Gate | Needed to lift release fence | R4 status |
|---|---|---|
| Cross-worker presentation | Joined clients see correct positions/metadata for each certified type; no duplicates, client pop, or invalid UUID binding | **Unproven with joined client** — synthetic GameTests only |
| Actual entity ownership transfer | Destination durably stages real Minecraft entity state, Manager checks trusted stage receipt and CAS, old owner stops simulating, new owner promotes, source tombstone prevents resurrection | **Blocked** — Manager CAS reference model only |
| Persistence and source loss | Real chunk/entity storage and mod attachments reconcile; killed source/destination never creates dual simulation or loses the entity | **Blocked** — no live storage adapters / process-kill worker matrix |
| Resource pickup | Entity decrement/merge saved durably; anchor inventory reservation and idempotent grant saved together with the effect marker; partial/full inventory and reconnect recovery | **Blocked** — reference state machine only |
| Expanded modded entities | Explicit per-mod provider/version certification for spawn, synchronized metadata, persistent state, tracking, AI/capability attachments and client registry | **Blocked** — no third-party mod artifact tested |
| Passenger/leash/vehicle graphs | Transfer topology atomically rather than silently detach/clone entities | **Blocked** — no adapter |
| Seam/hitch performance | Instrument snapshot, entity readiness, final fence, gateway switch, client resume and compare p50/p95/p99 against single-worker control | **Blocked** — visual user test pending |
| Survival block edits | Native player-aware execution plus inventory reservations, permissions, item effects, durable frames and test crash matrix | **Blocked** — intentional runtime safety fence retained |

An apparently working preview does **not** satisfy the entity transfer, pickup or persistence gates. A short backend pause is provisionally acceptable while measuring latency but never authorizes item/entity duplication or stale-generation writes. No unrestricted modded entity replication may be enabled based solely on UUID discovery.

## Repeatable proof

Run `python tools/validate.py`: portable ownership CAS and pickup journal tests are included, with deliberate post-durable-event child-process kills. Execute `python tools/validate_survival_spike.py` for real NeoForge packet/spatial-index GameTests (Java 21, NeoForge 21.1.250 and Gradle 8.14.3). Run the normal Windows/Linux GitHub CI and Windows updater/rollback tests against **the exact R4 source SHA** before publishing the uniquely tagged development updater. All tests use disposable worlds, not user saves.

### Why live ownership remains disabled

`EntityOwnershipLedger` currently only coordinates durable *metadata* and opaque staged bytes. It does not authenticate a destination worker's durable entity-storage receipt, freeze/resume live source AI, serialize/load Minecraft entities and attachments, or make client entity IDs stable through Conduit. `PickupTransferLedger` does not perform a real inventory mutation; only the future player-owned save adapter can make that effect idempotent. Wiring them directly into gameplay today would risk item/entity duplication.
