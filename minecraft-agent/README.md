# Foundations Cluster Node — 0.0.6a.R9

Minecraft 1.21.1 / NeoForge 21.1.250 worker agent. R9 adds authoritative chunk mutation capture, durable write status, sealed region save checkpoints and process-instance acknowledgements. Real NeoForge tests cover immediate fresh chest transfer and acknowledged creeper terrain recovery after source hard-kill. Full mod/entity simulation and independent non-authoritative replica changes remain partial. See ../docs/R19_VALIDATION.md.

Build with the existing Gradle tooling or, with previously prepared mapped artifacts, tools/build_node_mapped.py. Do not point two worker JVMs at the same writable world directory.

Historical capabilities and boundaries follow.

# Foundations Cluster Node — 0.0.6a

NeoForge 1.21.1 / 21.1.250 worker agent for Foundations Cluster Runtime.

## R7.R5 responsibilities

- Worker registration, telemetry, population-layer and Region Authority awareness.
- R7 live `CHUNK_REQUEST` / `CHUNK_SNAPSHOT` / `BLOCK_DELTA` replication.
- R7.R4 restart-durable final block-state commits and replay.
- R7.R5 generic complete BlockEntity persistence and replica hydration.
- Full compressed NBT captured with `BlockEntity.saveWithFullMetadata(...)`.
- Full payload application with `loadWithComponents(...)` / `loadStatic(...)`.
- `BLOCK_ENTITY_REQUEST` and `BLOCK_ENTITY_DELTA` write-through for non-owner replicas.
- SHA-256 fingerprints to avoid resending unchanged BlockEntities.
- Dirty loaded-chunk scanning with a rotating per-scan budget.
- Tombstones for removed/replaced BlockEntities.
- Full BlockEntity payloads included in authoritative chunk snapshots.
- Durable BlockEntity replay on worker startup/Region Authority acquisition.
- Periodic and clean-shutdown vanilla world checkpoints.

## Generic mod support

There is no list of supported container blocks. Any vanilla/modded BlockEntity that correctly serializes through the standard Minecraft/NeoForge save path is treated as an opaque complete payload. NeoForge attachments/components serialized by that path therefore travel with the BlockEntity as well.

Mods that mutate persistent BlockEntity state without marking it dirty, or store important data outside normal BlockEntity/attachment persistence, cannot be made universally durable without a mod-specific integration.

## Layers and physical worlds

Layers do not create separate BlockEntity state. They are population/runtime views over the same authoritative region.

Workers still use independent physical writable world directories. Never point multiple worker JVMs at one writable world folder.
