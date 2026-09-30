# R19-HF3 — NeoForge configuration ping response

Date: 2026-09-28 UTC / September 27 Pacific. Baseline: R19-HF2.
Components remain Manager R19, Node R9, Gateway R19, Conduit 1.8.0.
Manager startup identifies this package as `0.0.6a.R19-HF3`.

## Evidence and diagnosis

The user's second log archive records a fresh proxy (PID 17528) with experimental seamless enabled. Handoff-00000001 took source snapshots at 18:02:56 and 18:02:58 Pacific; destination preparation completed at 18:02:57. At 18:02:59 the backend connected, `neoforge:register` was verified with a captured reply, and `neoforge:network` was verified. After `minecraft:register` no further configuration tasks were logged. The backend read timed out after 6044 ms; switch rejection followed at 6111 ms. Manager aborted without committing destination ownership.

The exact NeoForge 21.1.250 userdev patch for `ServerConfigurationPacketListenerImpl` sends `ClientboundPingPacket(0)` in `startConfiguration`. Its `handlePong` calls `runConfiguration` when the ID is zero. HF2's `SeamlessConfigSessionHandler` had a keepalive handler but no `PingIdentifyPacket` handler. Conduit therefore silently absorbed this separate CONFIG ping and NeoForge never advanced to its remaining configuration tasks.

Official version-pinned implementation examined:
- https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-userdev.jar (`patches/net/minecraft/server/network/ServerConfigurationPacketListenerImpl.java.patch`)
- https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.250/neoforge-21.1.250-sources.jar (`network/registration/NetworkRegistry.java`)

This identifies and reproduces a concrete missing response consistent with the supplied stall. Completion of the entire live client handshake is still unverified.

## Changes

- Answer each current backend CONFIG ping with its matching pong through Conduit's SERVERBOUND CONFIG encoder. Do not replay an old ping ID or send CONFIG packets to the PLAY client.
- Suppress ping responses after configuration rejection. Existing transcript validation and finish checks remain in effect; a pong alone cannot commit a transfer.
- Trace `CONFIG_PING_PONG Ping{proxyId=0}` and otherwise unhandled decoded configuration packet types to make the next stage visible.
- Test Lab watches the exact requested handoff ID. ABORTED/FAILED now surfaces the Manager reason on the next poll instead of waiting 45 seconds. A real wait timeout includes handoff ID and last reported state. Destination anchor, simulation and player-state checks remain required.
- Rebuild the Manager with the HF3 package marker. Retain the actual Node R9 deployment fix and HF1 registry-packet integrity fix. No Node or Gateway plugin version bump is necessary for this proxy-core overlay change.

## Validation performed for HF3

- Reproduced old HF2 failure using actual Conduit packet dispatch: `CONFIG ping 0 was ignored; NeoForge cannot advance`.
- 32 new ping regression checks pass using actual Conduit 1.8.0 packets, handler dispatch, Netty EmbeddedChannel, and the SERVERBOUND CONFIG wire encoder. Verify packet ID 5 for Minecraft 1.21.1, matching IDs 0/42/-1/int boundaries, one response per request, no premature completion and no response after rejection.
- All 20 existing handshake validation checks and 29 HF1 packet-integrity checks pass. Repeated all 81 checks loading the overlay from a patched Conduit JAR first on the classpath.
- 9 new WebUI handoff cases pass: immediate abort/failure, missing reason, unrelated historical abort, physical ownership still required, committed state without migration rejected, stale profile waits, abort precedes ownership success, and request-ID integration.
- Existing 24 acceptance and 16 button fixtures pass; JavaScript syntax passes.
- Rebuilt Java 21-compatible Manager; 89 existing Manager/upgrade assertions pass.
- Verify every source/updater payload hash and ZIP integrity. Node/Gateway plugin JARs, handshake capture source/classes, and the transactional updater script remain byte-identical to HF2.

No joined Minecraft client or Windows runtime was available for HF3. PowerShell updater tests were not rerun in this environment; their historical evidence remains bundled and the transaction script is unchanged. These results establish the response and regression behavior, not player-visible seamlessness.

## Installation and one live check

1. Disconnect the test player, then stop the Manager, proxy and workers.
2. Extract `FoundationsCluster-0.0.6a.R19-HF3-UPDATER.zip`; run `APPLY_R19_UPDATE.bat` and select the existing Cluster folder. Keep the generated backup.
3. Restart normally and hard-refresh the WebUI. Confirm the startup package marker ends HF3; workers remain Node R9.
4. For this live test, keep experimental seamless enabled and verify the proxy launch line says `seamless-server-switches = true`. The updater preserves your saved policy.
5. Reconnect to capture a fresh initial handshake. With two ready workers and healthy saves, use Test Physical Migration first. In velocity.log look for CONFIG_PING_PONG, subsequent configuration tasks and CONFIG_FINISH_ACK_AND_PLAY; then confirm SWITCH_ACCEPTED and COMMIT_ACK. Observe whether the client remains in-world.
6. If that succeeds, run the round-trip and persistence suite while stationary away from dropped items. If it fails, retain the new error and following configuration trace; do not treat transport acknowledgement as full acceptance.

## Other confirmed issues remain open

The preceding normal-transfer archive confirmed 17 diamonds on both workers and successful authority round trips. Its later inventory differences included added grass/leaves; item pickup or replication effects were not isolated, and no loss/duplication verdict is justified. An empty-inventory round trip matched.

That earlier run also contains wrong-region durable writes: a block in r.-1.-1 was published as r.0.-1. The player block-event path still uses the player's route instead of resolving the target block's region. Windows advancement saves also hit sharing violations. Neither issue is changed by this focused proxy hotfix. Earlier zero-failure preflight cannot certify a session after later write failures. Prioritize target-coordinate authority routing, immutable worker-owned profile snapshots, then precise inventory differences and controlled acceptance. Full entity/replica simulation consistency and all existing roadmap limits remain open.
