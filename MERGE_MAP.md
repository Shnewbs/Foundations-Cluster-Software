# R7.R4 Merge Map

R7.R3 validation says the manager/UI transfer changes are working and existing R7 `relay-v2-chunk-read` code was untouched. R7.R4 should preserve that UI and replace only the transfer completion path.

## Manager-side merge

Wire the current Region Authority `Transfer...` action into:

```java
RegionTransferCoordinator.transfer(region, sourceEndpoint, destinationEndpoint)
```

The manager's existing authority store should implement `AuthorityStore` using its current owner/epoch/revision record and atomic commit lock.

For the current same-machine cluster, each worker can be represented by `LocalFilesystemWorkerEndpoint` using that worker's real world root and runtime staging directory.

## Node-side merge

Adapt the current Cluster Node command/control channel to `NodeRegionHooks`.

Required commands/operations:

```text
REGION_FENCE_FLUSH
REGION_UNLOAD_INVALIDATE
REGION_ACTIVATE
REGION_DEMOTE
REGION_RELEASE_FENCE   (pre-CAS rollback only)
```

The Node must perform Minecraft save/unload operations on the server thread. The manager must not edit open `.mca` files behind Minecraft's back.

## Gateway merge

The existing seamless handoff must wait for the region transfer result. Velocity switch happens only after `COMMITTED`/`WORLD_READY(epoch,revision)`.

## UI

Keep the working R7.R3 modal/dropdown. Add transfer progress and a `RECOVERY_REQUIRED` state later; do not regress the dropdown behavior.
