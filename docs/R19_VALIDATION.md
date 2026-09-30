# R19 validation and limits — 2026-09-25 UTC

Candidate: Manager 0.0.6a.R19, Node 0.0.6a.R9, Gateway 0.0.6a.R19; Minecraft 1.21.1, NeoForge 21.1.250, Conduit 1.8.0 with the R19 overlay. This release extends R18 without changing the original 0.0.6a scope.

## Changes and evidence

The second supplied R18 gateway log has seamless mode enabled and stalls on `neoforge:register`. The first supplied run uses normal physical reconfiguration. R19 captures replies from the actual client's completed initial configuration and reuses them only when destination configuration matches. NeoForge network-map ordering is canonicalized without dropping version or channel values. Unknown, changed, missing or oversized configuration rejects explicitly. Transcripts are bounded, private to the connection and held only in memory. No real joined-client seamless transfer was available here, so live seamlessness remains unproven.

The chest loss path had no durable-save barrier between a source's coalesced writes and a changed ownership epoch. R19 seals the source region, closes open containers there, flushes terrain and fresh BlockEntity snapshots, waits for Manager durability, and acknowledges with region epoch and worker-process identity. Ownership remains at the source if save, acknowledgement or destination validation fails. The seal pauses block mutation and BlockEntity ticks; it is not full entity or arbitrary mod simulation suspension.

Terrain journaling now observes authoritative LevelChunk block changes beyond player actions. The integration test uses a real summoned creeper, waits for the resulting journal acknowledgement, hard-kills the source JVM and checks the survivor against the recorded destroyed blocks. This proves acknowledged authoritative blast recovery. It does not prove zero loss of writes still in flight or automatic preservation of independent changes on a non-authoritative replica.

## Automated checks

| Check | Result | Boundary |
| --- | --- | --- |
| Manager regression suites | 65 retained + 9 R19 assertions passed | Source-preserving save success, rejection, failure and cancellation |
| Handshake replay | 20 checks passed | Captured reply matching, changes, ordering, unknown tasks, canonical network maps; no real connected game client |
| Real NeoForge integration | 7 checks passed | Two workers, immediate chest with 17 diamonds, acknowledged creeper crater after hard kill, time/weather, owner loss, Manager restart |
| Chromium WebUI/API | 55 checks passed | Existing pages plus save status, support summary and normal-transfer recovery |
| Test Lab fixtures | 20 acceptance + 16 button cases passed | JavaScript/API fixture behavior |
| Gateway bootstrap | 48 assertions passed | Offline PowerShell fixtures, fresh/cached/corrupt prerequisites, full overlay hashes and rollback |
| Updater | 6 checks passed | R18→R19 apply/hashes, protected state, Node normalization, all overlay classes, exact rollback and forced-failure rollback |

The Manager and Node compile with JDK 21. Node R9 is built against real mapped NeoForge artifacts using `tools/build_node_mapped.py` and was loaded by real NeoForge workers. The normal Gradle/ModDev dependency resolution path was not run. Gateway and overlay compile with JDK 25, producing Java 21 bytecode. Windows process discovery, GUI pickers, launchers and native desktop behavior still require Windows acceptance; Linux PowerShell fixtures mock Windows process discovery.

## Reproduction

```sh
bash tools/test_manager.sh
python tools/build_node_mapped.py --deps /path/to/build-deps --javac /path/to/jdk21/bin/javac
FOUNDATIONS_JDK25=/path/to/jdk25 bash tools/build_gateway_from_conduit.sh
python tests/persistence_integration_test.py --template /path/to/neoforge-template --java /path/to/jdk21/bin/java --output TestReports/R19/persistence
node tests/testlab_acceptance_test.cjs
node tests/testlab_buttons_test.cjs
python tests/gateway_bootstrap_test.py --conduit-jar /path/to/conduit-1.8.0.jar --powershell /path/to/pwsh
```

The browser test uses `PLAYWRIGHT_MODULE`, `CHROME_EXECUTABLE`, `TEST_JAVA` and `TEST_OUTPUT` to select installed tools and output. Integration fixtures use disposable worlds and stop their own processes.

## Live acceptance after updating

1. Stop the entire cluster and apply the R19 updater. Start it and hard-refresh the WebUI.
2. Confirm Manager R19, Node R9 on every worker, Gateway R19 and synchronized world environment. Run Safe Suite.
3. Use a disposable world. On the displayed active region owner, summon a creeper or make a controlled explosion. Wait for Save Health to show no pending/failed writes and a new durable save; kill that owner. Check the survivor's crater and journal revision.
4. Create a chest with known contents and immediately move its region. Confirm save completion before authority movement and inspect contents on the destination.
5. For experimental seamless acceptance, enable the policy, fully restart, reconnect the client to create a fresh transcript, and test same-dimension A→B→A. Observe whether a configuration screen remains; collect the correlated transfer trace and client latest.log on failure.
6. Diagnostics → Use normal transfers restores the saved normal policy; fully restart to apply it. A failed save keeps ownership at the source and must be investigated before retrying.

## Still open

- Independent mutations on non-authoritative replicas, complete entity state and replica tick suppression.
- Mod-specific external files and machines bypassing block mutation hooks; long-term BlockEntity churn and payload/queue limits.
- Windows advancement/profile sharing violation and immutable worker-owned profile snapshots.
- Live seamless client acceptance, custom NeoForge configuration tasks and large/multiframe transcript variants.
- TFC calendar/climate adapters and cluster-wide sleep voting.
- Full region snapshot integration, granular roles, remote hosts, shared storage/messaging and high availability.

The 48-area Feature Status page retains all earlier design areas and marks these limits explicitly.
