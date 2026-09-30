# R7.R4 Integration / Test Checklist

## Implemented in this package

- [x] Manager durable block WAL with fsync per accepted authoritative mutation.
- [x] Latest-state compaction snapshot.
- [x] Epoch/owner/ACTIVE validation before a block mutation may become durable.
- [x] Worker persistence replay endpoint.
- [x] Replay whenever a worker starts/acquires a new authority owner/epoch/state assignment.
- [x] Replay results checkpointed through Minecraft's normal world save path.
- [x] Periodic persistence checkpoint on workers.
- [x] Clean-shutdown checkpoint and wait for in-flight persistence commits.
- [x] Startup persisted-owner recovery grace.
- [x] Existing R7 live chunk snapshots/block deltas retained.
- [x] Existing R7.R3 dropdown/transfer UI retained.
- [x] Existing managed workers sync the current Cluster Node JAR from the server template on start/restart.

## Required in-game acceptance test

- [ ] Place red blocks on the current owner.
- [ ] Move through worker-002/worker-003 and confirm live view remains identical.
- [ ] Modify at least one test block while anchored on a different worker.
- [ ] Cleanly stop the entire cluster from the dashboard.
- [ ] Restart the manager/workers.
- [ ] Confirm the latest block states still exist.
- [ ] Transfer the authority region to another worker.
- [ ] Restart again and confirm the same latest states remain.
- [ ] Confirm `runtime/persistent-blocks.wal` or the compacted TSV contains the test coordinates.

## Intentionally not claimed complete yet

- [ ] Chest/container inventories.
- [ ] Block-entity NBT generally.
- [ ] Persistent entities/mobs/item entities.
- [ ] POI storage.
- [ ] Arbitrary mod SavedData/global state.
- [ ] Full Anvil `region/entities/poi` triplet migration during ownership transfer.

Those are the next persistence stage after R7.R4's restart-durable block path is proven in the live pack.
