# Validation — Foundations Cluster Runtime 0.0.5a.R7.R2

## Scope

R7.R2 is intentionally limited to Region Authority operator UX hardening. The R7 chunk read-replication, block RPC, epoch fencing, persistent-session model, Cluster Node `0.0.5a.R7`, and data-plane protocol `relay-v2-chunk-read` are unchanged.

## Build checks

- Cluster Manager compiled with Java 21.
- Node Simulator compiled with Java 21.
- `web/app.js` passed `node --check`.
- The manager health endpoint reports `0.0.5a.R7.R2`.
- The packaged manager still advertises Cluster Node `0.0.5a.R7` and `relay-v2-chunk-read`.

## Region transfer picker regression

The Region Authority table no longer embeds a live `<select>` that is destroyed/recreated every 2-second dashboard refresh. Transfer now opens a dedicated modal.

The modal:

- stores the current region ID and selected destination in browser state,
- preserves the selected worker while telemetry refreshes,
- rebuilds options only when the eligible worker signature changes,
- excludes current owner, offline, stale, draining, and maintenance workers,
- shows worker state, MSPT, players, and authority-region count,
- provides an Auto Select Best Worker action,
- disables confirmation when no valid worker is available,
- closes only after a successful authority transfer.

The Ensure Region worker selector received the same signature-based refresh protection.

## Safety

No Region Authority API semantics changed. Transfers still go through the manager's existing authority transfer endpoint, which increments the fencing epoch. No chunk/data-plane or Node code was modified for this revision.

## Packaged ZIP smoke test

The final full ZIP was extracted into a clean runtime and validated from the packaged bytes:

- `web/app.js` passed `node --check`.
- Region transfer modal IDs and Auto Select Best Worker controls are present in the packaged Web UI.
- Cluster Manager started successfully from the packaged JAR and `/api/health` returned `0.0.5a.R7.R2`.
- `ClusterVersion.NODE_VERSION` remains `0.0.5a.R7`.
- The R7→R7.R2 upgrade ZIP contains no `runtime/`, `workers/`, `logs/`, `server-template/`, `velocity/`, or active `config/cluster.properties`, preserving live server state during overlay upgrades.
