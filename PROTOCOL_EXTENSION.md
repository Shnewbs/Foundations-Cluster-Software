# R7.R4 Authority Transfer Contract

R7.R4 does not require replacing `relay-v2-chunk-read`. It wraps the existing R7 transport in a stricter transactional contract.

```text
PREPARING
  |
  +-- verify source owns (region, epoch)
  +-- verify destination is healthy/READY|ACTIVE
  |
SOURCE_FLUSHED
  |
  +-- fence new persistent writes
  +-- flush chunk save queue / block entities / attachments
  +-- produce {epoch, revision, checksum, counts}
  |
DESTINATION_INVALIDATED
  |
  +-- unload/drop stale region cache/local copy
  |
SNAPSHOT_INSTALLED
  |
  +-- destination pulls existing R7 chunk/read snapshot
  |
VERIFIED
  |
  +-- exact revision match
  +-- exact checksum match
  +-- chunk/block-entity/attachment count match
  |
COMMITTED
  |
  +-- authority CAS source+epoch+revision -> destination
  +-- increment epoch
  +-- activate destination writes
```

Any exception before COMMITTED:

```text
ROLLED_BACK
  source remains authoritative
  source write fence is released
  destination never becomes writer
```

## Required diagnostics

Example:

```text
[Cluster] REGION_TRANSFER PREPARE region=world:minecraft:overworld:0,0 source=worker-002 dest=worker-001 epoch=14 revision=392
[Cluster] SOURCE_FLUSH COMPLETE ... checksum=<sha256>
[Cluster] DEST_INVALIDATE COMPLETE ...
[Cluster] REGION_SYNC VERIFIED ... checksum=<sha256>
[Cluster] AUTHORITY_COMMIT ... epoch=15 revision=392
```
