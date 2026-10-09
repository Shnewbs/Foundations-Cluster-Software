Foundations Cluster 0.0.6a.R19-HF16 / Node R14 (pre-release)
Worker controls and recovery hardening

INSTALL
1. Stop Manager, gateway and every worker. Confirm the Java processes have exited.
2. Extract this updater separately, not over the running installation.
3. Run APPLY_UPDATE.bat and select the existing Cluster folder.
4. Start Foundations normally, then refresh the dashboard with Ctrl+F5.

Accepts HF11 through HF16. Includes the compiled Manager, R19 gateway artifacts,
Node R14, and the complete dashboard script/style set. Manager installs the staged
Node into its configured template and synchronizes stopped workers through the
existing backup-preserving path. No Gradle download is needed for this updater.

CONTROLS
Restart Servers: rolling restart of managed Minecraft workers only; Manager and
gateway stay online. Shutdown Cluster: closes Manager and its owned gateway and
requests shutdown of managed workers; external processes are not terminated.
Click a worker row to open details. Restart, Stop and Kill are adjacent actions.
Stop is graceful and evacuation-aware. Kill immediately terminates the process
and may lose unsaved world changes; it is an emergency action with confirmation.

DATA AND ROLLBACK
Worlds, profiles, credentials, active configuration and runtime journals are not
payload files. Existing application files are backed up before replacement.
Keep this updater and the matching printed update-backups/HF16-... directory.
Rollback with this updater's tools/apply_update.ps1 -ClusterRoot <installation>
-Rollback <matching backup path>. Older updater backups need their matching tools.
Later Node/template synchronization and gateway runtime patches have separate
backups. Never delete a world to resolve a Node version mismatch.

LIMITS
The recovery reference model is integrated in source, not connected to production
Minecraft inventories. Remote Survival breaking and placement remain disabled.
Browser tests use simulated workers; live-client and multi-worker acceptance
remain separate. Read release_notes/0.0.6a.R19-HF16.md in the repository for scope.
