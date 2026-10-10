# Foundations Cluster — 0.0.7a-dev.2.R4 / Node R18

Minecraft **1.21.1** / NeoForge **21.1.250** development build.

**Automatic preview discovery (new):** Instead of pairing individual cows and dropped items, authoritative source workers automatically scan relevant loaded entities near consenting viewers; a connected operator can suspend source discovery with `/foundations_entities auto off` or resume it with `auto on`. Compatible destination viewers must independently opt in with `/foundations_entities view on`. Node observes only **already-loaded** and **currently authoritative** native cows/pigs/sheep/chickens/items, and the Manager validates region ownership before relaying exact-UUID, dimension, position, movement and synced visual state. No destination gameplay entity is created. Manual pair commands remain for debugging.

**Not full 7a or generic entity replication.** Two durable reference models now exercise owner generation CAS and pickup effect phases, but are not wired to Minecraft gameplay or worker saves. Off-layer Survival breaking, entity ownership transfer, inventory-authoritative pickup, generic modded entity support, live crash-safe transfer and visually seamless backend switching remain unproven/disabled. A brief client-visible transition pause remains possible; this release does not claim to optimize it. Current state is source-bound presentation only.

Download the uniquely versioned R4 installer from GitHub Releases, shut down **all** processes, back up the cluster and run `APPLY_UPDATE.bat` from a separate extracted folder. Confirm package R4 / Node R18 on every worker; refresh the dashboard with Ctrl+F5. The HF16 dashboard shutdown/restart/worker controls remain. Installer never targets player worlds, saved inventory, credentials, configuration or journals. Keep the updater backups for rollback.

[Entity auto-discovery test guide](docs/ENTITY_AUTO_DISCOVERY_R3_GUIDE.md) and [R4 status](docs/status/0_0_7A_DEV2_R4.md) distinguish verified tests from live gameplay acceptance. The `TEST-TOOLS` archive carries exact source and test evidence. `python tools/validate.py` runs Java/HTTP/UI regressions; `python tools/validate_survival_spike.py` runs disposable NeoForge GameTests. The legacy offline mapped build helper is not the ordinary supported build path.

Source stays in draft PR #3 while live Survival and entity migration remain incomplete. Earlier releases are never overwritten. Frozen architecture remains unchanged.
