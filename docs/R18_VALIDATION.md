# Foundations Cluster 0.0.6a.R18 — time/weather and acceptance controls

2026-09-25 UTC / 2026-09-24 Pacific. Manager R18, Node R8, unchanged Gateway R16 and Conduit 1.8.0 trace overlay. Minecraft 1.21.1 / NeoForge 21.1.250.

## Confirmed causes

- All three individual Test Lab buttons referenced undefined `webLiveTestBusy` and `setWebLiveTestBusy`. Clicking them failed before reaching the API. Earlier suite coverage did not exercise these handlers.
- Full Acceptance waited for `Inventory`, while the supplied worker log at local 20:15:44 says `has the following entity data: []`. The timeout was a response-matching bug. Console polling could also read older output.
- R17 contained no cross-worker day-time or weather protocol. Equal world seeds do not synchronize clocks, rain timers or thunder state.
- In the supplied `logs.rar`, the newest gateway startup at local 20:14:02 explicitly has `seamless-server-switches = false`. Two physical handoffs then commit at 20:17:14 and 20:17:28. This run used normal reconfiguration. No new experimental bridge failure is established by these logs. Client `latest.log` was not included.

## Changes

Test Lab now has one shared run lock across safe/full/individual tests, freezes selectors during a run, reports missing opt-in in Test Output, and shows PASS/FAIL instead of unconditional DONE. Inventory parsing matches Minecraft's actual response. Unique console markers separate fresh responses from old output; these diagnostic markers appear in chat during live acceptance.

The Manager elects one healthy, seed-matched environment owner. Node R8 reports a versioned overworld snapshot: day time, fractional time, NeoForge day rate, daylight/weather game rules, rain/thunder flags, strengths and timers. Followers apply it on the server thread and synchronize clients. The source world supplies the initial clock; later starts use an atomic runtime checkpoint. Epochs, process-instance identifiers, ordered sample sequences and restore acknowledgements fence stale publishers. A new owner restores the shared state before publishing. Migration destinations must acknowledge an unexpired environment lease.

The protocol uses the existing two-second heartbeat with bounded prediction between reports. After the ten-second lease expires, the last shared environment is held pending coordination. Local `gameTime` and scheduled-tick counters are never rewritten. Send `/time`, `/weather`, and daylight/weather gamerule commands to the owner shown in Diagnostics. Sleeping on the owner can advance the shared night; cluster-wide sleep voting is not implemented.

`world.environment.enabled=true` is the default. Set it to false to opt out. Diagnostics reports the current owner and each worker's acknowledgement. The current adapter covers the vanilla/NeoForge overworld environment. Independent mod calendars, TFC seasons/climate and custom dimension weather require adapters; they are not certified by this release.

Diagnostics also reports the seamless policy read from Conduit's configuration at proxy launch, alongside the saved setting. A mismatch requires a full restart. An adopted process has unknown launch policy. This is launch-configuration evidence, not proof of client-visible seamlessness. The Gateway binary and experimental negotiation bridge are unchanged.

## Verification

| Check | Result |
|---|---|
| Manager compile and retained regression checks | PASS: 27 R16 + 9 R17 assertions |
| Environment ownership, checkpoint, stale/fenced reports, day rate, policy parsing | PASS: 29 new assertions |
| Node R8 compile | PASS against the supplied mapped NeoForge 21.1.250 artifacts with JDK 21 |
| Test Lab acceptance fixtures | PASS: 20 cases |
| Actual individual click-handler fixtures and fresh inventory output | PASS: 16 cases |
| Headless Chromium with running Manager HTTP/API | PASS: 52 checks, including the three buttons and real environment endpoints |
| Two actual headless NeoForge workers | PASS: shared snapshot acknowledgement; time/weather command propagation; owner shutdown/failover; Manager restart/checkpoint recovery |
| JavaScript syntax | PASS |
| R18 updater | PASS: apply/checksums, protected-state preservation, Node normalization, explicit rollback, forced-failure rollback |
| PowerShell updater syntax | PASS |

The real worker test uses disposable localhost flat worlds, Node R8 and NeoForge. No Minecraft client, Conduit or TFC was connected during it. It proves backend environment propagation and recovery in that setup, not rendered continuity, mod-calendar support or seamless player transfers. The normal Gradle ModDev dependency-resolution path was not rerun; the shipped Node was compiled against the supplied mapped artifacts.

Run `bash tools/test_manager.sh`, `node tests/testlab_acceptance_test.cjs`, `node tests/testlab_buttons_test.cjs`, and `node tests/web_ui_test.cjs`. The browser test accepts TEST_JAVA, PLAYWRIGHT_MODULE and CHROME_EXECUTABLE overrides. `tests/environment_integration_test.py` documents its JDK and already-provisioned/accepted-template inputs; it uses disposable workers and the Python standard library.

## Apply and check on Windows

1. Fully stop the Manager, gateway and every worker. Extract the R18 updater and run `APPLY_R18_UPDATE.bat`; select the existing Cluster folder. Keep the generated backup for rollback.
2. Start normally and hard-refresh the WebUI. Confirm Manager R18 / Node R8 on every worker. Open Diagnostics → Shared Time & Weather and wait for both workers to show Synchronized.
3. Test time/weather changes on the displayed owner, then one player A→B→A migration. Observe day/night and rain on both workers. Run live tests on a disposable world/player: chest and lifecycle acceptance intentionally change world/process state.
4. For the seamless experiment, enable Diagnostics → Physical Switch Policy, save, and fully restart the cluster. Confirm both launch policy and the new `velocity.log` startup show seamless enabled. If the preconfiguration screen remains, collect that new log and client `latest.log` with the transfer timestamps.

The earlier Windows advancement-file sharing violation remains unresolved. This revision does not claim lossless preservation of all player/mod profile data or a repaired seamless negotiation path. Checkpoints and environment synchronization do not replace that separate acceptance requirement.

Updater transaction tests used an isolated temporary installation under Linux PowerShell 7.5.3; Windows process discovery was mocked. A mismatch occurred in the initial shared-workspace fixture, so validation was repeated on an isolated native temporary directory. The unchanged transactional installer passed all five checks there. Actual Windows execution remains a target-machine acceptance step.
