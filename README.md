# Foundations Cluster — 0.0.7a-dev.2 / Node R15

Installable development update after HF16: opt-in connected-player observations,
read-only player .dat inventory comparisons, local report summaries and real
scheduled-tick codec/time-skew tests. Manager API and gateway stay at R19.

**Remote Survival breaking/placement remain disabled. This is not completed 7a.**
The dev.1 inventory journals/adapters remain groundwork; this release does not
install them as the live inventory authority or restore player saves.

Download the versioned UPDATER asset, stop the entire cluster, extract separately
and run APPLY_UPDATE.bat. Test on a stopped backup/copy. Confirm Node R15 on every
worker. Existing HF16 shutdown/restart controls, row navigation and Stop/Kill remain.

[Install and live test guide](docs/PLAYTEST_DEV2_GUIDE.md)
[Changes, evidence scope and remaining gates](docs/status/0_0_7A_DEVELOPMENT_2.md)

Start with `/foundations_playtest start control first_test` as a connected operator.
Recording is off otherwise. `mark`, `status`, `check-save`, and `stop` are read-only
with respect to game/player state. Reports remain under the active world's
`foundations-playtest` directory and are never uploaded automatically.

Build with Java 21, Python 3.12 and Node 22: `python tools/validate.py`, then
`python tools/validate_survival_spike.py`. Windows CI also validates gateway builds
and updater apply/rollback. Source and evidence are included in TEST-TOOLS assets.
Development publication is gated on both platforms; existing releases are not replaced.

Frozen World Replication v2.2 design: `docs/frozen_design/` (unchanged).
Previous HF16 instructions: [historical README](docs/history/README_HF16.md).
