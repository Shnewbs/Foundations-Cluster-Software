# Foundations Cluster API — 0.0.6a.R17

The WebUI and desktop client use the Manager HTTP control plane at the configured API address (default `http://127.0.0.1:8765`). Operator endpoints require an authenticated administrator session. Agent and gateway endpoints use their existing dedicated token headers. Browser mutations are checked for same-origin requests; all requests have a bounded body size.

## R17 startup readiness

`GET /api/startup/status` now includes `gatewayPrerequisitesReady`. The WebUI uses it to wait for forwarding dependencies before enabling worker creation. Backend worker spawning independently validates the prerequisites before creating a directory.

## R16 operational endpoints

| Method and path | Purpose |
|---|---|
| `GET /api/host` | Local host CPU, memory, swap, disk and Manager JVM measurements. Unsupported OS counters are null. |
| `GET /api/metrics?seconds=900` | Cluster samples over 300–604800 seconds. Uses bounded five-second, minute or hourly history. |
| `GET /api/metrics?workerId=worker-001` | Up to one hour of worker MSPT, percentiles, CPU, heap, players and chunks. |
| `POST /api/workers/{id}/pin` | Persist a pin to exclude the worker from automatic scale-down. |
| `POST /api/workers/{id}/unpin` | Remove that pin. Manual worker controls remain available. |
| `GET /api/alerts` | Active incidents with IDs, first-seen time and acknowledgement actor. |
| `POST /api/alerts/acknowledge` | JSON body `{"id":"<id from current alerts>"}`. Acknowledgements persist until the incident resolves. |
| `GET /api/transfers/trace` | Last 1000 metadata-only transfer events, correlated by handoff ID. |
| `POST /api/gateway/trace` | Gateway/admin trace submission: `handoffId`, `stage`, `elapsedMillis`, `detail`. Requires an existing handoff and a recognized stage. |
| `GET /api/diagnostics/download` | Authenticated ZIP export of allowlisted health and trace metadata; recorded in the audit trail. |

History and transfer trace buffers reset on Manager restart. Stopped or stale workers do not contribute to live cluster aggregates. `GET /api/workers` now includes `pinned` and `migrationEligible` on each worker.

`GET /api/status`, gateway topology and gateway heartbeat responses expose the global shutdown lifecycle. Once shutdown begins, new admin mutations and failover requests are rejected with 503 rather than starting topology work during teardown. Read-only operator endpoints remain available while the HTTP server is alive.

Migration destination selection validates heartbeat freshness, ready state, player capacity, MSPT and `placement.maxHeapPercent` (default 85). Existing physical handoff endpoints remain active for real backend migration. Normal Minecraft reconfiguration is the default; `gateway.seamless.enabled=true` is an experimental opt-in applied on restart.

## Historical protocol reference

The following R7 notes are retained for background. They describe earlier stages of the relay and persistent-session model, not the complete R16 protocol. Current physical handoffs, profile restore and BlockEntity journaling are described in the master roadmap and implemented in the Node/Manager sources.

---

# Foundations Cluster API — 0.0.5a.R7

The Web UI uses the same HTTP control plane. Dashboard APIs require the authenticated admin session unless otherwise stated.

## Simulation data plane

### Admin status

- `GET /api/dataplane/status`

Returns aggregate relay counters and recent frame metadata. Payload contents are intentionally not emitted into the normal dashboard history.

### Cluster Node endpoints

All three require `X-Foundations-Agent-Token`.

- `POST /api/agent/dataplane/publish`
- `POST /api/agent/dataplane/poll`
- `POST /api/agent/dataplane/ack`

### Publish fields

```text
kind
sourceWorkerId
targetWorkerId        optional when manager can resolve it
sessionId
playerId
worldId
dimensionId
regionId
authorityEpoch
payload
```

R6 frame classes used by the prototype:

- `BLOCK_REQUEST` — Session Anchor -> current Region Authority owner.
- `BLOCK_DELTA` — current Region Authority owner -> Session Anchor.

Request validation requires an active session, requires the publishing source to be that session's anchor, and resolves/validates the current ACTIVE Region Authority owner and epoch.

Persistent deltas require the publishing source to be the current ACTIVE region owner and require the current epoch. A stale epoch is rejected.

### Poll/ACK

A Node polls with:

```json
{"workerId":"worker-001","maxFrames":64}
```

The manager returns a compact encoded bundle. Delivered frames remain in-flight until acknowledged. If ACK does not arrive before `dataplane.retryMillis`, the same frame can be delivered again. Frames expire after `dataplane.frameTtlMillis`.

ACK body:

```json
{"workerId":"worker-001","frameId":"dp-0000000001","ok":true,"error":""}
```

## Worker control response

Register/heartbeat responses now include:

```text
dataPlaneProtocol = relay-v2-chunk-read
simulationRoutes  = encoded route records for sessions anchored to this worker
```

Each route carries the session/player, Simulation Target, current Region Authority worker, region ID, current authority epoch and dimension. The manager refreshes Region Authority owner/epoch from the live authority directory before producing the token.

## Persistent sessions / invisible transitions

- `GET /api/sessions`
- `POST /api/sessions/place`
- `POST /api/sessions/{id}/release`
- `GET /api/handoffs`
- `POST /api/handoffs/request`
- `POST /api/handoffs/{id}/abort`

R6 retains the R5 persistent-session model: normal simulation-target changes preserve the Session Anchor and do not generate Velocity backend-switch work.

## Gateway adapter

- `GET /api/gateway/topology`
- `POST /api/gateway/heartbeat`
- `GET /api/gateway/handoffs` — normally empty in persistent-session mode.
- legacy `/api/gateway/handoffs/*` commit/switch actions remain debug/emergency compatibility only.

## Current protocol boundary

`relay-v2-chunk-read` is a correctness-first manager relay. It is not yet the final high-volume chunk/entity transport. R7 adds CHUNK_REQUEST and CHUNK_SNAPSHOT over the same authority/epoch rules. Block entities, containers, entities, TFC state and machine replication still require richer frame types and eventually a faster direct worker data path.
