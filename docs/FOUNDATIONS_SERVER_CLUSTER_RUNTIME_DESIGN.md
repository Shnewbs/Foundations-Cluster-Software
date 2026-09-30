# Foundations Server Cluster Runtime Design

**Project:** Foundations Framework / Foundations Economy  
**Target:** Minecraft 1.21.1 NeoForge  
**Design status:** Architecture specification for post-0.0.7a groundwork, beginning with 0.0.8a-compatible abstractions  
**Primary goal:** Make a normal Foundations server easy to run while preserving a path to seamless multi-JVM, multi-machine scaling.

---

## 1. Executive Summary

Foundations should ship with a **server runtime directory** and a single Windows launcher:

```text
start_foundations_server.bat
```

The server owner should not need to manually launch every Minecraft JVM, proxy, monitor, or support service.

The batch file launches a dedicated:

```text
FoundationsClusterManager.jar
```

The Cluster Manager becomes the supervisor for the server runtime. It can:

- start and stop Minecraft worker JVMs;
- assign worker IDs;
- monitor CPU, memory, TPS/MSPT, GC and networking;
- start additional workers when load requires them;
- allow an administrator to manually start workers at any time;
- drain and stop workers safely;
- maintain a live player/region/layer directory;
- coordinate future region ownership and population layering;
- expose a local/admin-only web dashboard;
- maintain unified runtime and audit logs;
- recover crashed workers;
- preserve human oversight even when automatic scaling is enabled.

The **Foundations Economy mod must remain independent** of the Cluster Manager. Economy uses Framework APIs and storage abstractions. A small server can continue to run normally with one JVM and no distributed infrastructure.

---

# 2. Core Design Rule

The most important rule is:

> **Phases/layers may split population, but they must never decide whether persistent player-built world data exists.**

Persistent world state should have exactly one authoritative owner at a time.

Examples of persistent state:

- placed/broken blocks;
- player bases;
- chests and inventories;
- block entities;
- TFC soil and crop data;
- machines;
- claims;
- persistent structures;
- persistent entity ownership;
- economy balances;
- ledgers;
- markets;
- player progression.

Examples of state that may later be layered:

- visible player population;
- wild mobs;
- temporary combat encounters;
- projectiles;
- particles;
- temporary dropped entities;
- NPC crowds;
- other explicitly layer-safe runtime entities.

---

# 3. Runtime Architecture

```text
                         PLAYERS
                            |
                            v
                 Proxy / Foundations Gateway
                            |
                            v
                Foundations Cluster Manager
                  /         |          \
                 /          |           \
                v           v            v
          Worker JVM 1  Worker JVM 2  Worker JVM 3
               |            |             |
               +------------+-------------+
                            |
                     Foundations APIs
                            |
             +--------------+--------------+
             |              |              |
             v              v              v
          Storage       Messaging       Telemetry
          Provider       Provider        Provider
             |
      +------+------+
      |             |
   Economy       World State
```

For initial deployments, all components may run on one physical server.

Later deployments may spread the same components across multiple machines without changing the logical server model.

---

# 4. Foundations Components

## 4.1 Foundations Framework Mod

Runs inside every Minecraft worker JVM.

Responsibilities:

- Worker identity.
- Server identity.
- World identity.
- Region identity.
- Layer identity.
- Cluster registration.
- Heartbeats.
- Player session hooks.
- Region ownership API.
- Layer visibility API.
- Storage provider API.
- Messaging provider API.
- Telemetry API.
- Economy service discovery.
- Graceful drain hooks.
- Shutdown hooks.
- Future seamless handoff hooks.

The Framework must not assume that the process it is running inside is the only Minecraft server.

---

## 4.2 Foundations Economy Mod

Economy remains a module using Framework services.

It should not:

- launch JVMs;
- own the proxy;
- directly manage process lifecycles;
- contain cluster orchestration logic.

It should use an abstraction such as:

```java
interface EconomyStorageProvider
```

Possible implementations:

```text
Local/SQLite provider
PostgreSQL provider
Test/In-memory provider
```

This lets a normal server use local storage while a network deployment uses a shared authoritative backend.

---

## 4.3 Foundations Cluster Manager

A separate Java application:

