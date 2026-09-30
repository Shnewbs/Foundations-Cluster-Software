# R19-HF2 Node upgrade correction — 2026-09-27

## Confirmed cause

The supplied startup log explicitly installs Node 0.0.6a.R8 despite Manager R19 and a packaged Node R9. `ClusterVersion.NODE_VERSION` still contained R8. When an old R8 build remained in the installed build cache, startup selected it, replaced the template's R9, and synchronized the old version back into stored workers. The updater itself copied R9 correctly, but the next Manager startup undid it.

## Changes

- Correct the Manager's expected Node to R9 and rebuild its Java 21-compatible JAR.
- Select the exact expected Node when synchronizing stored workers; ignore obsolete Node JARs even if both versions appear in the template. Use the same synchronization on fresh worker creation.
- Refuse a stored-worker start when its required Node is absent from the template, without deleting the worker's current Node first.
- Reject Durable Save Health on R8 or missing counters instead of treating absent legacy telemetry as proof of confirmed saves.
- Print `package 0.0.6a.R19-HF2` in the Manager startup line so the installed fix is recognizable. Runtime API versions remain Manager R19, Node R9 and Gateway R19.
- Retain the HF1 registry-packet fix unchanged, along with all R19 terrain/save and environment work.

## Validation

The new test fails against the HF1 Manager with `Manager expects 0.0.6a.R8 but packaged Node is 0.0.6a.R9`. The corrected Manager passes 15 checks using the actual bundled Node R9 JAR: compare build and JAR metadata, select the correct cached build, preserve an updater-installed R9, repair an R8 template, synchronize an old stored worker, tolerate either stale-template directory order, preserve unrelated mods/world data, and retain the working Node on a missing-template failure.

All 74 previous Manager assertions still pass (27 + 9 + 29 + 9), for 89 total including upgrade checks. WebUI acceptance fixtures pass 24 cases, and button fixtures pass 16. JavaScript syntax passes. The Manager is compiled with JDK 25 using `--release 21`; the Node and Gateway JARs and Conduit overlay are byte-identical to HF1. Package integrity and payload hashes are verified. The unchanged updater transaction retains its prior R19 fixture evidence; no real Windows or joined-client transfer was run for HF2.

## Install and verify

Stop the Manager, gateway and all workers. Extract the HF2 updater and run `APPLY_R19_UPDATE.bat`, selecting the existing Cluster folder. Restart and hard-refresh the WebUI. Confirm the startup line includes `package 0.0.6a.R19-HF2`; every READY worker must report Node `0.0.6a.R9`. Run Safe Suite before chest or migration tests. Do not simply rename an R8 JAR: that cannot add the confirmed-save implementation.

## Separate gateway failure remains unresolved

The supplied trace records destination switch rejection after 6189, 6023 and 6022 ms, with only `velocity.error.internal-server-connection-error`. It does not include the last NeoForge configuration task, a gateway exception, or destination disconnect details. This suggests a timed-out stage but does not prove its cause. HF2 corrects the Node deployment blocker; it does not claim to fix this separate seamless negotiation failure.

If a fresh transfer still fails after both workers report R9, collect `Cluster/logs/velocity.log` and `Cluster/workers/worker-002/logs/latest.log` for that attempt, including `FoundationsConfigTrace`, plus the exported transfer trace. Keep graceful-stop abort behavior: the recorded runs retained the player on the source instead of forcing a disconnect.
