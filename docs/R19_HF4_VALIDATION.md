# R19-HF4 — compare frozen registries by identity

Date: September 27, 2026 Pacific / September 28 UTC. Baseline: HF3.
Manager R19, Node R9, Gateway R19, Conduit 1.8.0; startup package marker HF4.

## What the new user log establishes

HF3 starts with experimental seamless enabled. Source snapshots and destination preload succeed. At 01:26:51.777949 UTC the switch is armed; at 01:26:52.063527600 the bridge rejects `Destination configuration differs: neoforge:frozen_registry_sync_start (entry 0)`. This is progress beyond the prior six-second missing-pong stall: NeoForge reaches registry synchronization and the bridge's validation now rejects promptly. It is not an inventory-comparison failure.

The Manager log does not contain both registry payloads. We therefore cannot establish whether this specific pair differed only in ordering or also in registry membership. The existing comparator nevertheless has a reproducible defect: a manifest containing the same names in a different order triggers the exact reported error.

## Version-pinned source evidence

The official NeoForge 21.1.250 sources show:
- `FrozenRegistrySyncStartPayload` encodes a list of registry names.
- `RegistryManager.getRegistryNamesForSyncToClient` iterates registry entries without canonical ordering.
- `ClientPayloadHandler` consumes these names into a set and stores subsequent snapshots in a map by registry name.
- `RegistrySnapshot` serializes sorted numeric ID-to-name and alias maps.

Examined from https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-sources.jar, under `net/neoforged/neoforge/network/payload`, `network/handlers`, and `registries`.

HF3 compared the start list byte-for-byte and every `frozen_registry` packet by arrival index. Both impose ordering that the client protocol does not require.

## HF4 changes and preserved guards

- Canonicalize only the frozen-registry start manifest's name ordering. Require the same complete set, valid names and no duplicates. Bound counts and reject trailing/truncated encodings.
- Match each frozen-registry snapshot by registry name, regardless of packet arrival order. Require its entire payload bytes to remain identical. Numeric IDs, entry names, aliases and all other bytes are NOT ignored or rewritten.
- Reject snapshots before their manifest, unexpected or duplicate snapshots, and duplicate manifests. Verify every captured registry snapshot before acknowledging frozen-registry completion; retain final transcript completion checks.
- For real manifest differences, report missing/added names (display capped at eight per side; comparison uses the full set). For differing snapshot contents, include the registry name, lengths and abbreviated SHA-256 fingerprints. Do not log raw client payloads.
- Rebuild all handshake-capture overlay classes and the Manager's HF4 package marker. Retain the HF3 pong handler, immediate WebUI handoff errors, HF2 actual Node R9 deployment fix, and HF1 non-consuming registry observation.

This is a strict equality correction, not a bypass of registry compatibility. A genuine ID mapping, alias or membership difference still blocks seamless transfer.

## Validation performed

- Against unchanged HF3, the reordered manifest fixture reproduces the exact error `Destination configuration differs: neoforge:frozen_registry_sync_start (entry 0)`.
- 48 new registry checks pass: reordered manifest and snapshot sequences, unchanged sequence, added/missing registries, changed numeric IDs, changed names/aliases, duplicate and omitted snapshots, premature completion, bad ordering, malformed/duplicate/trailing manifests, bounded counts, and unchanged source bytes.
- All 81 retained handshake checks pass (20 capture/guard, 29 packet integrity, 32 ping dispatch/wire). All 129 also pass with the compiled overlay loaded from a real patched Conduit 1.8.0 JAR first on the classpath.
- All 49 WebUI cases pass (9 handoff, 24 acceptance, 16 button), plus JavaScript syntax.
- Rebuilt Java 21-compatible Manager; all 89 Manager/upgrade assertions pass.
- Source/updater ZIP integrity and every payload SHA-256 verified. Node and Gateway plugin JARs, WebUI, HF3 seamless handler, and transactional updater script remain byte-identical to HF3.

No real Windows runtime, joined Minecraft client, or full seamless transfer was executed here. The PowerShell transaction is unchanged and was not rerun; prior evidence remains bundled. Tests establish the corrected comparison behavior, not an end-to-end client success.

## Install and live acceptance

Disconnect the player and stop Manager, gateway and workers. Extract the HF4 updater, run `APPLY_R19_UPDATE.bat`, and select the existing Cluster folder. Keep the backup path. Restart, confirm `package 0.0.6a.R19-HF4`, and hard-refresh the WebUI. Node remains R9. Ensure experimental seamless is enabled at launch, reconnect for a fresh captured login, and run Test Physical Migration with two ready workers and healthy saves.

If ordering was the only difference, the bridge should proceed past this registry stage. If registry membership or snapshot contents differ, the new error will identify that difference. Do not delete world data, renumber registries or disable compatibility validation to force a transfer. A successful backend commit still needs the player's observation that the client remained in-world.

## Retained open work

Wrong-region block-event writes across region boundaries and Windows advancement file sharing remain unresolved. Inventory differences from the earlier normal-transfer tests require controlled pickup/replication investigation. Full entity persistence, non-authoritative replica ticking, mod-specific state, and the remaining roadmap are unchanged. HF4 only addresses frozen-registry comparison and its diagnostics.
