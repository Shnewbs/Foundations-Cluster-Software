Foundations Cluster 0.0.6a.R19-HF15 / Node R14 (pre-release)
Survival interaction groundwork — remote Survival edits remain disabled.

1. Stop Manager, gateway and every worker.
2. Extract this ZIP separately and run APPLY_UPDATE.bat.
3. Select the existing stopped HF11-HF15 Cluster installation.
4. Start Cluster normally and refresh the dashboard with Ctrl+F5.

The package includes the compiled Node R14. Manager installs it into the
configured template and upgrades stopped workers with backups. You do not need
to download the full source or Gradle to apply this updater.

Node explains rejected Survival edits and provides foundations_interaction_status.
The new authority executor, durable-result journal and real NeoForge tests are
groundwork. Safe cross-worker inventory/entity recovery is still a release gate;
this update does not remove the Creative-only restriction.

Worlds, credentials, player profiles, journals, active settings and seamless mode
are preserved. No experimental test mod or test world is deployed.

Rollback while stopped, using this updater:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\apply_update.ps1 -ClusterRoot "C:\Your\Cluster" -Rollback "C:\Your\Cluster\update-backups\HF15-..."

Keep this ZIP for rollback; older backups require their matching updater.
After Manager has installed Node R14, restoring payloads alone does not reverse
its template/worker Node copies. Restore the matching prior Node backups when
reverting to an older Manager; never delete worker worlds.
If interrupted, use the backup named in runtime/update-in-progress.json.

Detailed changes, test evidence and remaining gates are in the HF15 GitHub
release notes and docs/status/WORLD_REPLICATION_V2_2_SPIKE_A_C_2026-10-09.md.
