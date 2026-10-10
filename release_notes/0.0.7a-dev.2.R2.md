# 0.0.7a-dev.2.R2 — source-bound paired entity presentation

Minecraft 1.21.1 / NeoForge 21.1.250. Package dev.2.R2, Node R16,
Manager API/gateway R19. Installable **development** revision, not completed 7a.

## Implemented runtime increment

Opt-in connected-operator `/foundations_entities` commands pair exact vanilla cow
and item UUIDs with a fixed source worker. Other compatible workers send vanilla
entity packets to consenting nearby players without adding entities to their
server worlds. The source's authoritative position, rotation, head angle, velocity
and full synchronized presentation data are replicated. Views reuse allocated
vanilla-counter IDs for ordinary updates, including resets to default metadata.
The native dirty tracker is inspected without clearing it.

The Manager relays bounded binary frames under the existing trusted-agent token
model, current process instance, expected Node/Minecraft version and matching
world identity/seed. Pairing requires an anchored operator and ACTIVE source-owned
region. Replayed frames cannot refresh their expiry. Response age, view radius,
dimension, metadata size and count limits are checked. Receiver metadata batches
are decoded before publication. Invalid/unavailable data fails to a hidden view,
not creation or alteration of any real entity.

Pair selections persist atomically with a checksum in runtime/paired-entity-views.bin.
They are metadata, **not entity snapshots or ownership generations**. Corruption
preserves the original bytes. Presentation frames and consent are transient. Manager
restart requires renewed consent and fresh source data. Existing native UUID
collisions are reported, never automatically deleted, merged or moved. Stale
source views, gone items and dead/unloaded entities are removed from presentation.

No destination server entity means no duplicate destination AI, physics, damage,
loot generation or pickup. Requests against display-only IDs find no gameplay
entity. Up to eight nonpersistent ticking tickets per source retain already loaded
paired entities while current nearby viewer demand exists; loss of demand/control
releases them. No missing entity is loaded or recreated from a pairing record.

Caps: 32 pairs, 8/source, 64 blocks, 4 served viewers/worker, 15-minute consent,
200 ms target update period, 1.5-second frame freshness, 1-second maximum accepted
round trip, 8 KiB metadata per entity, one asynchronous exchange per worker.
A fifth viewer cannot interrupt source publication; excess viewers are not served.
These are configured bounds, not a client-latency or throughput benchmark.

## Validation and packaging gates

903 new portable checks cover wire framing, malformed/truncated data, identity,
world boundaries, source sequences/process changes, opt-in, capacity, registry
restart/corruption and excess-viewer isolation. 34 new HTTP checks use an actual
Manager with synthetic agent reports to exercise authorization, region gating,
world identity, exact UUID/position relay and stale-source cleanup. They do not
connect real Minecraft worker processes.

14 additional actual NeoForge GameTests extend the retained 45 to **59**. They
exercise native cow/item capture, source dirty tracking, real packet codecs,
metadata reset, movement without per-update respawn, TTL/retry expiry, native UUID
collisions, malformed batches, ID allocation and command registration. They use
synthetic game objects and packet sinks, not a joined client or a two-worker game.
Exact-candidate Windows/Linux CI execution is required before publication. Existing
journal/crash tests are retained; their counts do not certify live entity recovery.

Windows CI builds the actual Manager/Node/gateway, validates updater apply/reinstall
and rollback, and preserves operator state. Test-tools packaging reruns the portable
entity tests, carries exact tracked source and actual NeoForge logs. An installer
is published only after both platforms pass. Consult the workflow tied to the
release commit; test source alone is not evidence of passing execution.

The updater accepts HF11-HF16, original dev.2 or the exact same dev.2.R2. It contains
compiled Node R16 and matching Manager selection, plus the existing complete HF16
UI and gateway payload. Worlds, player saves, inventories, journals, security,
configuration, reports and pairing metadata are not updater payloads. Prior HF16,
dev.1 and dev.2 release assets remain unchanged. Main stays HF16 while PR3 is draft.

## Deliberate boundary

**Remote Survival block edits remain disabled. Remote entity gameplay/ownership
transfer and pickup transactions remain unimplemented.** Source A must stay online
and own the relevant region. If its authority moves, the entity unloads or source
control is lost, the preview disappears; this build does not promote it on B.
No crash-safe generation CAS/tombstone protocol or full serialized entity-state
handoff is claimed. No generic modded entity, passenger, leash or vehicle support.

This source-bound packet view is a bounded pre-V0 presentation experiment, not an
implementation of R3's destination in-place authority/shadow role flip. The frozen
R3/P2/P3 design remains unchanged, and this experiment cannot satisfy V0-E1/E2,
entity-crash recovery or pickup gates by itself. Full entity state includes more
than synchronized metadata; behavior/AI continuity is not inferred from packets.

No new mod ID, registry entries, custom client channel, weakened world identity,
forced game-mode change or altered backend-switch/save barrier is introduced.
The existing transition hitch has not been optimized or measured by this patch.
A brief pause remains subject to the user's acceptance; data correctness is not
waived. Reattachment after backend change can visibly clear/recreate client IDs.

See `docs/ENTITY_VIEWS_REV2_GUIDE.md` for the install, exact commands, source-worker
requirements, display-only restrictions, test results to collect and rollback.
The previous recorder keeps its own unchanged dev.2 schema/version marker.
