# R19-HF8 validation — 2026-09-28 UTC

Components: Manager R19, package R19-HF8, Node R11, Gateway R19. Based on the complete HF7 source; the working gateway JAR and Conduit overlay are unchanged.

## Support capture

Open **Diagnostics → Support ZIPs → Create & Download Latest**. The Manager compresses recent logs, merged block/BlockEntity journal snapshots, region/seed/environment metadata, the operational summary and the current Test Lab report. Captures are stored under `Cluster/support-bundles/` and downloaded through an authenticated administrator endpoint. Nothing is sent anywhere automatically.

Optional settings apply without restart:

| Setting | Default | Meaning |
| --- | --- | --- |
| Capture failed Test Lab checks | Off | Capture at the first failure, throttled to one request per minute in that browser |
| Interval | 0 minutes | Off; set 1–10080 for periodic local capture |
| ZIPs to keep | 5 | 1–50 captures; only files with the tool's own naming pattern are pruned |
| Recent log size | 4 MiB | Per file; newest complete lines, up to 80 recent log files |
| Journal snapshot limit | 32 MiB | Per journal; an oversized snapshot is omitted rather than cut into an invalid TSV |
| Total uncompressed limit | 128 MiB | Omitted files and truncated tails are listed in `manifest.json` |

Journal snapshots merge acknowledged snapshot/WAL entries while holding each journal's lock. They are captured separately, not as one transaction across the cluster. Vault, authentication sessions, player profiles, raw configuration, world folders, binary logs and symbolic links are excluded. Known Manager/forwarding secrets and credential labels are redacted from text. Logs and block-entity payloads can still contain player names, chat, signs, coordinates and local paths; review before sharing.

Support captures are diagnostic evidence, not full restorable world backups. The existing **BACKUP_CLUSTER.bat** remains the offline private backup path and now includes `logs/` as well as runtime/world/configuration data. It verifies process state, checksums and archive contents; it requires Python and a stopped cluster. Private backups contain security data and should not be used as support bundles.

## Persistence changes

The supplied runtime journal stores `minecraft:fire;age=10;east=false;north=false;south=false;up=false;west=false` at **65,15,43**, matching the repeated HF7 hydration failure in the worker log. The actual MCA region file was not supplied. NeoForge's LevelChunk placement calls fire's `onPlace`, which can remove fire or create portal blocks during reconstruction; flags suppressing neighbor updates did not prevent that callback.

- Snapshot replay now suppresses placement/removal gameplay callbacks while retaining LevelChunk lighting, heightmap and BlockEntity bookkeeping. Replayed fire resumes its intrinsic scheduled tick. Existing air variants are treated as equivalent if an empty section normalizes them.
- Normal mutation capture reads the final world state after nested writes, avoiding an outer placement overwriting a newer mutation with the requested state.
- Replay failures include expected/actual states, region and journal revision; retry requests are limited to one every five seconds.
- Chunk requests/snapshots validate coordinates against the claimed region; BlockEntities must fall inside the declared chunk and height interval. This closes a route for a stale player-region assignment to request/apply a neighboring region's chunk.
- The headless tree fixture now waits for the Node readiness acknowledgement instead of a fixed delay. The first HF8 integration attempt exposed that test race: the Manager became ACTIVE immediately after the dirt command was sent, so the sealed source correctly rejected it. The rerun uses the readiness command already used by the WebUI.

## Executed validation

| Check | Result |
| --- | --- |
| Manager regression suite | 109 existing assertions passed |
| Support archive tests | 25 checks passed: defaults, scheduled/failed-test capture, redaction, exclusions, bounded tails, manifests, traversal, retention |
| Node hydration ledger | 20 checks passed |
| Real Manager hydration HTTP contract | 13 checks passed with simulated worker reports |
| Real support HTTP integration | 20 checks passed: authentication, configuration, ZIP contents, opt-in failure capture and same-second retention |
| WebUI acceptance/button/handoff/persistence/support tests | 78 checks passed |
| Browser/API integration | 61 checks passed, including actual settings save, ZIP download, navigation and mobile dashboard overflow |
| Real two-worker NeoForge 21.1.250 integration | 9 checks passed; see below |
| Offline backup fixture | Logs included, private runtime retained, process lock excluded, checksums verified |
| Builds | Manager and Node R11 compiled for Java 21; Node compiled against mapped NeoForge 21.1.250 |

The two-worker run passed shared environment acknowledgement; clock/weather propagation; saved unsupported-fire restoration without a stuck hydration barrier; a chest with 17 diamonds across authority transfer; identical hashes for all 6,144 blocks around a generated oak across an authority round-trip; durable capture of an actual creeper blast; crater recovery after killing the owner; environment owner failover; and Manager restart restoration. Disposable localhost worlds were used with an already accepted EULA from the provided template.

The supported mapped Node build completed. Browser verification used a provisioned Chromium headless shell after the default Playwright browser download returned an invalid archive. UI screenshots and machine-readable results are included. No Windows execution or connected Minecraft game client was available.

## Remaining limits and next live test

Generated-tree authority transfer is verified; natural growth on non-authoritative physical replicas, scheduled ticks, entities, full-region persistence and mod-specific simulation remain unfinished. Existing unjournaled losses cannot be reconstructed. This release does not claim complete shared-world consistency or client-visible seamlessness.

Stop the whole cluster, apply HF8 with `APPLY_R19_UPDATE.bat`, restart, and confirm the startup package marker is HF8 and both workers report Node R11. Enable failure capture. Run Chest / BlockEntity and Generated Tree separately, then Full Acceptance. Also observe natural sapling growth and an actual player transfer. If a test fails, download the new Support ZIP from Diagnostics. Do not delete journals or world files as a repair step.