```text
FoundationsClusterManager.jar
```

This is the primary orchestration service.

Responsibilities:

- Worker process lifecycle.
- Automatic worker launch.
- Manual worker launch.
- Worker drain.
- Worker shutdown.
- Crash detection.
- Restart policy.
- JVM argument templates.
- Port assignment.
- Worker directory generation.
- Health checks.
- Resource monitoring.
- Region assignment.
- Layer assignment.
- Capacity planning.
- Service registration.
- Configuration loading.
- Web UI backend.
- Central logging.
- Admin action auditing.

Java/Kotlin is preferred for the production Cluster Manager rather than Python because:

- it shares the Java ecosystem with NeoForge;
- it can share DTO/API modules with Foundations Framework;
- it has excellent process and networking support;
- it avoids requiring a second production runtime;
- it simplifies deployment and packaging.

Python remains useful for development tools and optional desktop interfaces.

---

# 5. Three Operating Modes

The same package should support three modes.

## 5.1 Standalone Mode

```text
1 machine
1 Minecraft JVM
Local storage
No proxy required
```

Designed for ordinary private/small servers.

The Cluster Manager may still supervise the JVM and provide the dashboard, but no distributed features are required.

---

## 5.2 Local Cluster Mode

```text
1 physical machine
Multiple Minecraft JVM workers
Shared services
Proxy/gateway
```

Useful when one physical server has enough CPU/RAM to run several workers.

Example:

```text
128 GB RAM server

Worker 01  16-24 GB
Worker 02  16-24 GB
Worker 03  16-24 GB
Worker 04  16-24 GB
Cluster Manager
Proxy
Database/cache
OS/filesystem cache
```

---

## 5.3 Distributed Cluster Mode

```text
Multiple physical machines
Multiple worker JVMs
Gateway fleet
Shared persistent services
```

A worker is identified by cluster metadata, not by physical host.

Foundations should therefore treat:

```text
NodeId
WorkerId
ServerId
WorldId
RegionId
LayerId
SessionId
```

as separate concepts.

---

# 6. Server Package Layout

The intended distributed server package should resemble:

```text
FoundationsServer/
|
|-- start_foundations_server.bat
|-- stop_foundations_server.bat
|-- README_SERVER.md
|
|-- cluster/
|   |-- FoundationsClusterManager.jar
|   |-- cluster.toml
|   |-- jvm-profiles.toml
|   `-- permissions.toml
|
|-- framework/
|   `-- FoundationsFramework.jar
|
|-- modules/
|   |-- FoundationsEconomy.jar
|   `-- optional/
|
|-- proxy/
|   |-- velocity.jar
|   `-- config/
|
|-- server-template/
|   |-- mods/
|   |-- config/
|   |-- defaultconfigs/
|   |-- kubejs/
|   |-- libraries/
|   `-- server.properties.template
|
|-- workers/
|   |-- worker-001/
|   |-- worker-002/
|   `-- ...
|
|-- data/
|   |-- cluster/
|   |-- snapshots/
|   |-- backups/
|   `-- cache/
|
|-- logs/
|   |-- cluster/
|   |-- workers/
|   |-- audit/
|   |-- events/
|   `-- crash/
|
|-- web/
|   |-- dashboard/
|   `-- assets/
|
`-- tools/
    |-- diagnostics/
    |-- backup/
    `-- optional-python-gui/
```

A worker directory is generated from `server-template/` rather than manually duplicated.

---

# 7. Single BAT Startup Experience

The primary Windows entry point should be:

```bat
start_foundations_server.bat
```

Its job should remain intentionally small.

Example design:

```bat
@echo off
setlocal
cd /d "%~dp0"

if not exist "cluster\FoundationClusterManager.jar" (
    echo [FOUNDATIONS] Cluster Manager not found.
    pause
    exit /b 1
)

java -jar "cluster\FoundationClusterManager.jar" --config "cluster\cluster.toml"

endlocal
```

The BAT file is **not the cluster intelligence**.

It simply launches the Cluster Manager.

This keeps process management testable and portable while still giving Windows server owners the expected double-click startup experience.

Equivalent shell scripts may later ship for Linux.

---

# 8. Startup Sequence

```text
start_foundations_server.bat
        |
        v
