# Foundations Cluster Runtime Roadmap

- **0.0.1a** — process orchestration / worker lifecycle.
- **0.0.2a** — real Cluster Node telemetry, placement and all-in-one administration.
- **0.0.3a** — Population Layer Planner, worker tooling, console and single-entry Gateway.
- **0.0.4a** — Region Authority control plane + lightweight Cluster Player State.
- **0.0.5a** — persistent client session + invisible simulation transition architecture.

## 0.0.5a.R7 current milestone

R6 adds the first **Simulation Data Plane** underneath the persistent Session Anchor model:

```text
Minecraft client
   -> Velocity
   -> Session Anchor worker
          |
          | BLOCK_REQUEST / future simulation requests
          v
      Manager relay
          |
          v
   Region Authority worker
          |
          | authoritative delta
          v
   Session Anchor replica
```

The initial transport is manager-mediated and bounded. Correct authority ownership, epochs, retries, ACKs and failure behavior are more important at this stage than raw throughput.

The first live interaction is Creative-only remote block break/place. This deliberately avoids Survival item/drop/tool semantics until inventory and loot are authoritative.

## Remaining 0.0.5a work

1. **R7 — remote chunk snapshot/read replication** so an anchor can initially materialize a remote authority region instead of starting from its independent local terrain.
2. Incremental chunk-section/block update stream and replica invalidation.
3. Block entities + container authority, including NBT/versioned state.
4. Survival-safe block interactions: inventory consumption, durability, drops and loot authority.
5. TFC soil/crop/calendar-sensitive persistent state hooks.
6. Entity authority: mobs, Cobblemon, projectiles, vehicles, passengers and dropped items.
7. Automatic transition-belt routing driven by live player location.
8. Replace/augment manager relay with authenticated direct worker links where traffic volume requires it.
9. Expand cluster player state to inventory, XP, effects, advancements and mod attachment/capability payloads.
10. Session-anchor failover/redundancy.

## Safety rule

Never point two ordinary NeoForge JVMs at the same writable `world/` directory. R6 routes individual fenced operations, but it does not yet provide shared region-file storage or a complete persistence journal.
