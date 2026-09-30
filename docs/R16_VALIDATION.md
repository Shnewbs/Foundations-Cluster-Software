# R16 validation record

Date: 2026-09-23. Baseline: supplied Cluster(4).zip / Manager R15, Node R7, Gateway R15.

| Area | Result | Evidence / limitation |
|---|---|---|
| Manager build | PASS | JDK 21, Java 21 target |
| Gateway build | PASS | JDK 25 compiler, supplied Conduit 1.8.0 API, Java 21 bytecode |
| Conduit instrumentation | PASS (compile) | Java 25 overlay; negotiation behavior deliberately unchanged |
| Node source | PASS (compile) | Unchanged R7 source, supplied mapped NeoForge artifacts, JDK 21 |
| Node normal Gradle pipeline | BLOCKED | ModDev 2.0.147 could not be resolved in this environment |
| Manager regression suite | PASS | 27 assertions: atomic config rejection/round trip, NaN rejection, shutdown single-flight, migration health limits, fresh telemetry, pins, bounded trace, redaction and export allowlist |
| Reference coordinator | PASS | RegionTransferCoordinatorSelfTest |
| Persistent region reference | PASS | PersistentRegionTransferSelfTest |
| Browser/API | PASS | 42 checks: authentication, cross-origin rejection, invalid config atomicity, host/pin/export, 18-page navigation, drawers, keyboard search, unsaved forms, search, responsive layout, partial API outage, alert acknowledgement, stopped-worker metrics and clean shutdown |
| Visual review | PASS | Chromium screenshots at 1536×1000 and 390×844; desktop dashboard, mobile, world map and Diagnostics inspected |
| Backup tool | PASS | Create, verify, cache exclusions, nested-output refusal, overwrite refusal, corrupted-payload rejection |
| Updater transactions | PASS | Actual PowerShell updater: installation, protected-state preservation, JAR normalization, duplicate removal, explicit rollback and automatic rollback after a mid-update failure; Windows process discovery mocked in the Linux fixture |
| PowerShell | PASS (parse) | All 11 PS1 files parsed with PowerShell 7.5.3 |
| Native Windows launcher/desktop | NOT RUN | This environment is Linux; folder picker, Windows JDK paths/process checks and Tkinter window require target acceptance |
| Joined Minecraft client | NOT RUN | No actual NeoForge client attached; synthetic agent telemetry is used only in API/UI tests |
| Seamless transfer | NOT PROVEN | Prior R15 destination configuration timeout remains an explicit blocker |
| Crash/evacuation/world soak | LIVE ACCEPTANCE | Runtime code is retained; actual inventory, mod attachments, epoch fencing, chest/machine persistence and crash behavior need a live cluster |

The UI test deliberately injects one HTTP 503 to verify partial-outage handling; that expected browser console entry is not a JavaScript exception. Screenshots contain synthetic six-worker telemetry and are layout previews, not production measurements.

## Run locally

On Linux with JDK 21 in PATH:

```sh
bash tools/test_manager.sh
bash tools/compile_and_test_reference.sh
```

The browser test needs Node, Playwright and Chromium. Set TEST_JAVA, PLAYWRIGHT_MODULE and CHROME_EXECUTABLE if they are not on the default paths, then run `node tests/web_ui_test.cjs`. It creates an isolated temporary Manager with workers/bootstrap/gateway disabled, uses generated test credentials, starts no Minecraft workers and deletes the fixture after the test.

## Required live sequence

1. Update a stopped R15 test installation and verify Manager R16 / Node R7 / Gateway R16.
2. Boot with normal physical switching. Verify Java 21 workers and Java 25 gateway.
3. Join through the gateway. Transfer A→B→A and confirm anchor, simulation, inventory, location and mod state.
4. Drain and gracefully restart the current worker. Verify it stays alive until migration completes.
5. Test hard crash recovery once, then verify there is no redirect loop.
6. Shut down the cluster with a joined player. Confirm no emergency quarantine/failover storm.
7. Test placed blocks, chests, dynamic BlockEntities and stale-authority writes across restarts.
8. Enable experimental seamless mode only on the test installation. Capture `FoundationsConfigTrace` lines and the browser transfer timeline for the unanswered task.
9. Test updater rollback and offline backup on a copied Windows installation before applying to valuable worlds.