Foundations Cluster Manager
        |
        +--> validate config
        +--> validate Java/runtime
        +--> initialize logs
        +--> initialize admin API
        +--> initialize dashboard
        +--> initialize storage/messaging
        +--> start proxy/gateway if configured
        +--> determine desired worker count
        +--> start worker JVM(s)
        |
        v
Workers register READY
        |
        v
Cluster opens for players
```

A server should not be marked healthy until the required components have registered READY.

---

# 9. JVM Worker Lifecycle

Each Minecraft server process is a **Worker JVM**.

Example:

```text
worker-001
worker-002
worker-003
```

A worker progresses through:

```text
OFFLINE
  |
STARTING
  |
BOOTSTRAPPING
  |
READY
  |
ACTIVE
  |
DRAINING
  |
STOPPING
  |
OFFLINE
```

Failure states:

```text
UNHEALTHY
CRASHED
QUARANTINED
```

---

# 10. Manual Spin-Up and Human Oversight

Automatic scaling must never eliminate administrator control.

The Cluster Manager must support three scaling policies:

```text
MANUAL
ASSISTED
AUTOMATIC
```

## MANUAL

Foundations never starts an additional worker automatically.

The dashboard may recommend:

```text
Recommended action:
Start 1 additional worker.
Reason:
worker-001 MSPT = 42 ms
CPU = 91%
Active chunks = 8,240
```

An administrator presses:

```text
START WORKER
```

---

## ASSISTED

Foundations calculates scaling actions but requires administrator approval before executing them.

Example:

```text
Scaling recommendation

+1 Worker
Suggested profile: heavy
Estimated allocation: 20 GB
Reason: sustained worker load

[Approve]
[Reject]
[Snooze]
```

This should be the recommended early production mode.

---

## AUTOMATIC

The manager may start/stop workers according to configured policies.

Human controls remain available at all times.

Administrators can:

- manually start extra capacity;
- stop automatic scale-down;
- pin a worker online;
- mark a worker maintenance-only;
- drain a worker;
- quarantine a worker;
- override a region assignment;
- disable automatic scaling globally.

---

# 11. Manual Worker Controls

Both CLI and Web UI should expose:

```text
Start Worker
Start Worker From Profile
Start N Workers
Restart Worker
Drain Worker
Cancel Drain
Stop Worker
Force Stop Worker
Pin Worker Online
Unpin Worker
Quarantine Worker
Return Worker to Pool
```

Example CLI:

```text
foundation worker start
foundation worker start --profile heavy
foundation worker start --count 3
foundation worker drain worker-003
foundation worker stop worker-003
foundation worker pin worker-004
```

Every administrator action must be written to the audit log.

---

# 12. Worker Profiles

Do not hard-code one set of JVM flags.

Use named profiles.

Example:

```toml
[profiles.standard]
xms = "8G"
xmx = "16G"
max_workers_per_node = 4

[profiles.heavy]
xms = "12G"
xmx = "24G"
max_workers_per_node = 3

[profiles.memory_heavy]
xms = "16G"
xmx = "32G"
max_workers_per_node = 2
```

Additional settings may include:

- JVM executable.
- Java arguments.
- GC options.
- CPU affinity.
- NUMA policy.
- worker port range.
- process priority.
- mod/config profile.
- maximum active chunks.
- maximum player target.

---

# 13. Performance Philosophy

The design should prefer:

```text
multiple reasonably sized JVMs
```

over:

```text
one enormous JVM
```

when the workload can be partitioned.

Reasons:

- smaller GC domains;
- easier crash recovery;
- better CPU utilization;
- easier horizontal scaling;
- easier load isolation;
- less risk from one broken mod/region;
- more predictable memory use.

The system should profile actual load instead of assuming that player count alone determines capacity.

---

# 14. Load Metrics

Each worker should report at least:

## Minecraft

- TPS.
- MSPT average.
- MSPT p50/p95/p99.
- Tick duration.
- Loaded chunks.
- Active/ticking chunks.
- Player count.
- Entity count.
- Ticking entity count.
- Block entity count.
- Scheduled tick count.
- Chunk generation load.
- Chunk save queue.
- Packet rate.
- Packet bytes/sec.
- Login rate.

## JVM

- Process uptime.
- Heap used.
- Heap committed.
- Heap maximum.
- Non-heap memory.
- Metaspace.
- Direct/native buffers.
- Thread count.
- GC collection count.
- GC time.
- GC pause p50/p95/p99.
- Allocation rate.
- CPU utilization.
- Process CPU utilization.
- File descriptor count where supported.

## Host Node

- Total CPU.
- Per-core CPU.
- RAM.
- Swap/pagefile.
- Disk utilization.
- Disk latency.
- Disk read/write throughput.
- Network throughput.
- Network errors.
- Free storage.
- System load.
- Temperature if exposed by OS/hardware.

---

# 15. Foundations Admin Web UI

The preferred production interface is a **local/admin web UI** served by the Cluster Manager.

Example:

```text
http://127.0.0.1:8765/
```

Remote access should be disabled by default.

For remote administration, require explicit configuration plus authentication/TLS or placement behind an administrator-controlled reverse proxy/VPN.

---

# 16. Dashboard Overview

Example top-level dashboard:

```text
FOUNDATIONS CLUSTER

