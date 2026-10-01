# GitHub source verification - 2026-09-30

Repository: Shnewbs/Foundations-Cluster-Software, main commit
`f7968b1814b32fa0384666166e8022cc0bf2309c`.

Reference: supplied `FoundationsCluster-R19-HF11-WORLD-REPLICATION-V2.2-AGENT-HANDOFF-2026-09-29.zip`,
expanded `source/Cluster/`. The supplied archive and synced sources were not modified.

Method: compare each GitHub blob SHA with `git hash-object --no-filters` of the
corresponding supplied file. All 288 GitHub tracked files exist in the reference.
286 are byte-identical. The R14 release notes and validation file have only final
newline formatting differences; text comparison shows no substantive changes.
No implementation source differs and no GitHub-only source file was found.

19 additional supplied files are packaged outputs/dependencies: Manager/Node/
Gateway/Velocity JARs, Bouncy Castle, compiled Conduit bridge classes, and six
validation screenshots. Their absence from GitHub is not missing implementation
code. A GitHub source checkout is not the ready-to-install HF11 updater.

Verified baseline: package HF11, Manager R19, Node R13, Gateway R19.
The frozen v2.2 candidate design, accepted review addendum, and September 29
handoff roadmap were absent from GitHub. This change imports those documents
verbatim and links them from the older roadmap and README.

Historical release notes and runtime version markers are retained. The new
protocol prototype is isolated under reference/, with no production enablement.
