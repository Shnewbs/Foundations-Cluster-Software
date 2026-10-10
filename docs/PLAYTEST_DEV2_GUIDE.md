# Test Foundations Cluster 0.0.7a-dev.2

This is a versioned development updater with Node R15 and live diagnostic commands.
It keeps the HF16 dashboard/worker controls. It does NOT enable off-layer Survival
breaking or placement, and it does not repair or overwrite player inventories.

## Install

Use a stopped backup/copy of the cluster for development testing, not your only
world copy. Run the existing BACKUP_CLUSTER.bat while the cluster is fully stopped.

1. Stop Manager, gateway and all workers; confirm their Java processes have exited.
2. Extract the dev.2 UPDATER ZIP separately. Run APPLY_UPDATE.bat and select the
   existing stopped Cluster folder. Supported baselines: HF11–HF16 or this dev.2.
   The dev.1 EXPERIMENTS ZIP was not an installed baseline.
3. Keep the printed `update-backups/7a-dev2-...` path and this exact updater.
4. Start normally. Manager should show package `0.0.7a-dev.2`, API `0.0.6a.R19`,
   required Node `0.0.6a.R15`. Confirm every worker reports Node R15/READY.
   Refresh the dashboard with Ctrl+F5. Do not install a separate playtest mod.

The updater supplies built Manager, Node and gateway files. Node synchronization
uses the configured template and existing stopped-worker path, preserving backups.
It does not replace worlds, player .dat, active config, profiles, security or journals.
A new mod ID is deliberately avoided, so no fingerprint reset or weaker world
identity policy is necessary. Do not delete world-identity.txt or any world files.

## First live test — normal local Survival behavior

Join as an operator (permission level 2). Use Survival, close every menu and leave
nothing on the cursor. On a genuinely single-worker control server, or with the
player and target area on their owning worker, run:

    /foundations_playtest start control first_test
    /foundations_playtest mark before_mining

Mine stone with a pickaxe, place a few dirt blocks, toggle a lever, switch hotbar
slots and offhand, then drop and pick up an item. A named or enchanted tool is useful
for checking persistent item components. Do not use your only valuable items.

    /foundations_playtest mark after_actions
    /save-all flush
    /foundations_playtest check-save
    /foundations_playtest status
    /foundations_playtest stop

Stand still with menus closed between saving and checking, without changing items.
Expected: a report is saved and ordinary inventory reports MATCH after a successful
save with no intervening changes. MISMATCH can mean an unsaved change; it does not
prove data loss. MISSING or UNSUPPORTED is diagnostic, never an instruction to reset
or restore your inventory. Stop reports only announce saved after the file closes.

The observer does not generate test structures, hand out items, teleport players,
change gamerules, force saves or automatically change the server topology.
`/save-all flush` above is your explicit ordinary server command, not an observer
side effect. The `control` label is not automatic single-worker certification.

## Second test — compare the replicated area

On the two-worker cluster, start a separate capture on the current backend:

    /foundations_playtest start seam replicated_area

Repeat the same simple actions locally and in the replicated area. Note which
worker/coordinates are involved and any in-game denial messages. Off-layer Survival
edits are STILL EXPECTED TO BE DENIED. Do not regard that as dev.2 failing to enable
a feature it does not implement. This capture helps identify event cancellation,
server-side final-state samples, inventory changes and remaining replication gaps.

Before a known handoff, add a marker. Logout/backend changes finish that worker's
capture. After joining the destination, start another `seam replicated_area` capture.
There is no automatic cross-worker recording permission or uninterrupted client
latency measurement in this build. A short screen recording can show visible stalls
that server-side traces cannot prove or disprove.

## Reports to return

Per-worker reports are under the active world's `foundations-playtest` directory,
usually `workers/worker-###/world/foundations-playtest/playtest-*.jsonl`. Custom
`level-name` settings change that world-folder location. No data is uploaded.
Send the reports from both workers, the package/Node versions and the reproduction
steps. Review first: reports contain coordinates, item IDs/counts/damage and
pseudonymous identity/component digests. They exclude chat, raw item NBT, names/lore,
IPs, credentials and complete player saves. You do not need to send .dat files.

Optional offline summary (Python 3):

    python summarize_playtest.py report1.jsonl report2.jsonl --output summary.json

The summarizer refuses overwriting an existing output file and marks missing footer,
drops and malformed sequences. Scenario coverage is not a gameplay PASS. Saved
reports remain separate from existing Manager Support ZIPs in this increment.

## Limits / cleanup

Recording is off by default, requires your own connected operator command, lasts
at most five minutes per worker and has hard queue/record/file limits. Open menus
or unsupported item components are marked as gaps; the game is not blocked. Move
old reports elsewhere once the 32-report directory limit is reached. Nothing is
automatically deleted. Reporter failure stops diagnostics, not the server.

## Roll back

Before restarting after an update, the updater can restore its own application
backup with:

    powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\apply_update.ps1 -ClusterRoot "C:\Your\Cluster" -Rollback "C:\Your\Cluster\update-backups\7a-dev2-..."

Run that script from the extracted dev.2 updater, not an older updater. After the
cluster has started, Node synchronization has separate template/worker backups.
To return fully to HF16, stop everything, restore the matching Manager/application
backup, then restore the backed-up R14 Node in each template/worker mods folder,
removing only the R15 Node JAR (keep every unrelated mod). Never leave both Node
versions in a mods directory. Verify R14 is required/reported before testing HF16.
The safest development rollback is your complete stopped-cluster backup/copy.
