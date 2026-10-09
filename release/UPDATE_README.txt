Foundations Cluster 0.0.7a-dev.2 / Node 0.0.6a.R15
INSTALLABLE DEVELOPMENT BUILD: live-player observations and read-only save checks.
NOT completed two-worker Survival. Remote Survival edits remain disabled.

Stop Manager, gateway and every worker. Extract separately and run APPLY_UPDATE.bat,
selecting the existing stopped Cluster folder. Keep the printed backup path. Start
normally and confirm Node R15 on every worker. No separate playtest mod is needed.

Supports HF11 through HF16 or dev.2. Existing config, worlds, credentials, profiles,
player saves, journals and reports are excluded from the updater payload. Node
synchronization and gateway runtime updates have their own later backups.

Open PLAYTEST_GUIDE.md for commands, expected results, reports and rollback.
Commands: /foundations_playtest start control <label>, mark <label>, check-save,
status and stop. Your connected operator must explicitly start every capture.
Reports remain local; review before sharing. Diagnostics never restore inventories.
