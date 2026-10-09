# Foundations Cluster — 0.0.6a.R19-HF16 / Node R14

HF16 adds separated top-bar **Restart Servers** and **Shutdown Cluster** controls,
full-row worker details, and adjacent **Restart / Stop / Kill** actions on the
Dashboard and Workers pages. It includes the merged player-ledger recovery work
and the Manager support-archive retention race fix. This is a **pre-release**.

**Remote Survival breaking and placement remain disabled.** Recovery protocol
experiments do not imply completed Minecraft inventory/chunk/entity integration.
Manager API and gateway stay at R19; the updater includes Node R14.

## Install

Download `FoundationsCluster-0.0.6a.R19-HF16-UPDATER.zip` from
[GitHub Releases](https://github.com/Shnewbs/Foundations-Cluster-Software/releases).
Stop Manager, gateway and all workers. Extract the updater separately and run
`APPLY_UPDATE.bat`, selecting your existing Cluster folder. Restart normally and
refresh the dashboard with **Ctrl+F5**.

HF11 through HF16 installations are accepted. Worlds, profiles, credentials,
active configuration and runtime journals are excluded from the updater payload.
Keep the updater and its matching printed backup path for rollback. Later
Node/template synchronization and gateway runtime patches have separate backups.

## Control scopes

| Control | Behavior |
| --- | --- |
| Restart Servers | Graceful rolling restart of managed Minecraft workers; Manager and gateway remain online. |
| Shutdown Cluster | Exit Manager/dashboard, close its owned gateway and request shutdown of managed workers. External processes are not terminated. |
| Worker Restart | Evacuate players/authority, stop and relaunch that worker. |
| Worker Stop | Graceful evacuation-aware stop without relaunch by that action. |
| Worker Kill | Emergency process termination with a warning; unsaved world changes may be lost. |

Click a non-interactive cell in either worker table to open its details; Enter and
Space work on focused rows. Buttons remain independent of row navigation. Stale,
external, unavailable or already-submitting actions are disabled. A blocked
evacuation can abort a graceful stop or restart; Kill bypasses that protection.

## Development and validation

Source remains on `main`. Run `python tools/validate.py` with a full JDK 21,
Python 3.12 and Node 22. Run `python tools/validate_survival_spike.py` for the real
NeoForge fixture tests. The Windows launcher initializes active configuration
from `config/cluster.properties.default`; direct Java launches require that copy.
Application binaries belong in release assets, not source commits.

CI validates Windows and Linux, including source paths containing spaces. The
Windows pipeline also builds the gateway and tests updater apply/rollback.
A uniquely versioned pre-release is published only after both platforms pass;
existing release assets are never overwritten by a same-version source push.
The 48 new control-logic checks use inert API/DOM fixtures. The 36 locally run
Chromium checks use production UI assets with simulated worker responses, not
live Minecraft process controls.

Read [HF16 changes and compatibility](release_notes/0.0.6a.R19-HF16.md),
[recovery evidence](docs/status/WORLD_REPLICATION_V2_2_LEDGER_RECOVERY_2026-10-09.md),
[Spike A/C and remaining gates](docs/status/WORLD_REPLICATION_V2_2_SPIKE_A_C_2026-10-09.md),
and the [hardening audit](docs/audits/2026-10-05-hardening.md).

The frozen World Replication v2.2 design is in `docs/frozen_design/`; its source
handoff roadmap is `docs/status/CURRENT_STATUS_AND_ROADMAP_2026-09-29.md`.
The previous README is preserved verbatim as [historical context](docs/history/README_HF15.md)
(relative links in that historical copy refer to the repository root). Its old
installation/version claims are superseded here. The HF9 installer is retired.
