Foundations Cluster 0.0.7a-dev.2.R6 — Cumulative updater (Minecraft 1.21.1 / NeoForge 21.1.250)

This archive updates the *application files* needed for a direct upgrade from any
supported R19-HF11 through HF16 installation or from dev.2/R2-R5. You do NOT need
to install every intermediate release. It includes the current source/build scripts,
Manager, Node, gateway and dashboard, rather than only replacing a few binaries.

STOP all workers, gateway and Manager. BACK UP the entire Cluster folder; extract
this archive to a separate folder. Run APPLY_UPDATE.bat and explicitly select the
stopped Cluster installation folder shown in the startup logs. Confirm the printed
"Installed 0.0.7a-dev.2.R6" message and backup location. Restart using your usual
launcher and check the Manager log: package 0.0.7a-dev.2.R6, Node R19 on workers.

The updater backs up and restores each replaced application file, and never includes
workers/, server-template/, runtime/, logs/, update-backups/, secrets or live config/.
It does NOT migrate world data or enable cross-worker Survival or entity ownership.
Keep backup files; rollback requires the matching R6 updater script and backup path.
If any step fails, DO NOT start the cluster; read the updater error and rollback.

This is a 7a DEVELOPMENT revision: remote Survival, inventory transactions, and
entity ownership migration are still gated, even if the installer passes validation.