Cluster Status: HEALTHY
Players:        842
Workers:        9 / 12
Layers:         17
Regions:        524
Cluster MSPT:   21.4
Alerts:         1

-----------------------------------------------------
Worker      Players   MSPT   CPU    Heap       Status
-----------------------------------------------------
worker-01      96     18.3   61%   10/20 GB    ACTIVE
worker-02     103     26.7   78%   14/20 GB    ACTIVE
worker-03      84     20.1   64%   11/20 GB    ACTIVE
worker-04     117     41.9   91%   18/24 GB    WARNING
worker-05      91     19.8   58%    9/20 GB    ACTIVE
worker-06       0      3.2    6%    4/16 GB    READY
```

---

# 17. Per-JVM Detail Page

Selecting a worker should show:

```text
Worker: worker-004
Node: node-002
PID: 18472
Java: 21.x
Uptime: 18h 42m

Players: 117
TPS: 20.0
MSPT avg: 41.9
MSPT p95: 49.1
MSPT p99: 54.8

Heap:
18.1 GB / 24 GB

GC:
collections: ...
pause p95: ...
allocation rate: ...

Chunks:
loaded: ...
ticking: ...
generation queue: ...

Entities:
total: ...
ticking: ...

Network:
in: ...
out: ...
packets/sec: ...
```

Charts should support:

```text
5 min
15 min
1 hour
6 hours
24 hours
7 days
```

---

# 18. Important Dashboard Screens

## Overview

Whole-cluster health.

## Workers

Every JVM and status.

## Nodes

Physical machine health.

## Players

Current player, worker, layer, region, world and session.

## Regions

Current ownership and load.

## Layers

Population-layer status.

## Economy

Service health only by default; sensitive forensic ledger access stays protected by separate permissions.

## Scaling

Automatic/assisted/manual controls.

## Logs

Searchable live log stream.

## Alerts

Warnings and incidents.

## Configuration

Safe runtime-adjustable settings.

## Audit

Administrator actions and security events.

---

# 19. Optional Python Desktop GUI

A Python GUI may be offered as an **optional convenience tool**, not the source of truth.

Possible technology:

```text
Python + PySide6
```

It should connect to the same Cluster Manager admin API used by the browser UI.

Do not implement two separate management backends.

Correct:

```text
              Cluster Manager API
              /                 \
             v                   v
        Web Dashboard        Python GUI
```

Incorrect:

```text
Web UI has one control system
Python UI has another control system
```

This prevents duplicated logic and mismatched behavior.

The Python GUI could be useful for Windows administrators who prefer a desktop application and may include:

- system tray icon;
- worker status;
- start/stop buttons;
- alerts;
- live performance charts;
- log viewer;
- quick backup button;
- open web dashboard button.

The web UI should remain the primary long-term interface because it works on Windows, Linux, tablets and remote admin devices.

---

# 20. Central Logging

The Cluster Manager must provide one place to understand the entire cluster.

Recommended structure:

```text
logs/
|
|-- cluster/
|   |-- latest.log
|   `-- 2026-09-17.log
|
|-- workers/
|   |-- worker-001/
|   |   |-- latest.log
|   |   `-- minecraft.log
|   |-- worker-002/
|   `-- ...
|
|-- audit/
|   |-- admin-actions.jsonl
|   `-- security.jsonl
|
|-- events/
|   |-- worker-lifecycle.jsonl
|   |-- region-ownership.jsonl
|   |-- layer-events.jsonl
|   `-- player-routing.jsonl
|
`-- crash/
    |-- worker-003-2026-09-17T14-22-04/
    `-- ...
```

