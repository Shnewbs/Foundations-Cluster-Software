# R19-HF6 — NeoForge declaration comparison

Date: September 27 Pacific / September 28, 2026 UTC. Baseline: HF5.
Manager R19 / Node R9 / Gateway R19 / Conduit 1.8.0. Startup package marker HF6.

## What the supplied acceptance output establishes

Safe preflight passes: two Manager-controlled Node R9 workers are ready, the gateway launched with experimental seamless enabled, world identity matches, and both workers report no unconfirmed writes. The physical migration round trip aborts handoff-00000001 during destination configuration, at `neoforge:known_registry_data_maps`, entry 0. Source/destination payload lengths are 385/385 bytes, with fingerprints 21bce21a8d78/cafd7bb691f1. The failure is reported after 4,352 ms. The round trip did not complete.

This output identifies the current rejection stage. It does not include the raw paired payloads, complete gateway trace or startup package marker, so it cannot by itself establish an order-only live difference, the precise installed hotfix, or client-visible seamlessness. It does not certify all persistence behavior.

## Version-pinned protocol evidence and reproduction

Inspected the official NeoForge 21.1.250 source JAR:
https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-sources.jar

Relevant classes under `net/neoforged/neoforge`:
- `network/payload/KnownRegistryDataMapsPayload`: registry-key map whose values contain data-map IDs and mandatory booleans.
- `network/configuration/RegistryDataMapNegotiation`: builds declarations from hash maps and each registry's data-map entries, with no canonical order.
- `registries/ClientRegistryManager`: compares mandatory declarations as sets of registry/ID pairs and replies with the client's locally known maps; list ordering is not used for compatibility.
- `network/codec/NeoForgeStreamCodecs.registryKey`: registry identity is serialized as a ResourceLocation string.
- `network/payload/ExtensibleEnumDataPayload` and `network/configuration/CheckExtensibleEnums`: class entries are keyed by class name, but network policy, counts and the ORDERED added enum values matter.
- `network/payload/FeatureFlagDataPayload` and `network/configuration/CheckFeatureFlags`: feature flags are a resource-name set, checked with set equality.

A source-grounded wire fixture encodes identical data-map declarations in different registry/ID iteration orders. Running it against the unchanged HF5 bridge reproduces the same channel rejection (141/141-byte fixture). This proves an existing comparator defect; it is not a capture of the user's 385-byte payloads. The same source audit identified equivalent ordering assumptions in enum-class and feature-flag declarations, which this release also corrects.

## Changes and retained checks

| Declaration | Order normalized | Still checked exactly |
| --- | --- | --- |
| Known registry data maps | Registry ordering and data-map declaration ordering | Every registry, every map ID, every mandatory boolean; empty registry declarations retained |
| Extensible enums | Class-entry ordering only | Entire serialized entry: class name, network policy, extension presence, counts and ordered extension names |
| NeoForge feature flags | Resource-name set ordering | Complete unique flag membership |

The comparator uses private bytes, never rewrites forwarded packets, and replays only the real client's captured replies. The data-map declaration describes availability/mandatory status; it is not the later synchronized data-map values themselves. This change does not claim new validation of those values.

Missing/added registries or map IDs and changed mandatory flags now appear in rejection messages. Enum mismatches name the class and give entry lengths/fingerprints. Feature-flag differences list missing/added names. Names/lists in diagnostics are bounded.

Malformed names/counts/booleans, duplicate registry/map/class/flag identities, trailing/truncated data and inconsistent enum extension counts reject replay. Payloads retain the 16 MiB capture ceiling; declaration collections are bounded to 8,192 registries/classes/flags and 131,072 total data-map declarations or enum extension members. Counts are checked against remaining bytes before processing.

The existing packet multiplicity, captured-client-acknowledgement and final completeness guards remain in place. Frozen-registry snapshots, tag member sequences, config-file bytes, known packs, and other unsupported/unmodified tasks have not been relaxed. The new helper class is shipped and required by gateway bootstrap; its existing wildcard patch and hash verification covers that class. All HF1–HF5 corrections remain included.

## Validation performed

- Unchanged HF5 fails the equivalent reordered data-map fixture at the reported channel.
- 366 new declaration checks pass: reordered/shuffled maps, mandatory flag changes, missing/added/moved IDs, empty declarations, enum class ordering with exact policy/count/member order, feature-set equality, malformed/duplicate/truncated payloads, count bounds, packet ownership/copy isolation, captured replies and mixed declaration completion. Counts include all tested truncated fixture prefixes.
- All 351 retained handshake checks pass. All 717 checks also pass with the compiled overlay loaded from the patched real Conduit 1.8.0 JAR first on the classpath.
- WebUI: 9 handoff, 24 acceptance and 16 button cases pass, plus JavaScript syntax.
- Manager rebuilt for Java 21; all 89 Manager/upgrade assertions pass.
- Source/updater ZIP integrity and every payload SHA-256 are verified. Node R9, Gateway plugin R19, WebUI, the seamless session handler, tag comparator and transactional updater script remain byte-identical to HF5.

No joined Minecraft client, actual Windows runtime or full seamless transfer was executed here. PowerShell is unavailable; the added bootstrap class requirement was inspected, and the shipped helper was exercised from the patched JAR, but the Windows bootstrap was not run. The transactional updater is unchanged. Results: `docs/validation/r19-hf6-results.json`.

## Installation and live acceptance

Disconnect the player and stop Manager, gateway and all workers. Extract the HF6 updater, run `APPLY_R19_UPDATE.bat`, select the existing Cluster folder and retain the backup path. Restart and confirm `package 0.0.6a.R19-HF6`. Components remain R19/R9/R19. Confirm experimental seamless at proxy launch, reconnect for a fresh captured login, then run Test Physical Migration with two READY workers and healthy saves.

If the declarations differ only in supported collection ordering, this comparison should now pass. Real declaration changes still reject with specific details. Success requires actual destination ownership/presence plus confirmation that the Minecraft client showed no configuration/loading screen, followed by inventory round-trip verification. A green safe preflight alone does not establish that.

## Remaining work

Live seamless acceptance remains open. Earlier wrong-region block-event routing, Windows advancement file sharing, and inventory pickup/replication investigation remain open. Full entity persistence, replica tick fencing, mod-specific state, remote hosts and the rest of the roadmap are unchanged. This hotfix does not claim to complete these features or establish crash durability from a preflight snapshot.
