# 0.0.7a-dev.2.R5 — Handoff latency and full entity snapshot adapter

**Development-only:** Minecraft 1.21.1 / NeoForge 21.1.250, Node R19, Manager API/gateway R19.
**Not** completed playable `0.0.7a` or a new standalone mod. The unchanged main branch remains HF16.

## Live handoff preload change

Previous destination preparation synchronously loaded an `(2r+1)^2` square via
`getChunk(...)` on the Minecraft server thread before acknowledging Manager.
With radius 2, that could schedule/load **25 chunks in the same server tick**,
contributing to an apparent lag spike. This was not an instrumented diagnosis
of every source of the observed hitch.

R5 replaces that call with a **bounded, nonpersistent, asynchronous ticket request**:

- Center-first rings, **at most four tickets issued per server tick**.
- Acknowledgment is sent only when all requested chunks are actually present
  via `getChunkNow`; no blocking `getChunk` or `Future.join` in the path.
- Tickets are isolated by handoff ID even if two preloads overlap a chunk.
- A 10-second post-ready grace keeps chunks prepared through final checkpoint
  and backend switching; failures/timeouts clean up all tickets.
- Timeout is 18 seconds. At most four pending preloads, radius clamped to 0–4
  (25 chunks at the normal radius of 2). Expired jobs abort, never claim ready.
- Errors fail closed. Unknown dimensions and invalid destination coordinates
  are rejected, not silently redirected to the Overworld.

**Limits:** This may reduce one source of server-thread stalls but is not
measured joined-client end-to-end latency. The existing source final
checkpoint and Manager's handoff safety checks are retained, not bypassed.

## Vanilla full-state continuation capture

`EntityContinuationCodec` captures and decodes the **actual Minecraft persistent
entity NBT** for the five certified vanilla entity classes (cow, pig, sheep,
chicken, dropped item). It preserves the entity UUID, dimension, type, world
game time, serialized position, velocity and normal saved fields (including
health/age/item stacks). Strict readback validates embedded identity, size,
format, coordinate finiteness and expected world/type. Byte cap: 256 KiB.
No destination entity is spawned or inserted by this adapter.

This is an input for future destination durable staging, **not** a full
transfer API. It does not wire into Manager's ownership CAS yet, and does not
claim to reconcile loaded/dormant entity storage or mod-specific data.

## Suggested user test (backup/test cluster only)

1. Stop all cluster processes; back up worlds, settings, playerdata and journals.
2. Apply the unique R5 updater to a stopped compatible baseline, save the
   printed backup path, and verify package R5 and Node R19 on every worker.
3. Join as a real operator. Try a previously repeatable physical backend
   switch from worker 1 to worker 2. Record subjective pause length and any
   world/entity pop. Repeat with a controlled test after a server restart.
4. For presentation, issue `/foundations_entities view on` on the destination
   with the source worker still running and authoritative. Verify the five
   preview types at canonical coordinates; no real remote interaction yet.
5. Use `/foundations_playtest start seam r5_handoff` and `stop` for local
   context, and provide worker logs plus timestamps of the actual transition.
6. Report whether chunk loading stalls are lower; a single subjective run
   is **not** a p95/p99 benchmark.

## Remaining release-blocking gates

A truly playable 0.0.7a still requires: real-player remote Survival actions
with inventory reservation and exactly-once results; full entity state staged
**durably on the destination** before Manager generation CAS; source demotion,
new owner promotion and tombstone replay; a coherent save/reload/crash matrix
against actual workers and entity storage; resource pickup inventory commit;
continuous ordered block and entity updates; modded-entity certification and
joined-client single-worker vs two-worker V0 seam measurements. They are not
present in this increment. Do not infer full support from a packet-only view.