---

# 21. Unified Live Log

The dashboard should be able to combine:

```text
Cluster Manager logs
Worker stdout/stderr
Minecraft logs
Proxy logs
Framework logs
Economy service logs
Database health events
Routing events
Region events
Layer events
Admin actions
```

with filters such as:

```text
severity
worker
node
player UUID
region
layer
mod/logger
event type
time range
text search
```

Example:

```text
[14:20:11] INFO  worker-004 Player Shnewbs entered region 81
[14:20:12] WARN  worker-004 MSPT p95 exceeded 45 ms
[14:20:13] INFO  cluster    Scaling recommendation +1 worker
[14:20:20] AUDIT admin      Approved worker start
[14:20:21] INFO  worker-010 STARTING
[14:20:39] INFO  worker-010 READY
```

---

# 22. Structured Logs

Human-readable `.log` files are useful, but important cluster activity should additionally use structured JSONL.

Example:

```json
{
  "timestamp": "2026-09-17T14:20:20Z",
  "event": "worker.manual_start",
  "actor": "admin:local-console",
  "worker": "worker-010",
  "profile": "heavy",
  "reason": "manual capacity increase"
}
```

Structured logs make future searching, dashboards and incident review much easier.

---

# 23. Audit Requirements

The following actions should always be audited:

- Worker started.
- Worker stopped.
- Worker force-killed.
- Scaling mode changed.
- Auto-scaling disabled/enabled.
- Worker pinned.
- Worker quarantined.
- Region manually reassigned.
- Layer manually manipulated.
- Player manually moved.
- Economy forensic view opened.
- Economy administrative adjustment.
- Configuration changed.
- Permissions changed.
- Backup restored.
- Cluster shutdown initiated.

Audit logs should never contain plaintext passwords, private keys or access tokens.

---

# 24. Alerting

The system should produce alerts for:

```text
High MSPT
Low TPS
Worker crash
Worker failed startup
Memory pressure
Repeated GC stalls
Disk almost full
Database unavailable
Proxy unavailable
Region ownership conflict
Heartbeat timeout
Unexpected worker disconnect
Economy storage failure
Backup failure
```

Alert levels:

```text
INFO
WARNING
CRITICAL
```

---

# 25. Dynamic Population Layering

Layering should be local/dynamic rather than duplicating the entire world.

Conceptually:

```text
ONE PERSISTENT REGION STATE
          |
          +--> Layer A population
          +--> Layer B population
          +--> Layer C population
```

All layers share authoritative persistent blocks.

Layer-safe transient simulation may differ.

---

# 26. Layer Cells

Do not switch layer at every chunk border.

Use larger **Layer Cells** containing multiple chunks.

Example starting point:

```text
16 x 16 chunks
```

Actual size should remain configurable and may eventually become dynamic.

A player should normally remain sticky to the same layer while moving through nearby cells.

---

# 27. Layer Affinity

Assignment priority should favor social continuity:

```text
1. Party
2. Explicit teleport target
3. Same active claim/base
4. Group/guild/team hooks
5. Friend affinity if available
6. Existing sticky layer
7. Population/load balancing
```

If two players intentionally meet, Foundations should prefer placing them in the same population layer.

---

# 28. Layer Split/Merge

Example:

```text
Target:          60 players
Split threshold: 80 players
Merge threshold: 30 players
```

Use hysteresis so the server does not constantly split and merge around a single threshold.

Layer decisions should also consider:

- MSPT.
- entity load;
- AI load;
- packet load;
- block entity load;
- active chunks.

Player count alone is insufficient for modded Minecraft.

---

# 29. Region Authority

