# R7.R5 Integration / Acceptance Checklist

## Install
- [ ] Entire cluster is stopped before update.
- [ ] Extract update over the current Cluster directory.
- [ ] Run `APPLY_R7_R5_UPDATE.bat`.
- [ ] Manager reports `0.0.5a.R7.R5`.
- [ ] Every active worker reports Node `0.0.5a.R7.R5`.
- [ ] Existing worker world directories are unchanged.
- [ ] Existing `runtime/persistent-blocks.*` files are preserved.

## Vanilla BlockEntity test
- [ ] Place a chest and fill it with several distinct item stacks.
- [ ] Move through Worker 1 / Worker 2 / Worker 3 or different layers.
- [ ] Chest block remains and contents remain identical.
- [ ] Modify chest contents while anchored on another worker.
- [ ] Return to the original area and verify the newest contents.
- [ ] Repeat with barrel/shulker/furnace if convenient.

## Generic modded BlockEntity test
- [ ] Place at least one modded container or machine.
- [ ] Give it inventory/state that is easy to identify.
- [ ] If applicable, also set progress/configuration/fluid/energy/upgrades.
- [ ] Cross workers/layers and verify the state is unchanged.
- [ ] Modify the state from another worker and verify the latest state everywhere.

## Restart durability
- [ ] Shut the ENTIRE cluster down cleanly.
- [ ] Confirm `runtime/persistent-block-entities.wal` and/or `.tsv` exists after BlockEntity changes.
- [ ] Restart normally.
- [ ] Verify vanilla container contents survived.
- [ ] Verify modded BlockEntity state survived.
- [ ] Modify again, restart again, verify the newest state wins.

## Stale-state/tombstone test
- [ ] Fill a container with recognizable items.
- [ ] Break/remove the container.
- [ ] Place a fresh container at the same coordinate.
- [ ] Restart the cluster.
- [ ] Confirm the old inventory/state does NOT resurrect.

## Performance/safety
- [ ] Normal chunk movement does not cause continuous persistence spam in logs.
- [ ] Dashboard BlockEntity persistence counters advance when state changes.
- [ ] No worker uses the same writable `level-name`/world directory as another worker.
- [ ] Oversized BlockEntity warning, if any, identifies a payload over the configured guard rather than crashing a worker.
