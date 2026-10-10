# Rev2 — paired cow/item views

Package **0.0.7a-dev.2.R2**, Node **0.0.6a.R16**, Manager API/gateway R19.
Minecraft **1.21.1 / NeoForge 21.1.250** only.

## What this build does

An explicitly paired vanilla cow or dropped item remains a real entity on its
source worker. Opted-in players on another compatible worker receive a packet-only
view using that entity's UUID, authoritative coordinates, facing, velocity and
synchronized presentation data. Movement and data updates reuse the local view ID.
No destination gameplay entity is created, saved or ticked.

**This is display-only presentation, NOT full entity migration.** A remote view
cannot be attacked, milked, bred, mounted or picked up. The real source entity
continues its ordinary gameplay. Source death, pickup, unload, timeout or lost
region readiness removes the view; this release never recreates the real entity.
Cross-worker Survival block breaking/placement remains disabled.

This build does not eliminate independent mobs already present in another worker's
world. Different UUIDs are different entities, even at identical locations. A
native entity with the same UUID blocks the preview and increments the collision
counter; the software never deletes, teleports or merges that native entity.

## Install

1. Make a stopped backup/test copy of your installation. Shut down Manager, gateway
   and every worker. Do not test experimental features first on your only world copy.
2. Extract `FoundationsCluster-0.0.7a-dev.2.R2-UPDATER.zip` separately.
3. Run `APPLY_UPDATE.bat` and select the existing stopped Cluster folder. Accepted
   baselines are HF11 through HF16, the original dev.2, or this same Rev2 version.
4. Keep the printed backup directory. Start normally, refresh the dashboard with
   Ctrl+F5 and confirm **dev.2.R2 / Node R16 on ALL workers**.

The compiled Manager and Node are included; no local Gradle build or additional mod
ID is needed. Existing HF16 shutdown/restart and worker controls remain included.
Worlds, player saves, inventories, profiles, configuration, security, journals and
pair selections are excluded from the updater payload. Later Node synchronization
and gateway runtime patching have separate backups.

## First test: observe the same entity from worker B

Use two READY/ACTIVE workers with matching world identity/seed and the same modpack.
Pin the source worker online. Keep it running and keep the entity's region owned by
it throughout this first test. This is not a source-stop/failover acceptance test.

As a connected operator on **worker A**, stand within 16 blocks of a vanilla cow
in an ACTIVE region owned by A. A named cow is easy to distinguish. Run:

```text
/foundations_entities pair @e[type=minecraft:cow,sort=nearest,limit=1,distance=..16]
/foundations_entities list
/foundations_entities status
/foundations_entities view on
```

Wait for the Manager confirmation, not merely the "request queued" message.
`pair` records the real UUID and source worker. Repeating it on that source reuses
the pairing; attempting to pair the same UUID to another source is refused.

On **worker B**, use another connected operator at the same world coordinates and
dimension, or change your own backend while leaving source A online and its region
ownership unchanged. Enable viewing on B with:

```text
/foundations_entities view on
/foundations_entities status
```

The preview should appear within 64 blocks. Move the real cow on A and watch B:
the preview should follow, retain its UUID, and update facing/baby/name/health
presentation data without being removed and respawned on every update. You cannot
interact with the preview on B. Existing unrelated local cows are not removed.

Viewing consent lasts 15 minutes and can follow your player UUID between compatible
workers during that time. Manager restart clears consent; run `view on` again.
A worker/backend switch can still briefly clear/recreate client-side presentation:
stable client integer IDs across gateways are not implemented here.

## Dropped item

On A, drop an ordinary item, stand near it, and pair the exact item entity:

```text
/foundations_entities pair @e[type=minecraft:item,sort=nearest,limit=1,distance=..16]
```

Watch it on B. The preview shows the real item type/count and supported component
presentation. Pick up the **real** item on A; B's view should disappear after a fresh
update. Walking over the view on B must not grant a duplicate item. This is NOT a
test of cross-worker pickup transactions; those are still unimplemented.

## Cleanup and diagnostics

```text
/foundations_entities view off
/foundations_entities list
/foundations_entities unpair UUID_FROM_LIST
/foundations_entities status
```

`view off` removes only that player's previews. `unpair` removes the selected
pairing, never the real entity. `status` reports source pairs, unavailable sources,
short-lived source tickets, remote views and UUID collisions. The Manager also has
an authenticated read-only `/api/entity-views/status` endpoint.

A registered source is required: pair/view/list/unpair are connected-operator
commands, not console commands. Status is usable from an authorized console.
Pairing a replica, leashed cow, passenger/vehicle or unsupported entity is rejected.
Modded cows/entity subclasses, horses, pets, boats, minecarts and projectiles are
not certified by this first exact-vanilla cow/item implementation.

## Limits and expected pauses

32 pairs per Manager, at most 8 per source; 64-block view radius. At most 4 opted-in
viewers are served per worker. Above that, selection is deterministic by UUID and
excess viewers are not served; they cannot prevent the source publishing updates.
Use no more than four simultaneous viewers per worker for this test.

Updates target 200 ms when there are players/pairs. There is no per-entity fsync.
Stale views expire after at most the remaining 1.5-second freshness window, reduced
by measured request round-trip time. Responses taking more than one second are
not rendered. Missing/stale/offline-source data is hidden rather than invented.
These are software bounds, not a claim of measured client latency or no frame hitch.

At most 8 nonpersistent source ticking tickets keep already loaded paired entities
active while fresh nearby opted-in viewers need them. Tickets are released when
interest, control, ownership or source readiness is lost, and at shutdown. This
does not search/load missing entities from disk or make remote views authoritative.
No entire-world duplicate entity simulation is introduced.

## What to send back

Record A/B worker IDs, the pairing UUID from `list`, status output on both workers,
world coordinates, and whether A still owns the region. Report whether position,
facing and item disappearance agree, and how long the transition hiccup feels.
A short video is useful for visual continuity; server test counts do not prove it.
The existing `/foundations_playtest` capture remains available but does not record
all entity packets or prove entity ownership transfer. No player .dat or credentials
are needed. Review logs/screenshots for private data before sharing.

## Rollback

Stop all processes. Use the **matching Rev2 updater** with its printed backup path:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\apply_update.ps1 -ClusterRoot "C:\path\to\Cluster" -Rollback "C:\path\to\Cluster\update-backups\7a-dev2-R2-..."
```

That restores the program files covered by this updater. It does not roll back
world changes or later Node/template synchronization; preserve their separate
backups too. Verify the old Manager's required Node version on every worker before
rejoining. Never delete a world or pair metadata to solve a version mismatch.
