# Foundations Cluster — 0.0.7a-dev.2.R2 / Node R16

Installable development revision for Minecraft 1.21.1 / NeoForge 21.1.250.

**Opt-in paired entity views:** explicitly pair a vanilla cow or dropped item on
its source worker, then view its UUID, source coordinates, facing, movement and
synchronized presentation data from another compatible worker. Remote copies are
packets only: no destination AI, world entity, loot or pickup is created.

**Not completed 7a.** Remote Survival breaking/placement, entity ownership transfer,
remote pickups, generic modded entity support and crash-safe entity promotion are
still gated. Keep the real source worker online and the region owned by it.
The existing transition pause has not been eliminated by this revision.

Download the versioned UPDATER release, stop every cluster process and run
`APPLY_UPDATE.bat` against a backup/test copy. Confirm package **dev.2.R2** and
**Node R16 on every worker**. Existing HF16 shutdown/restart/worker-row controls
remain included. No extra mod ID or new client mod is added.

[Entity test and rollback guide](docs/ENTITY_VIEWS_REV2_GUIDE.md)
contains exact commands and restrictions. Read it before testing.
[Change scope and validation gates](docs/status/0_0_7A_DEV2_REV2.md)
separates portable/HTTP/NeoForge tests from joined-client acceptance.

The TEST-TOOLS release includes exact tracked source, checksums and executed test
logs. Ordinary builds use `minecraft-agent/build_mod.bat` or Gradle. Run
`python tools/validate.py` with Java 21, Python and Node, then
`python tools/validate_survival_spike.py` for the disposable NeoForge GameTests.

The legacy offline mapped-build helper is not the normal supported build path.
Frozen architecture files are unchanged. Source on main remains HF16 while this
work stays in draft PR3; older releases are never silently replaced.