For future distributed-world operation, every persistent region must have exactly one current writer.

Conceptually:

```text
(WorldId, DimensionId, RegionId)
              |
              v
         Worker Owner
```

Ownership should include:

```text
ownerWorkerId
leaseId
epoch
expiration
```

A worker may only persist state while holding the current valid lease/epoch.

This protects against split-brain writes.

---

# 30. Worker Failure / Region Failover

Example:

```text
worker-07 owns region 418
        |
        X crash
        |
Cluster misses heartbeat
        |
lease expires
        |
epoch increments
        |
worker-12 restores authoritative state
        |
region 418 becomes active
```

If worker-07 later comes back with the previous epoch, its writes are rejected.

---

# 31. Seamless Player Experience Goal

The long-term user-facing goal is:

> A player experiences one Foundations world, not a collection of Minecraft servers.

Players should never need to know:

```text
worker-014
server-027
phase-003
```

Normal gameplay should expose:

```text
World
Party
Region/claim
Friends
Gameplay
```

Infrastructure identity is an administrator concept.

---

# 32. Same-Worker Layer Switches

Population-layer transitions inside the same worker should not reconnect the client.

The server adjusts entity visibility/routing while the underlying block state remains the same.

Goal:

```text
No "Loading Terrain"
No /server command
No manual server selection
```

---

# 33. Cross-Worker Handoffs

True cross-JVM seamless handoff is a later advanced feature.

Desired sequence:

```text
Destination worker preloads target
        |
Source captures transient player state
        |
Destination acknowledges readiness
        |
Coordinator commits ownership/session move
        |
Gateway routes simulation to destination
        |
Source releases player
```

The handoff should be transactional.

If the destination fails before commit, the source remains authoritative.

---

# 34. Storage Strategy

Do not put all data into one type of database.

Suggested responsibilities:

## PostgreSQL

- economy;
- UUID ledger;
- accounts;
- claims metadata;
- market state;
- durable cluster metadata;
- ownership history;
- durable configuration metadata.

## Redis or equivalent fast shared state

- worker presence;
- online player directory;
- short-lived routing;
- active layer population;
- cache;
- heartbeats;
- ephemeral coordination.

Redis should not be the sole permanent economy ledger.

## World/Region Storage

- chunk data;
- block entities;
- entity snapshots;
- region snapshots;
- backups.

Initial versions may continue using normal Minecraft world storage while the abstraction is introduced.

---

# 35. Server Startup Policy

Configuration example:

```toml
[cluster]
mode = "local_cluster"
scaling_mode = "assisted"

[workers]
minimum = 1
warm_spares = 1
maximum = 6
default_profile = "standard"

[scaling]
target_mspt = 30.0
warning_mspt = 40.0
critical_mspt = 50.0
scale_up_sustain_seconds = 30
scale_down_idle_minutes = 15
```

---

# 36. Warm Spare Workers

For smoother scaling, optionally keep one READY worker running with no assigned heavy workload.

```text
Worker 01 ACTIVE
Worker 02 ACTIVE
Worker 03 ACTIVE
Worker 04 READY (warm spare)
```

When load spikes, Worker 04 can receive work without waiting for a full Minecraft boot.

The manager may then start another warm spare in the background.

This costs RAM but greatly improves responsiveness.

---

# 37. Manual Capacity Reservation

An administrator should be able to reserve capacity before an event.

Example:

```text
Manual Capacity Reservation

Start: 4 additional workers
Profile: standard
Pin until manually released
```

This is important for scheduled tournaments, launches or community events.

Automatic scale-down must not stop pinned capacity.

---

# 38. Graceful Drain

Normal worker shutdown should not immediately kick players.

Instead:

```text
Admin clicks DRAIN
        |
No new assignments
        |
Existing regions/sessions migrate where supported
        |
Players leave/move
        |
Worker reaches zero responsibility
        |
Worker safely stops
```

Force-stop exists only for emergencies.

---

# 39. Crash Capture

If a worker crashes, the Cluster Manager should automatically create an incident bundle containing:

```text
worker ID
node ID
exit code
last N log lines
crash report
JVM arguments
memory state
recent MSPT
recent CPU
recent GC
players assigned
regions assigned
layers assigned
timestamp
```

