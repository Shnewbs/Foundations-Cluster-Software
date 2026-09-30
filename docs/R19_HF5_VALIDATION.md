# R19-HF5 — compare tag maps by identity

Date: September 28, 2026 UTC. Baseline: R19-HF4.
Manager R19 / Node R9 / Gateway R19 / Conduit 1.8.0; package marker HF5.

## Live evidence from HF4

The supplied run starts with `package 0.0.6a.R19-HF4` and experimental seamless enabled. The destination answers the NeoForge CONFIG ping, verifies the frozen-registry manifest, verifies 30 frozen-registry snapshots and their completion, and passes known packs. At 01:38:05 UTC, it rejects `vanilla:TagsUpdatePacket` at entry 0 after 149 ms in configuration. Both payload lengths are 38,207 bytes; their abbreviated SHA-256 values are 51f69d755cc7 and 557b2840465d. Manager aborts the handoff. The player has not successfully completed an experimental seamless transfer in this evidence.

This establishes that HF3's pong and HF4's frozen-registry comparison are working in this live attempt. Equal tag lengths do not establish equal contents. The logs do not include the paired tag payloads, so the exact user mismatch is not yet classified as map ordering, member ordering or changed membership.

## Reproduction and protocol evidence

The pinned Conduit 1.8.0 `TagsUpdatePacket` codec stores a map of registry names to maps of tag names to integer arrays. Its decoder constructs both maps from names; its encoder iterates their entries and writes member arrays in their existing sequence. This was inspected directly from the same official Conduit JAR used to compile and test the shipped overlay, using `javap -p -c`.

A regression fixture uses actual Conduit tag packet objects, encodes identical nested mappings in reversed map order, and feeds them to the unchanged HF4 bridge. HF4 rejects them with the same TagsUpdatePacket error despite identical registry/tag/member content. The fixture is smaller than the user's payload; it reproduces a comparator defect, not the complete live failure.

## Changes and guards

- Canonicalize registry-name and tag-name map order for comparison only. Preserve empty registries and empty tags.
- Preserve every numeric member ID, duplicate member and member sequence exactly. Reordering members still rejects: this release does not assume all tag consumers ignore list order.
- Keep forwarded packets and their arrays untouched. Comparison operates on private captured bytes. A failed initial capture disables replay without interrupting ordinary packet forwarding.
- Reject duplicate registry/tag keys, invalid names, negative member IDs, truncated/trailing data and invalid counts. Bound each payload to the existing 16 MiB limit; bound registries to 8,192, total tags to 131,072 and total members to 1,048,576. Check declared counts against available bytes before allocation. Exceeding these limits disables seamless replay; it does not rewrite data to fit.
- Report missing/added registries or tags, or the first differing tag with member counts, member index and expected/destination numeric IDs. A `sameMembersDifferentOrder=true` diagnostic identifies sequence-only differences but does not bypass them. Names and lists in diagnostics are bounded; full payloads are not logged.
- Retain packet multiplicity and final transcript completeness guards. Frozen-registry IDs/aliases, config file bytes and other replay checks remain strict.
- Ship the new helper class and require it during gateway bootstrap so an incomplete overlay fails explicitly. The bootstrap's existing wildcard patch and hash verification includes it.
- Retain HF1 non-consuming registry observation, HF2 Node R9 selection, HF3 pong and prompt WebUI errors, and HF4 frozen-registry checks.

## Validation actually performed

- The new real-Conduit reordered-map fixture fails against unchanged HF4, then passes against HF5.
- 222 tag checks pass, including reversed/shuffled maps, empty maps/tags, ID/content/sequence/multiplicity changes, omissions, duplicate packets/keys, each truncated fixture prefix, malformed counts/names/IDs, and non-mutating/fail-safe capture.
- All 129 retained handshake checks pass. All 351 handshake checks also pass with a patched real Conduit JAR first on the classpath, loading the shipped overlay classes.
- WebUI checks: 9 handoff, 24 acceptance and 16 button cases pass, plus JavaScript syntax.
- Manager rebuilt for Java 21; all 89 Manager/upgrade assertions pass.
- Both ZIP archives and every source/updater payload checksum are verified. Node R9, Gateway plugin R19, WebUI, the seamless session handler, and the transactional updater script remain byte-identical to HF4.

No joined Minecraft client, Windows runtime or full seamless transfer was run here. PowerShell is unavailable in this environment: the one-name bootstrap requirement change was inspected and its packaged helper verified, but Windows execution was not tested. The updater transaction itself is unchanged. Automated results are in `docs/validation/r19-hf5-results.json`.

## Install and live acceptance

1. Disconnect Minecraft and stop Manager, gateway and all workers.
2. Extract the HF5 updater, run `APPLY_R19_UPDATE.bat`, select the existing Cluster folder and retain its backup path.
3. Restart; confirm `package 0.0.6a.R19-HF5`. Component versions remain Manager R19 / Node R9 / Gateway R19.
4. Confirm experimental seamless was enabled at proxy launch. Reconnect the player to capture a fresh login.
5. With two READY workers and healthy saves, run Test Physical Migration once. Success requires destination ownership/physical presence and the player's observation that no reconfiguration/loading screen appeared. Then perform the inventory round trip.

If only tag map ordering differed, this stage should pass. A remaining content/sequence difference should now name the registry and tag. Collect that precise rejection with the gateway and destination worker logs. Do not disable compatibility checks or alter registry IDs to force a pass.

## Remaining work

Seamless live acceptance is still open. Earlier wrong-region block-event writes, Windows advancement file sharing violations, and controlled investigation of grass/leaves appearing in inventory round trips remain open. Full entity persistence, replica tick fencing, mod-specific state, shared calendar/sleep policies, remote hosts and the rest of the roadmap are unchanged. This package does not claim to complete them or certify crash recovery from this transfer trace.
