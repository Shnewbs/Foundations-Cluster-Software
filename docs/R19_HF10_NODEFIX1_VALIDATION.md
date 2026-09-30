# Foundations Cluster HF10-NODEFIX1 — validation and repair scope

## Release

- Runtime API version: `0.0.6a.R19` (unchanged).
- Startup package marker: `0.0.6a.R19-HF10-NODEFIX1`.
- Required Node: `0.0.6a.R12`.
- Gateway R19 / Conduit / Node R12 gameplay and replication source: unchanged.
- Precompiled Manager SHA-256: `faa157b77867da03513051c2a0be930ce74067caf357d8b4b20c43594b5d82fb`.

## Verified cause

The supplied HF10 SOURCE ZIP and the earlier NODE-REPAIR updater both contain
`cluster-manager/src/main/java/com/foundations/cluster/manager/ClusterVersion.java`
with `NODE_VERSION = "0.0.6a.R11"`, although the repaired Node build properties
and NeoForge mod metadata specify `0.0.6a.R12`.

The user's `Foundations-support-20260928-184011-f3369436.zip` independently confirms
this in `summary.zip/runtime.json`: `nodeExpected` is `0.0.6a.R11`.
The Manager constructs its expected JAR filename from that constant. Consequently,
a successfully built/installed R12 JAR cannot satisfy the original HF10 Manager's
R11 prerequisite. Repeated successful R12 builds do not solve that contradiction.

The older R11 worker startup lines in the support ZIP are historical; they do not
prove new workers launched after the latest repair. This repair does not rely on
that earlier diagnosis. Worker folders can contain actual `world-worker-*` data;
they are not treated as universally disposable.

## Code changes

1. Correct the expected Node constant to R12 and rebuild the entire Manager,
   including classes that inline that constant. Add the distinct NODEFIX1 marker.
2. Require the embedded Node mod ID/version, entry class, and mixin descriptor
   before accepting a template/build artifact. A renamed R11 JAR is rejected.
3. Build-only subprocess invocation: the Manager owns installation into the
   configured template, rather than passing a hard-coded `--install` to Gradle's
   wrapper script. Installation stages the new file before replacing it and backs
   up stale template Node JARs before removing them.
4. Missing/wrong artifacts produce the exact expected version/path and observed
   build filenames. Three consecutive failures pause automatic builds. Manual
   Build/Install remains available, and a subsequently present valid artifact is
   detected without requiring build scripts.
5. Stored-worker synchronization checks the validated current template before
   removing or copying worker Node JARs. It retains existing normal lifecycle flow.

## Offline updater

The repair ships a compiled Java 21 Manager. It does **not** build Node or download
anything. It finds the existing R12 JAR in the build cache, configured template,
active workers, or the exact `*-R11-backup` worker directory, and verifies it.
If no valid R12 JAR exists, deployment is refused rather than reporting success.
The local Node source must already declare R12; this is an HF10 repair, not an
upgrade from a different base version.

The updater respects configured in-root template/worker paths, verifies payload
SHA-256 values, checks Cluster Java processes/ports and acquires the Manager lock.
Out-of-root or linked deployment paths are refused. File changes are recorded
before mutation in a durable rollback journal under `.foundations_update_backups`.
Failures trigger rollback. The separate rollback BAT supports reversing a completed
repair while the Cluster is stopped.

If the active worker folder is absent/empty and its `*-R11-backup` sibling exists,
the updater asks for `RESTORE`. It moves that original folder back, preserving its
world contents; it does not merge populated folders or regenerate worlds. Without
a saved folder, normal Manager provisioning remains responsible for workers.

## Executed checks

| Check | Result | Scope |
|---|---:|---|
| Full Manager compilation | PASS | Java 21, `javac --release 21` |
| New Node startup/deployment tests | 33 PASS | Version parity, wrong/corrupt artifacts, template paths, restart sync, no-build startup, failure pause/recovery |
| Offline updater transaction tests | 52 PASS | Spaces, configured paths, R12 reuse, current rerun, worker-backup restoration, fail-closed checks, injected failures, rollback, file preservation |
| Real Manager HTTP/process startup tests | 16 PASS | Both installed-R12 and cached-R12/old-template cases reach Node READY and provision an owned Java worker fixture |
| R16 retained assertions | 27 PASS | Manager regression |
| R17 retained assertions | 9 PASS | Forwarding regression |
| R18 retained assertions | 29 PASS | Environment/policy regression |
| R19 save barrier assertions | 9 PASS | Save barrier regression |
| R19 hydration checks | 20 PASS | Destination restoration regression |
| Support archive checks | 25 PASS | Support capture regression |
| Restart availability checks | 14 PASS | HF9 stop/restart policy regression |

The retained suites total 133 passing checks. These are the checks executed for
this repair, not recycled counts from an earlier revision.

## Limits

The updater engine and Manager were executed on Linux with Java 21. Windows BAT /
PowerShell launchers were authored and reviewed but were **not executed on Windows**.
The new JAR/deployment tests use explicitly synthetic metadata fixtures; the HTTP
tests launch the real Manager with Manager-owned Java fixture agents, not NeoForge
or a connected Minecraft client. No new in-game seamlessness, natural-growth,
world-recovery completeness, or Windows launcher success is claimed.

## Windows acceptance

Stop Manager, Desktop launcher, Gateway, and every worker. Extract the repair ZIP
anywhere and run `REPAIR_R19_HF10_NODEFIX1.bat`. Select the existing Cluster root.
Type `RESTORE` if offered the saved-worker restoration. Restart through the normal
Cluster launcher and confirm the startup package marker above, Node R12 / READY,
and newly generated worker logs showing `Foundations Cluster Node 0.0.6a.R12 loaded`.
Use only fresh startup lines; older appended R11 log entries are historical.

Do not delete world folders, runtime journals, profiles, security state or backups.
Retain the printed backup path and repair terminal output if a later check fails.
