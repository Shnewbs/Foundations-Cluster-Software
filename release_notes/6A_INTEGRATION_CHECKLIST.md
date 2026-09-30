# Foundations Cluster Runtime 0.0.6a — Integration / Acceptance Checklist

## Install
- [ ] Entire cluster is stopped before the update.
- [ ] Extract `FoundationsClusterRuntime-0.0.6a-UPDATE.zip` over the Cluster directory.
- [ ] Run `APPLY_6A_UPDATE.bat`.
- [ ] Manager reports `0.0.6a`.
- [ ] Every running worker reports Node `0.0.6a`.
- [ ] Existing world/block/BlockEntity/player persistence data remains intact.

## Locked seed / world identity
- [ ] Dashboard shows a canonical cluster seed.
- [ ] Newly-created workers receive the same locked seed.
- [ ] Healthy workers show **World Identity MATCH**.
- [ ] A deliberately incompatible test worker is marked mismatch / unroutable when `world.identity.enforce=true`.
- [ ] Do not enable enforcement on an upgraded cluster until expected healthy workers show MATCH.

## RAM guardrail
- [ ] Dashboard shows physical/budget RAM, system reserve, worker reservation, and effective maximum.
- [ ] Manual worker creation is refused when the effective memory maximum is reached.
- [ ] Automatic scale-up is also refused at the same limit.
- [ ] Lowering `scaling.maxWorkers` below the memory-derived maximum makes configured max win.

## Assisted scale-up
- [ ] Set `scaling.mode=assisted`.
- [ ] Create sustained pressure (players/MSPT/CPU/heap).
- [ ] Dashboard produces an actionable scale-up recommendation.
- [ ] Approving it starts exactly one new worker if capacity exists.
- [ ] New worker verifies seed/world identity before becoming routable.

## Consolidation / scale-down
Use three healthy workers with light load (for example 5 players on each).
- [ ] Sustained low load computes a lower desired worker count.
- [ ] Excess workers are selected for consolidation; tied loads prefer keeping lower-numbered workers.
- [ ] All selected drain workers enter DRAINING before player evacuation starts.
- [ ] No player from one drain candidate is migrated onto another drain candidate.
- [ ] Players/authority move to the kept worker(s).
- [ ] Drained workers stop only after players, authority regions, and pending handoff work clear.
- [ ] Target workers remain below the configured utilization/capacity ceiling.

## Batch drain
- [ ] `scaling.maxConcurrentDrains` limits how many workers consolidate at once.
- [ ] With two excess workers and maxConcurrentDrains >= 2, both may drain in one batch.
- [ ] Remaining workers have enough target capacity before batch drain begins.

## Hysteresis / anti-thrash
- [ ] Short load spikes do not immediately create workers before scale-up sustain time.
- [ ] Short load dips do not immediately drain workers before scale-down sustain time.
- [ ] Cooldowns prevent rapid create/drain/create oscillation.

## Persistence regression
- [ ] Blocks survive worker movement and full cluster restart.
- [ ] Vanilla and modded BlockEntity/container contents survive worker movement and restart.
- [ ] Player inventory/profile survives graceful drain and full cluster restart.
- [ ] Advancements/stats survive if stored through the supported player profile paths.
