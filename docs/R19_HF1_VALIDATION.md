# R19-HF1 registry packet hotfix — 2026-09-27

The reported client error was `Failed to decode packet clientbound/minecraft:registry_data`.

## Reproduced defect

R19's configuration observer called `RegistrySyncPacket.encode` before the normal client encoder. In Conduit 1.8.0 that method consumes its internal ByteBuf with `writeBytes(content())`. A reproducer using the actual Conduit packet class captured 26 bytes and then forwarded **zero bytes** from the same packet. The screenshot alone cannot exclude another client-side issue, but this is a confirmed R19 regression consistent with the reported packet.

## Fix

The observer now copies registry payload bytes through absolute reads without invoking its consuming encoder. The original reader/writer indices, bytes and reference count remain intact. Capture failures mark seamless replay unavailable for that connection instead of throwing through the initial login's observer. The capture hook runs during initial login regardless of seamless policy, so toggling seamless off alone does not remove the defective R19 capture.

## Validation

- Reproduced the failure against the original R19 source and real Conduit 1.8.0 JAR: 26-byte registry payload became zero on client encoding.
- Corrected code passes 29 checks covering heap, direct and composite buffers, nonzero reader indices, reference counts, independent copied storage, repeated capture, and encoding the same object for the client afterward.
- Tags, active features and known-packs packet bytes remain unchanged after observation. A synthetic capture-encoding failure marks replay unavailable without throwing.
- Existing guarded handshake suite passes all 20 checks.
- The same tests run with the overlay installed into a staged Conduit JAR, using the classes from that patched core.
- Source/updater archive integrity and all updater payload hashes are checked. The transactional updater logic and Manager/Node/Gateway JARs are byte-identical to R19. The prior R19 installation/rollback fixture report is retained as historical evidence; Windows process discovery and an actual connected Minecraft client were not tested here.

## Install

Stop Manager, gateway and all workers. Extract `FoundationsCluster-0.0.6a.R19-HF1-UPDATER.zip`, run `APPLY_R19_UPDATE.bat`, select the existing Cluster directory, and restart. The updater supports reinstalling over R19 and applies the corrected Conduit classes to the existing core. Existing setup also reapplies the bundled overlay at startup. The backup path is printed normally.

Component versions stay Manager R19, Node R9 and Gateway R19; HF1 identifies this overlay/package revision. No client mod replacement or world reset is part of this fix. First test a fresh login, then an ordinary transfer, then experimental seamless transfer. Seamless acceptance and the previously documented persistence/mod/profile limitations remain open.

If the packet error persists, use Open Report Directory and provide the newest disconnect report plus the client `logs/latest.log` and cluster gateway log from that attempt.
