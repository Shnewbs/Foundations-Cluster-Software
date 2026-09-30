# Foundations Velocity Gateway — 0.0.6a.R19

Minecraft 1.21.1 / NeoForge 21.1.250 through Conduit 1.8.0. Explicit physical transfers move the actual backend. Normal reconfiguration is the default; experimental seamless mode requires a full restart and fresh login to capture the client's configuration transcript.

R19 installs a bounded per-client configuration observer before initial backend selection. The Conduit overlay validates destination configuration before reusing that client's replies. Unknown or changed tasks fail with a reason; source connection retention and a failed-destination cooldown support recovery. Install all overlay classes with this plugin.

Compilation and transcript fixtures pass; real connected-client seamless acceptance remains open. See ../docs/R19_VALIDATION.md and ../tools/conduit-r15-patch/README.txt. The historical session-anchor design below does not replace explicit physical migration.

# Foundations Velocity Gateway 0.0.5a.R7.R2

Velocity remains the public `:25565` ingress and chooses the player's initial backend worker.

R5 changes the normal movement model: after login, that backend becomes the player's **session anchor**. Foundations does not ask Velocity to switch the player to another backend during ordinary region/layer movement. Instead, the Cluster Manager can prepare and commit a different **simulation target** behind the anchored session.

This avoids Minecraft 1.21's visible `Reconfiguring...` transition caused by a normal Velocity backend change.

The old backend-switch code remains available only when both of these are explicitly configured:

```properties
handoff.mode=velocity-switch
handoff.legacyVelocityBackendSwitch=true
```

That compatibility mode is expected to show the normal Minecraft reconfiguration/loading transition and is not the target MMO experience.

## Current boundary

R5 is the control-plane/session-anchor foundation. It does **not** yet stream remote chunks/entities/machine ticks from the simulation target back through the anchor worker. Until the shared-world/simulation data plane exists, changing the simulation target is intentionally a topology/preload test rather than a complete gameplay migration.
