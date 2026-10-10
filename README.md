# Foundations Cluster — 0.0.7a-dev.2.R3 / Node R17

Minecraft **1.21.1** / NeoForge **21.1.250** development build.

**Automatic preview discovery (new):** Instead of pairing individual cows and dropped items, a connected operator may enable `/foundations_entities auto on` on their authoritative source worker. Compatible destination viewers opt in with `/foundations_entities view on`. Node observes only **already-loaded** and **currently authoritative** native cows/items, and the Manager validates region ownership before relaying exact-UUID, dimension, position, movement and synced visual state. No destination gameplay entity is created. Manual pair commands remain for debugging.

**Not full 7a or generic entity replication.** Off-layer Survival breaking, entity ownership transfer, inventory-authoritative pickup, generic modded entity support, crash-safe transfer and visually seamless backend switching remain unproven/disabled. A brief client-visible transition pause remains possible; this release does not claim to optimize it. Current state is source-bound presentation only.

Download the uniquely versioned R3 installer from GitHub Releases, shut down **all** processes, back up the cluster and run `APPLY_UPDATE.bat` from a separate extracted folder. Confirm package R3 / Node R17 on every worker; refresh the dashboard with Ctrl+F5. The HF16 dashboard shutdown/restart/worker controls remain. Installer never targets player worlds, saved inventory, credentials, configuration or journals. Keep the updater backups for rollback.

[Entity auto-discovery test guide](docs/ENTITY_AUTO_DISCOVERY_R3_GUIDE.md) and [R3 status](docs/status/0_0_7A_DEV2_R3.md) distinguish verified tests from live gameplay acceptance. The `TEST-TOOLS` archive carries exact source and test evidence. `python tools/validate.py` runs Java/HTTP/UI regressions; `python tools/validate_survival_spike.py` runs disposable NeoForge GameTests. The legacy offline mapped build helper is not the ordinary supported build path.

Source stays in draft PR #3 while live Survival and entity migration remain incomplete. Earlier releases are never overwritten. Frozen architecture remains unchanged.
