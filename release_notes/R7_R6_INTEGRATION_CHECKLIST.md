# R7.R6 Integration / Acceptance Checklist

## Install

- [ ] Shut down the entire cluster before applying the update.
- [ ] Extract the R7.R6 ZIP over the current Cluster directory.
- [ ] Run `APPLY_R7_R6_UPDATE.bat`.
- [ ] Manager reports `0.0.5a.R7.R6`.
- [ ] Every active worker reports Node `0.0.5a.R7.R6`.
- [ ] Existing R7.R2 Velocity Gateway remains online/healthy.

## Advancement + player profile continuity

Use one test player and at least two workers.

- [ ] Earn a new vanilla advancement while anchored to worker-001.
- [ ] Put recognizable items in normal inventory, armor/offhand and Ender Chest.
- [ ] Click **Stop** on worker-001 while worker-002 is healthy.
- [ ] Player is moved to a healthy worker without a disconnect.
- [ ] Advancement remains completed on the destination.
- [ ] Inventory / armor / Ender Chest remain unchanged.
- [ ] Statistics remain present.
- [ ] `runtime/player-profiles/<uuid>/` contains `playerdata`, `advancements`, and `stats` records after the handoff.
- [ ] Restart the cluster and verify the same player records remain.

## Graceful Stop — single player

- [ ] worker-001 and worker-002 are healthy/routable.
- [ ] Player is anchored to worker-001.
- [ ] Click **Stop** on worker-001.
- [ ] worker-001 enters DRAINING and stops accepting new placements.
- [ ] Dashboard shows a countdown / migrated-player status.
- [ ] Player migrates to worker-002 (or another healthy worker) within the grace window.
- [ ] Authority regions owned by worker-001 are rehomed before shutdown.
- [ ] worker-001 reaches zero players and zero owned authority regions.
- [ ] Minecraft receives normal `stop`; worker reaches OFFLINE.

## Graceful Stop — multiple players

- [ ] Put several players on one source worker.
- [ ] Keep at least two destination workers healthy.
- [ ] Click **Stop**.
- [ ] Multiple handoffs progress in the same heartbeat cycle (not one player every two seconds).
- [ ] Players are distributed across available healthy workers.
- [ ] No player is sent to a DRAINING / STOPPING / unhealthy worker.
- [ ] Source worker stops only after everyone has evacuated.

## Deadline / cancellation

- [ ] With no healthy destination worker available, click **Stop**.
- [ ] After the configured evacuation grace (default 10s), Stop aborts.
- [ ] Source worker remains alive and routing is restored; players are not intentionally disconnected.
- [ ] Start a Stop and click **Cancel Stop** before completion; routing is restored.
- [ ] Start a Stop and click **Force Stop**; JVM is terminated without the graceful evacuation guarantee.

## Quest mods

- [ ] If the quest mod uses vanilla advancements or normal player NBT, verify quest progress after a graceful worker migration and restart.
- [ ] If it uses a per-player file, add that path to `playerProfile.sharedFiles` with `{uuid}` / `{name}` and re-test.
- [ ] If it uses a team/global database or custom external store, treat it as needing a dedicated adapter; do not assume generic file copying is safe.
