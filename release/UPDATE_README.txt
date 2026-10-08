Foundations Cluster 0.0.6a.R19-HF13 gateway bootstrap repair (pre-release)

For an existing stopped HF11, HF12 or HF13 installation with Node R13.
This includes the HF12 Manager hardening and fixes the missing R19 bridge class
failure. It includes the compiled Conduit bridge, gateway plugin and their source.
The isolated gateway Java 25 runtime can remain a JRE when using these binaries.
Manager and Minecraft workers remain on Java 21.

1. Stop Manager, gateway and every worker. Keep your normal off-machine backup.
2. Extract this entire ZIP into a separate folder, such as Downloads.
3. Run APPLY_UPDATE.bat and enter the existing Cluster installation folder:
   C:\Your\Foundations-Cluster-Software-main
4. Start Cluster normally. Gateway setup applies and verifies the bundled bridge.

No full source download or manual Java installation is needed for this update.
The updater verifies every payload checksum and backs up all replaced files.
Worlds, runtime/security, journals, active config and Node R13 are excluded.
Gateway setup preserves the forwarding secret and backs up the proxy core before
applying the bridge. Do not copy payload directly over a live installation.

A fresh source ZIP needs an initial launch to create config and build Manager.
Source startup now compiles the gateway automatically with an isolated JDK 25.
This updater is not a fresh installer and does not add live World Replication v2.2.

Rollback (cluster must be stopped):
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\apply_update.ps1 -ClusterRoot "C:\Your\Cluster" -Rollback "C:\Your\Cluster\update-backups\HF13-..."

If interrupted, use the backup recorded in runtime/update-in-progress.json.
Keep this ZIP for rollback. Older HF12 backups must use the HF12 updater.
The transaction restores the payload files; gateway runtime changes made by a
later startup are separate. This release recompiles the existing R19 bridge
behavior and does not change its protocol. Live client acceptance remains required.
