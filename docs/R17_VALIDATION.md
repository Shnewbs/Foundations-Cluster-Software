# R17 startup correction — validation

Date: 2026-09-23. Manager 0.0.6a.R17; unchanged Node 0.0.6a.R7 and Gateway 0.0.6a.R16; Minecraft 1.21.1 / NeoForge 21.1.250.

## Test Lab correction and supplied Windows logs

The later R17 WebUI hotfix removes the stale R15 Manager and seamless-transport checks. It checks Manager health/status agreement on the 0.0.6a.R17-or-later line, requires the actual Gateway R16 heartbeat, and reports the saved seamless setting without claiming that it is active. Full Acceptance explicitly skips live actions after failed preflight. Existing live-test opt-in controls remain in place.

Validation for this hotfix: `node --check web/app.js` and `node tests/testlab_acceptance_test.cjs` pass (17 fixture cases). The fixture executes the real acceptance functions, rejects stale/malformed versions and incomplete gateway startup, checks both transfer policies, and verifies that failed preflight cannot initiate a handoff. Earlier bootstrap/Manager/browser/updater results below belong to the initial R17 build; those suites were not rerun for this JavaScript/documentation-only correction. Runtime binaries and updater implementation are unchanged.

The supplied Windows logs now show successful bootstrap and three committed physical transfers. Conduit started with seamless mode **false**; a later configuration edit has no subsequent restart in this capture. There is no experimental configuration trace to diagnose yet. A failed player-profile capture and a Windows advancement-save sharing violation remain unresolved; committed transfers do not establish complete state preservation. See `docs/R17_TRANSFER_NEXT_STEPS.md` for the evidence and next experiment.

## Cause and corrections

The R16 setup script called `File.Replace` with an undefined staging path in both its existing-core and freshly downloaded-core branches. PowerShell parsing alone did not catch this runtime defect. The gateway stopped before installing Proxy Compatible Forge, leaving manual worker spawn to report the missing dependency.

R17 replaces only actual staged files, prefers the bundled adapter JAR, validates forwarding JAR metadata, stages downloads before installation, and cleans failed downloads. The forwarding mod is **Proxy Compatible Forge 1.2.2**, whose supplied `META-INF/neoforge.mods.toml` declares mod ID `pcf` and NeoForge support. The loader remains NeoForge.

The Manager checks forwarding prerequisites before copying a new worker directory. The startup API reports readiness explicitly, and worker-creation buttons wait for it. The startup panel displays gateway bootstrap failures. Scaling displays the saved RAM calculation above the controls, uses GiB labels, and no longer logs an identical recommendation every 30 seconds. Saved user RAM settings are not changed.

| Check | Result |
|---|---|
| Reproduce R16 bootstrap defect | Confirmed with the real script and fixture network responses |
| R17 bootstrap fixture | 32 assertions pass: fresh/cached Conduit, bundled Gateway plugin, corrupt plugin repair, absent/corrupt forwarding JAR, dependency synchronization, failed-download cleanup, failed-overlay core preservation |
| Manager compile | JDK 21 / Java 21 target |
| Manager regression checks | 27 retained R16 assertions + 9 R17 forwarding assertions pass |
| Browser/API | 47 checks pass, including dependency gating, failure explanation and zero-capacity arithmetic |
| Visual review | Scaling capacity explanation inspected at 1536 px; synthetic telemetry |
| PowerShell syntax | All 12 scripts parse with PowerShell 7.5.3 |
| R17 updater transactions | PASS: R16→R17 apply, protected-state preservation, JAR normalization, explicit rollback and automatic rollback after a forced mid-update failure; Windows process discovery mocked |
| Actual Windows / Minecraft client | Not executed here; complete the target-machine startup and live transfer acceptance sequence |

The bootstrap fixture uses the actual supplied Conduit core and plugin, but mocks network responses and Java installation metadata. Its forwarding download is a metadata fixture. It tests script execution and installation behavior, not a live NeoForge server. Older R16 compile/soak limitations remain documented in R16_VALIDATION.md.

## Run checks

```sh
bash tools/test_manager.sh
python tests/gateway_bootstrap_test.py --conduit-jar /path/to/conduit-1.8.0.jar --powershell /path/to/pwsh
node tests/web_ui_test.cjs
```

Browser checks require Playwright/Chromium; environment overrides remain documented in R16_VALIDATION.md.

## On the Windows installation

1. Stop the cluster and apply the R17 updater to the existing folder.
2. Start `start_foundations_server.bat` with internet access for any missing gateway dependencies.
3. Confirm `Proxy Compatible Forge 1.2.2 validated in server-template/mods` and the bundled Gateway R16 validation in `logs/velocity-bootstrap.log`.
4. Wait for gateway preparation to finish, then start a worker if startup has not already created the configured baseline.
5. Confirm Node R7 joins Manager R17, then test a real player login. Seamless transfer remains experimental and is not claimed fixed.

If download/provisioning still fails, provide `logs/velocity-bootstrap.log`; the Manager's exit-code message alone does not identify a network/provider failure.
