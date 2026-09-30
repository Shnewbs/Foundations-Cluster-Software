# Foundations Cluster feature coverage — R16

All original design areas remain represented. “Implemented” is a source/build status; game-dependent acceptance is stated separately.

| Area | Stage | Behavior and remaining work |
|---|---|---|
| Single-machine orchestration | implemented | Manager, reusable worker IDs/ports, template installation, launch and supervision. Validate launchers on Windows with JDK 21. |
| Manual worker controls | implemented | Start, warm start, drain, cancel, maintenance, stop, restart, kill, retirement and deletion. Deletion keeps existing explicit safeguards; use test worlds. |
| Pinned capacity | implemented | Persistent worker pin prevents automatic scale-down while leaving manual controls available. Use worker drawer → Pin online. |
| Named worker profiles | partial | Default heap/JVM profile and memory reservation are configurable. Multiple named templates, affinity, priority and NUMA placement remain planned. |
| Manual / assisted / automatic scaling | implemented | Sustain windows, cooldowns, RAM budgets, consolidation and operator approval. Production scaling still depends on live migration acceptance. |
| Warm spares | implemented | Warm worker creation and configurable warm capacity targets. Predictive demand forecasting is planned. |
| Cluster performance history | implemented | Bounded 5-second, minute and hourly tiers; graph ranges through seven days. History accumulates in memory and resets on Manager restart. |
| Per-worker history | implemented | One hour of bounded MSPT, percentile, heap, CPU, players and chunk telemetry. Open a worker → Metrics. GC pause histograms and allocation rates need additional instrumentation. |
| Local host health | implemented | Host CPU, available RAM, swap, storage, Manager heap and runtime. Unavailable OS counters remain explicitly unavailable. |
| Extended platform counters | planned | Per-core CPU, network rate, disk latency, temperatures and file descriptors. Add platform adapters without blocking Minecraft ticks. |
| Alerts and acknowledgements | implemented | Live incidents, severity thresholds, persisted acknowledgements and resolved-incident clearing. External notification channels are not connected. |
| Unified console / logs / audit | implemented | Manager/worker consoles, search, pause, downloads, CSV export and audited admin mutations. Long-term structured log retention/export pipelines remain planned. |
| Diagnostic summaries | implemented | Download an allowlisted ZIP of component health, host measurements and transfer metadata. Raw logs, secrets, world files and player profiles are excluded. |
| Consistent offline backups | implemented | Stopped-cluster backup tool, SHA-256 manifest and archive verification. Stop the Manager before taking a backup; live snapshots are not implemented. |
| Transactional program updater | implemented | Portable folder selection, stopped-process checks, checksummed payload, backups and rollback. Requires Windows execution acceptance on the target machine. |
| WebUI mockup layout | implemented | Dark dashboard, six overview metrics, load graph, distribution, worker table, quick actions and events. Live ownership map replaces fictional terrain imagery. |
| Operator quality of life | implemented | Ctrl+K search, deep links, sort/export, dirty-form protection, responsive layout, focus trapping and stale-data indicators. Use Feature Status to inspect every roadmap area. |
| World ownership map | implemented | Coordinate-based region view, dimension filter, zoom, pan and cell inspection. A terrain tile renderer is not yet connected. |
| Population layer planner | acceptance | Layer cells, lease placement, party affinity and split/merge foundations exist. Validate large populations and game entity visibility. |
| Persistent bases across layers | partial | Blocks and BlockEntities use one-writer authority and journals. Persistent entities, machines, mod semantics and complete replica tick suppression require further work. |
| Region authority / epochs | acceptance | Leases, fencing, recovery, transfer and authority map are implemented. Prove no duplicate writers under real crash and load tests. |
| Region snapshot transfers | partial | Checksummed snapshot/install coordinator exists in the reference module. Integrate and acceptance-test full region files across live workers. |
| BlockEntity persistence | acceptance | R7 coalescing, throttled scans, host-block state and lifecycle flushes are preserved. Run dynamic TFC/machine/chest soak tests. |
| Data plane / replicas | acceptance | Chunk/block relay, fencing and deduplication exist. Test stale publisher behavior, saturation and replica consistency. |
| Player state / profile restore | acceptance | Capture, preload, final snapshot and profile preparation are implemented. Test inventory, advancement and mod attachment preservation. |
| Physical backend migration | acceptance | Explicit transfers require destination readiness and real backend movement. Run both directions with a connected client; confirm anchor and simulation. |
| Migration headroom | implemented | Reject stale, full, unhealthy, high-MSPT and over-heap destinations. Reserve host memory in Scaling and tune placement.maxHeapPercent. |
| Seamless NeoForge switching | partial | R15 experimental bridge retained; normal physical reconfiguration is the default. Unanswered destination configuration tasks remain unproven; capture new traces before changing negotiation. |
| Transfer evidence timeline | implemented | Correlated gateway switch stages, elapsed time, commit/abort diagnostics and exports. Packet-level bridge logs remain gateway-side; client seamlessness requires observation. |
| Graceful evacuation / rolling restart | acceptance | Migration-aware stop waits for players and authority before exit. Validate successful migration, timeout abort and last-worker handling. |
| Emergency crash recovery | acceptance | Backend failure checks, quarantine, profile preparation and redirects. Validate hard crash behavior and repeat failure limits in live cluster. |
| Intentional shutdown | implemented | Global gate suppresses failover, automatic planning and new topology mutations before workers stop. Run a joined-client shutdown test to verify the proxy path. |
| Java toolchain isolation | implemented | JDK 21 discovery for builds; isolated Java 25 gateway runtime retained. Clean-machine Windows provisioning still requires acceptance. |
| Administrator security | implemented | Argon2id login, encrypted vault, authenticated APIs, same-origin mutation checks and audit. Default binding remains loopback; remote TLS belongs at the reverse proxy. |
| Granular administrative roles | planned | Current runtime has one administrator role. Add Viewer, Operator, Administrator, Forensics and Owner enforcement server-side. |
| Economy / Framework boundary | partial | Economy remains separate from worker orchestration. No Economy health or ledger provider is included in this source package. |
| Native desktop client | partial | New standard-library Tkinter client supplies authenticated worker controls, live summary and logs without PySide dependencies. Desktop window behavior needs Windows acceptance; full Test Lab and topology administration use the WebUI. |
| Remote worker hosts | planned | Existing orchestration currently owns local processes. Build authenticated remote node agent, resource inventory and host drain. |
| Shared SQL / messaging providers | planned | Local authoritative state stores are preserved. Add transactional metadata provider, coordination and storage abstraction. |
| Cross-host world transfer | planned | Reference snapshot structures establish the transfer model. Add authenticated install endpoints, fencing and interrupted-transfer recovery. |
| High availability | planned | Manager and gateway are currently single instances. Add durable leadership, redundant gateways and replicated control-plane state. |
| Seamless cell boundaries | partial | Logical simulation movement exists separately from explicit physical migration. Validate physical handover, visibility, persistent entities and no lost writes. |
| Test Lab | acceptance | Safe and live suites, handoff, inventory, chest and lifecycle checks are preserved. Local tests cannot certify joined-client protocol or real Minecraft rendering. |
