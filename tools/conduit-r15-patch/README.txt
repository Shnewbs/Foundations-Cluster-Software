R19-HF6 (2026-09-28 UTC): normalize NeoForge data-map declaration order,
enum class-entry order and feature-flag sets using the pinned 21.1.250 codecs.
Retain mandatory flags and all ordered enum member data. Ship the new
FoundationsNeoForgeConfiguration.class helper with the complete overlay.
See docs/R19_HF6_VALIDATION.md. Live client acceptance remains required.

R19-HF5 (2026-09-28 UTC): compare tag registry/name maps independent of map
iteration order. Keep all member IDs and their sequence exact. Real differences
name registries, tags and the first differing member. Ship the new
FoundationsTagConfiguration.class helper with the full overlay.
See docs/R19_HF5_VALIDATION.md. Live client acceptance remains required.

R19-HF4 (2026-09-28 UTC): compare the frozen-registry manifest as a set and
match snapshots by registry name. Snapshot bytes (IDs and aliases included)
remain exact. Verify all snapshots before completion acknowledgement.
See docs/R19_HF4_VALIDATION.md. Live client acceptance remains required.

R19-HF3 (2026-09-28 UTC): answer backend CONFIG ping with the matching pong.
NeoForge 21.1.250 waits for pong(0) before running the remaining configuration.
The actual current challenge is echoed, without bypassing transcript checks.
See docs/R19_HF3_VALIDATION.md. Live client acceptance remains required.

R19-HF1 (2026-09-27): registry capture is non-consuming. The original R19
observer drained RegistrySyncPacket before normal client encoding. All overlay
classes must be updated together. See docs/R19_HF1_VALIDATION.md.

Foundations R19 guarded NeoForge replay overlay for Conduit 1.8.0

The historical directory name is retained for updater compatibility. Install
all bundled class files together; FoundationsHandshakeCapture and its nested
classes are required by SeamlessConfigSessionHandler and the Gateway R19 plugin.

A per-client Netty observer captures successful initial CONFIGURATION requests
and replies. The seamless destination bridge validates equivalent configuration
before replaying that same client's replies. Unknown tasks, changed registries,
changed mod configuration, missing captures and size limits reject explicitly.
No client mod list is fabricated. Completed transcripts stay in memory only,
are bounded at 16 MiB / 8192 packets, and follow the connection lifetime.

This is experimental. Compilation and transcript fixtures do not prove real
NeoForge client seamlessness. Reconnect after a full cluster restart to capture
a fresh login. The normal physical reconfiguration policy remains available.

The setup script validates all overlay hashes on a staging core before replace.
The R19 updater also applies all overlay classes and backs up the previous core.

Conduit project: https://github.com/tame-gg/conduit
Source and compiled overlay are distributed under GPL-3.0-or-later consistently
with Conduit. The upstream license is included in GPL-3.0.txt.
