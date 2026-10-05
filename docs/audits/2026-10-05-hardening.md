# Foundations Cluster hardening audit — 2026-10-05

## Scope and provenance

Starting GitHub `main`: `734edb8fa5984d3a31d9cdeb00967854d0db38a5`.
Materialized repository files were checked against Git object hashes. The supplied
HF11 handoff archive remains the source reference; frozen v2.2 design/addendum and
handoff roadmap are unchanged. Earlier claims of a working GitHub release pipeline
were not implementation evidence: the starting repository had no Actions workflow.

The tracked active configuration was byte-identical to its default template and
was removed from version control; startup copies the retained default template.
Runtime/config/build ignores prevent installation data from entering new commits.

This pass inspected and changed security, parsing, persistence/replay, authority
commit validation, replication queueing, build/test entry points and update/release
transactions. It also reviewed startup/version selection and the existing regression
suites. This is not an exhaustive proof over every gameplay path or a production
certification. Manager HF12 is a control-plane patch; Node R13 and gateway code are
unchanged. No provided live installation was modified.

## Defects addressed

| Area | Failure | Change |
| --- | --- | --- |
| Administrator setup | Blank/malformed credential file reopened setup | Refuse startup, including invalid Argon2 parameters |
| Authentication | Remote key included ephemeral TCP port | Bound failed attempts by source IP; bounded request/password size and attempt retention |
| Sessions | Stale remembered-session file survived a password change | Bind each session to the credential generation; commit credentials before publishing them |
| Security storage | Missing key could generate a replacement; write failures changed memory | Fail closed on missing/invalid key; stage and force atomic writes; restore in-memory state on failure |
| JSON | Escape replacement corrupted literal backslashes and accepted malformed input | Bounded structured parser with duplicate-key, syntax and depth validation |
| Block/BlockEntity journals | Partial write could be acknowledged; failed append reused revision | Complete every write and poison journal after uncertain I/O |
| Journal compaction | WAL truncated without durable replacement ordering | Force staged snapshot, atomic rename, directory force, then forced WAL truncation; retain WAL when directory force is unavailable |
| Recovery | Corrupt/truncated rows silently skipped | Stream journals, validate records, refuse startup with file/line; validate authority metadata instead of silently resetting it |
| Authority mutation | Ownership could change between validation and WAL append | Serialize durable commit validation and append with authority transitions; reject expired leases |
| Replicas | Sliding throttle could suppress invalidations indefinitely | Coalesce pending notices only; release coalescing key on dequeue/eviction/expiry |
| Relay restart | Counter-only IDs repeated after restart; old acknowledgement could remove new frame | Add a random process identity to every frame ID |
| Relay | Concurrent enqueue could exceed queue bound; inflight unbounded by count | Serialize per-worker queue operations and cap in-flight frames |
| v2.2 model | Valid checksum did not enforce replay transitions | Check sequence, initial stage, reservations and immutable result transitions |
| Builds/tests | New suites missing from older runners; Windows paths previously escaped | One portable entry point, complete model/manager/helper/UI suites, Windows/Linux CI in a path with spaces |
| Releases | Stale HF9 updater targeted Node R11; no CI release gate | Retire historical installer, package only HF12 control-plane allowlist, verified backups/rollback, startup interruption marker, immutable versioned pre-releases |

## Validation

Local compilation and execution used Temurin Java 21.0.8 and Eclipse ECJ 3.41.0
(the available Java runtime did not include javac). The compiler artifact's hash
was checked against its Maven publisher metadata. CI uses full Temurin JDK 21
with javac on both Ubuntu and Windows; Windows also executes the actual PowerShell
Manager, simulator and reference build scripts.

`python tools/validate.py` includes ten manager suites, three reference suites,
two dependency-free Node helpers, simulator compilation, six UI handler suites,
and four real local Manager HTTP suites. Node deployment tests explicitly use
metadata fixtures; they do not compile or simulate Minecraft. The v2.2 model suite
now has 102 checks, including checksum-valid but semantically invalid replay.
Security/recovery/queue hardening adds 61 direct checks and 13 HTTP checks.

`tests/updater_test.ps1` exercises install, rollback, tampered hashes, forbidden and
traversal paths, lock refusal, interrupted-update handling and failed replacement
recovery. All fixtures are disposable and include spaces in their paths.

The matching GitHub Actions run is the authoritative platform result. A release
asset is published only after both jobs succeed; packaging locally is not evidence
of Windows validation. No full NeoForge/Conduit build, game-client seamless test,
long load test or real host power-loss test was performed in this local pass.

## Remaining production blockers

1. **Storage fencing and epoch durability.** Region authority still describes its
   fencing as control-plane-only. The authority table still batches persistence
   on a timer; new epochs/ownership must become durable before they are published,
   with explicit failure fencing and restart reconciliation. All actual world-file
   writes need the same authority rule. The narrower locked journal validation in
   HF12 does not solve distributed split-brain safety.
2. **Coherent world/player transactions.** Block, BlockEntity, entity and player
   inventory/profile checkpoints are not one atomic commit. The v2.2 reservation,
   durability receipt and frame-application model still needs runtime adapters and
   crash/duplicate-message tests before claiming no loss or duplication.
3. **Live acceptance.** Re-run the provided two-worker persistence/environment
   tests against provisioned NeoForge, then actual client handoffs, inventory and
   container round trips, natural growth/fluids, transfers, owner hard kills and
   manager restarts. Historical HF11 evidence is not a new HF12 acceptance run.
4. **Host-crash durability.** File force/rename ordering cannot demonstrate behavior
   of every Windows filesystem, controller and power-loss scenario. Windows often
   cannot force directory metadata through Java; WAL retention favors recovery but
   grows disk usage. Test crash boundaries and implement a verified compaction
   strategy before sustained production writes.
5. **Capacity and backpressure.** Frame count limits do not bound all memory by
   bytes; payloads can be large, journals retain current state in memory, and queue
   eviction is lossy. Measure bounded bytes, overload behavior and tail latency at
   realistic worker/player scale before promising capacity or availability.
6. **Service identity and operations.** Shared worker/gateway secrets are not
   per-worker identity or mutually authenticated transport. Define deployment trust
   boundaries, TLS/rotation, recovery ownership, monitoring, offline backups and
   tested restore targets. External penetration review and failure drills remain
   separate engineering work.

## Compatibility and recovery

HF12 accepts the HF11 journal formats, including legacy BlockEntity rows. It is
stricter about corruption: restore/reconcile a reported damaged file; do not remove
it just to start with empty state. Old remembered logins are intentionally discarded
once because they have no credential binding. Credentials, vault keys and active
configuration must travel together in normal backups.

The updater accepts an HF11/HF12 Manager baseline and only replaces Manager JAR,
its existing pinned Argon2 dependency and the guarded launcher. It does not install
Node/gateway or migrate an older baseline. Source rebuilds should use the matching
Git tag; rebuilding stale local source can replace the newer installed JAR.
Update checksums detect corruption; obtain the complete package from the trusted
repository's Releases page. Interrupted updates retain a backup record and block
the updated launcher/Manager until rollback is completed.
