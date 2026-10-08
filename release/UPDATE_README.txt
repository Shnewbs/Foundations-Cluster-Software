Foundations Cluster 0.0.6a.R19-HF14 Test Lab fixture repair (pre-release)

For an existing stopped HF11, HF12, HF13 or HF14 installation with Node R13.
Includes the HF12 control-plane hardening and HF13 gateway bootstrap repair.

1. Stop Manager, gateway and every worker. Keep your normal off-machine backup.
2. Extract this ZIP separately and run APPLY_UPDATE.bat.
3. Enter your existing stopped Cluster folder, then start Cluster normally.
4. Refresh the dashboard with Ctrl+F5 to load the updated Test Lab.

The generated-tree test checks up to seven 16x24x16 volumes in spawn chunk 0,0
at Y floors 280, 296, 256, 232, 208, 184 and 160. Occupied areas are skipped;
it never clears an occupied area to make space. If every candidate is occupied,
the test remains blocked and reports the reason. It logs the selected bounds.
It compares all 6144 states across the transfer, retains unexpected changed
blocks for inspection and confirms fixture removal before reporting success.

Worlds, credentials, journals, active configuration, Node R13 and your seamless
mode setting are preserved. Updates include web/app.js and web/index.html,
which are application files and are backed up along with the other payloads.
HF13 gateway artifacts are still bundled; no complete source download is needed.

Rollback with this updater while stopped:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools\apply_update.ps1 -ClusterRoot "C:\Your\Cluster" -Rollback "C:\Your\Cluster\update-backups\HF14-..."

Use the matching old updater for older backups. If interrupted, use the backup
recorded in runtime/update-in-progress.json; do not delete that guard to bypass
recovery. A source folder needs its first launch before it can accept updates.

Known limitation: Node R13's remote block-edit prototype only forwards Creative
break/place actions. Survival edits on a replica are cancelled. HF14 does not
implement remote Survival tools, drops or inventory transactions. Do not disable
that protection to make replicas write independently. Live v2.2 integration and
multiworker game acceptance remain separate work.