Example directory:

```text
logs/crash/worker-004-2026-09-17T14-22-04/
```

This should make debugging significantly easier than searching several unrelated consoles.

---

# 40. Diagnostic Bundle

Dashboard button:

```text
CREATE DIAGNOSTIC BUNDLE
```

Produces an archive containing non-secret information:

```text
cluster status
worker states
configuration with secrets redacted
recent logs
crash reports
performance metrics
mod/version list
Java versions
host information
```

This is ideal for troubleshooting Foundations deployments.

---

# 41. Security

The Cluster Manager is powerful and must be treated as administrative infrastructure.

Required principles:

- Bind dashboard to localhost by default.
- Authentication required before remote exposure.
- Role-based permissions.
- No client-side cluster secrets.
- No database credentials sent to Minecraft clients.
- Economy signing/encryption keys remain server-side.
- Audit every privileged action.
- Secrets redacted from logs.
- Proxy/backends protected from direct unauthorized connections.
- Manual worker control available only to authorized operators.

---

# 42. Admin Roles

Suggested roles:

```text
VIEWER
OPERATOR
ADMIN
FORENSIC
OWNER
```

Examples:

**VIEWER**
- metrics;
- health;
- normal logs.

**OPERATOR**
- start/restart/drain workers;
- acknowledge alerts.

**ADMIN**
- scaling/configuration;
- worker quarantine;
- routing controls.

**FORENSIC**
- protected forensic logs/economy forensic tools.

**OWNER**
- permissions/secrets/destructive operations.

The Economy forensic interface should remain separately permission-gated rather than automatically exposed to every server operator.

---

# 43. Performance Data Retention

Keep high-resolution data briefly and aggregate older data.

Example:

```text
1-second samples:   15 minutes
5-second samples:    6 hours
1-minute samples:    7 days
5-minute aggregate: 30 days
```

This prevents monitoring data itself from becoming a major storage/performance burden.

Retention must be configurable.

---

# 44. Monitoring Overhead Rule

Telemetry must never meaningfully damage server performance.

Prefer:

- lock-free/counter-based metrics where practical;
- sampling instead of expensive synchronous inspection;
- asynchronous log writing;
- batched metric persistence;
- bounded queues;
- dropped low-priority metrics before gameplay stalls.

Gameplay remains higher priority than dashboard precision.

---

# 45. Threading Rule

The Cluster Manager must never perform expensive management tasks on Minecraft's primary server thread.

Framework worker telemetry should gather already-available values or schedule safe asynchronous snapshots.

World state changes must continue to respect NeoForge/Minecraft thread safety.

---

# 46. 0.0.8a Groundwork

The complete distributed cluster does **not** need to be implemented in Economy 0.0.8a.

0.0.8a should avoid architectural decisions that make it impossible later.

Recommended groundwork:

```text
NodeId
WorkerId
ServerId
WorldId
RegionId
LayerId
SessionId

StorageProvider abstraction
ClusterMessagingProvider abstraction
WorkerTelemetry interface
ClusterPresence interface
RoutingProvider abstraction
```

Economy should gain/retain a clean storage/service boundary so its ledger can move from local storage to shared network storage without rewriting gameplay code.

---

# 47. Suggested Implementation Phases

## Phase 1 — Supervised Single Server

- Cluster Manager application.
- BAT launcher.
- One Minecraft worker.
- Web dashboard.
- Per-JVM metrics.
- Central log viewer.
- Manual start/stop/restart.
- Crash capture.
- Economy/Framework remain normal NeoForge mods.

This phase gives immediate practical value.

---

## Phase 2 — Local Multi-JVM

- Multiple worker templates.
- Dynamic port assignment.
- Worker registration.
- Proxy integration.
- Manual worker spin-up.
- Assisted scaling recommendations.
- Central player directory.
- Warm spare support.

---

## Phase 3 — Automatic Local Scaling

- automatic worker start;
- automatic drain/stop;
- resource-aware worker profiles;
- load history;
- safeguards/hysteresis;
- administrator override.

---

## Phase 4 — Distributed Nodes

- Node agent.
- Multiple physical machines.
- Shared storage.
- Shared messaging.
- Node health.
- Worker placement scheduler.
- node maintenance/drain.

