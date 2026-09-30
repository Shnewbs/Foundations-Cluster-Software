# Foundations Cluster R19-HF10 / Node R12 Validation

## Purpose
HF10 preserves the accepted R19-HF9 physical/seamless handoff path and fixes replica freshness for autonomous authoritative world mutations.

## Changes
- Manager data plane fans out coalesced, epoch-fenced `CHUNK_INVALIDATE` frames after durable `PERSIST_BLOCK` and `PERSIST_BLOCK_ENTITY` commits.
- Node R12 invalidates replica cache metadata when the simulation-route token changes, forcing authoritative refresh for a new anchor/epoch.
- Existing `LevelChunk.setBlockState` mutation capture remains authoritative; no whole-world scan loop was added.
- Gateway R19 / Conduit bridge is unchanged.

## Sandbox validation
- Manager Java 21 compile: PASS.
- Test Lab persistence: PASS (22 cases).
- Test Lab handoff: PASS (9 cases).
- Test Lab restart: PASS (5 checks).
- Test Lab support: PASS (5 checks).
- Test Lab acceptance: PASS (24 cases).
- Node R12 source compile could not execute in the sandbox because the NeoForge/Gradle dependency cache is absent and outbound Gradle distribution download is unavailable. The updater therefore builds Node R12 locally before replacing any installed Node binary.

## Live acceptance
1. Stop the entire cluster and apply the HF10 updater.
2. Confirm startup reports package `0.0.6a.R19-HF10` and every worker reports Node `0.0.6a.R12`.
3. On authority worker A, plant a sapling and allow normal random-tick growth (do not use `/place feature`).
4. After growth, physically transfer A -> B without preconfiguration. The tree must already be present on B and must not pop out/in during route convergence.
5. Return B -> A and verify identical tree state.
6. Repeat with crop growth and leaf decay.
7. Exercise flowing fluid and redstone state near the handoff area.
8. Run Chest, Generated Tree, Graceful Stop + Restore, then Full Acceptance to prove no HF9 regression.
9. Capture a Support ZIP on any failure.

HF10 improves block/chunk replica freshness. Complete scheduled-tick queues, entity migration, and arbitrary mod-owned non-block simulation state remain separate roadmap work.
