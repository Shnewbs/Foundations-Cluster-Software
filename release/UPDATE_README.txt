Foundations Cluster 0.0.6a.R19-HF12 control-plane updater (pre-release)

Requires an existing HF11 installation with Node R13. This package updates the
Manager, Argon2 dependency and guarded Windows launcher. Node and gateway are
unchanged. This is not a fresh installation or completed World Replication v2.2.

1. Stop Manager, gateway and all workers. Keep your normal off-machine backup.
2. Extract this entire ZIP into a separate folder, such as Downloads.
3. Run APPLY_UPDATE.bat and enter your existing Cluster installation folder.
4. Start the cluster and sign in again. Older remembered sessions are invalidated.

The updater verifies every payload checksum, backs up all replaced files and
attempts rollback on failure. Worlds, runtime/security, journals and active
config are excluded from its payload. Never copy payload directly over a live
installation. Download updater assets from the project's GitHub Releases page;
the automatically generated source-code archives are for development.

Manual rollback (cluster must be stopped):
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\apply_update.ps1 -ClusterRoot "C:\Your\Cluster" -Rollback "C:\Your\Cluster\update-backups\HF12-..."

If interrupted, use the backup recorded in runtime/update-in-progress.json.
Do not delete that marker merely to bypass recovery. Keep the updater ZIP so its
rollback command remains available. Directory flush support varies by platform;
on Windows, recovery journals may grow because compaction keeps the WAL.