---

## Phase 5 — Dynamic Population Layering

- Layer cells.
- Layer affinity.
- player visibility filtering;
- split/merge;
- party/claim affinity;
- high-population hotspot handling.

---

## Phase 6 — Region Authority

- region identifiers;
- ownership leases;
- epochs;
- migration;
- snapshots;
- failover;
- split-brain protection.

---

## Phase 7 — Seamless Gateway / Cross-Worker World

- persistent client-facing gateway;
- destination preload;
- transactional handoff;
- cross-worker movement;
- minimized/no visible loading transitions where protocol behavior permits.

This is the most advanced portion and should only be attempted after worker orchestration and world authority are proven reliable.

---

# 48. Recommended First Production UI

The first production dashboard does not need to be visually elaborate.

Prioritize:

1. Reliability.
2. Accurate metrics.
3. Worker controls.
4. Logs.
5. Clear alerts.
6. Auditability.
7. Mobile-readable layout.
8. Low overhead.

A simple fast dashboard is more valuable than an animated dashboard that consumes meaningful CPU/RAM.

---

# 49. Example Home Dashboard

```text
+------------------------------------------------------------------+
| FOUNDATIONS SERVER                                               |
| HEALTHY                                            842 PLAYERS   |
+------------------------------------------------------------------+
| Cluster MSPT  21.4 | CPU 67% | RAM 82/128 GB | Workers 9/12     |
+------------------------------------------------------------------+
| ALERTS                                                           |
| ! worker-004 MSPT p95 = 49.1                                     |
+------------------------------------------------------------------+
| WORKERS                                                          |
| worker-001 ACTIVE   96 players   18.3ms   10/20GB                |
| worker-002 ACTIVE  103 players   26.7ms   14/20GB                |
| worker-003 ACTIVE   84 players   20.1ms   11/20GB                |
| worker-004 WARNING 117 players   41.9ms   18/24GB                |
| worker-010 READY     0 players    3.1ms    4/16GB                |
+------------------------------------------------------------------+
| Suggested: start 1 additional worker                             |
| [ START WORKER ] [ APPROVE SCALE ] [ DISABLE AUTO SCALE ]       |
+------------------------------------------------------------------+
```

---

# 50. Definition of Success

From the owner/operator perspective:

```text
1. Install Foundations server package.
2. Configure RAM and server settings.
3. Double-click start_foundations_server.bat.
4. Browser dashboard opens or URL is displayed.
5. Foundations starts the required services.
6. Minecraft workers register automatically.
7. Server accepts players.
8. Operator can watch every JVM from one screen.
9. Operator can manually start capacity whenever desired.
10. All important activity appears in one searchable logging system.
```

From the player perspective:

```text
Join one server.
Play one world.
Build one persistent base.
Do not care which worker/JVM/node is running the area.
```

That separation is the core design objective.

---

# 51. Final Architecture Recommendation

The streamlined production direction is:

```text
Foundations Framework
        |
        +-- Foundations Economy
        +-- cluster-aware APIs
        +-- telemetry hooks
        +-- future region/layer hooks

Foundations Cluster Manager
        |
        +-- worker JVM lifecycle
        +-- manual oversight
        +-- assisted/automatic scaling
        +-- dashboard
        +-- logs
        +-- alerts
        +-- audit

Foundations Gateway / Proxy
        |
        +-- player routing
        +-- future seamless handoff

Persistent Services
        |
        +-- economy database
        +-- messaging/cache
        +-- world/region persistence

Worker JVM Pool
        |
        +-- region simulation
        +-- dynamic population layers
```

**Do not make Economy itself the process manager.**

**Do not make one layer equal one JVM.**

**Do not synchronize independent writable copies of player bases.**

**Do maintain one authoritative persistent world state.**

**Do make manual worker spin-up available even when automation is enabled.**

**Do build one admin API and place both the Web UI and optional Python GUI on top of it.**

**Do centralize performance, runtime, crash and audit logs.**

This gives Foundations a practical path from one ordinary NeoForge server to a supervised multi-JVM server and, eventually, a distributed persistent-world cluster without forcing normal users to deploy enterprise infrastructure.
