/* HF16 worker controls. Extend the operator workspace without changing lifecycle protocols. */
const workerControls = (() => {
  const pendingWorkers = new Set();
  let systemPending = false;
  let shutdownAccepted = false;
  const tableIds = ['workerRows', 'dashboardWorkerRows'];
  const controlledActions = new Set(['restart', 'stop', 'force-stop', 'kill']);
  const interactive = 'button,a,input,select,textarea,label,summary,[role="button"],[contenteditable="true"]';
  const baseWorkerAction = workerAction;
  const baseWorkers = renderWorkers;
  const baseDashboardWorkers = renderDashboardWorkers;
  const baseOperations = renderOperations;
  const baseDrawer = renderDrawer;

  function shuttingDown() {
    return shutdownAccepted || state.status?.lifecycle === 'SHUTTING_DOWN';
  }
  function unavailable() {
    return !state.status || (state.staleEndpoints || []).some(key => key === 'status' || key === 'workers');
  }
  function reason(w, action) {
    if (shuttingDown()) return 'Cluster shutdown has been requested.';
    if (systemPending) return 'A cluster request is being submitted.';
    if (unavailable()) return 'Refresh the connection before changing worker processes.';
    if (!w || !w.managed || w.controlDetached) return 'This worker is not controlled by this Manager.';
    if (pendingWorkers.has(w.id)) return 'A request for this worker is already being submitted.';
    if (action === 'restart' && (w.state === 'RETIRED' || w.state === 'STOPPING' || w.gracefulStopPending))
      return 'Wait for the current stop, or cancel it, before restarting.';
    if (action === 'stop' && (w.gracefulStopPending || w.state === 'STOPPING'))
      return 'A graceful stop is already in progress. Kill is the emergency alternative.';
    if (action !== 'restart' && !w.processAlive) return 'This worker has no running local process.';
    return '';
  }
  function title(action, id) {
    if (action === 'restart') return `Restart ${id}: evacuate players and authority, then relaunch.`;
    if (action === 'stop') return `Stop ${id}: graceful evacuation and save-aware shutdown; no relaunch.`;
    return `Kill ${id}: immediately terminate the process. Unsaved changes may be lost.`;
  }
  function confirmation(w, action) {
    if (action === 'restart') return `Restart ${w.id}?\n\nFoundations will evacuate players and Region Authority, stop this worker after migration commits, then relaunch it. The Manager and gateway stay online. A blocked evacuation aborts the restart.`;
    if (action === 'stop') return `Stop ${w.id}?\n\nThis is a graceful, migration-aware stop, not a kill. The worker stays alive while evacuation makes progress; a migration timeout aborts the stop. It will not be relaunched by this action. Other workers, the Manager and gateway stay online.`;
    return `KILL ${w.id}?\n\nThis immediately terminates the worker process. UNSAVED WORLD CHANGES MAY BE LOST and connected players may be disconnected. Player migration is NOT guaranteed.\n\nReported pending writes: ${w.pendingWrites ?? 'unknown'}; failed writes: ${w.failedWrites ?? 'unknown'}. These counts do not prove that all world data is saved.\n\nUse Stop for a graceful shutdown. Continue with Kill?`;
  }
  async function act(id, action) {
    if (!controlledActions.has(action)) return baseWorkerAction(id, action);
    action = action === 'kill' ? 'force-stop' : action;
    const w = state.workers.find(worker => worker.id === id);
    const blocked = reason(w, action);
    if (blocked) { toast(blocked, 'warn'); return; }
    if (!confirm(confirmation(w, action))) return;
    pendingWorkers.add(id);
    renderState();
    try {
      const result = await api(`/api/workers/${encodeURIComponent(id)}/${action}`, {method:'POST'});
      const label = action === 'force-stop' ? 'Kill' : action === 'stop' ? 'Graceful stop' : 'Restart';
      toast(`${id}: ${label} requested${result.state ? ' · ' + result.state : ''}`, action === 'force-stop' ? 'warn' : '');
    } catch (error) {
      toast(`${id}: ${error.message}`, 'error');
    } finally {
      pendingWorkers.delete(id);
      try { await refreshCore(); } catch (error) { toast('Status refresh failed: ' + error.message, 'error'); }
      renderState();
    }
  }
  async function restart() {
    if (systemPending || shuttingDown()) return;
    if (unavailable()) { toast('Refresh the connection before restarting servers.', 'warn'); return; }
    const count = state.workers.filter(w => w.managed && !w.controlDetached && w.state !== 'RETIRED').length;
    if (!count) { toast('No managed servers are available to restart.', 'warn'); return; }
    if (!confirm(`Restart managed Minecraft servers?\n\nThis requests a graceful rolling restart (${count} eligible worker(s) in the current view). The Manager/dashboard and gateway stay online. Evacuation must complete before each worker stops; a blocked evacuation aborts that worker's restart.\n\nThis does NOT shut down the cluster.`)) return;
    systemPending = true; renderState();
    try {
      const result = await api('/api/system/restart-workers', {method:'POST'});
      toast(`Rolling restart requested for ${result.workers} worker(s). Manager and gateway remain online.`);
    } catch (error) { toast('Server restart: ' + error.message, 'error'); }
    finally {
      systemPending = false;
      try { await refreshCore(); } catch (error) { toast('Status refresh failed: ' + error.message, 'error'); }
      renderState();
    }
  }
  async function shutdown() {
    if (systemPending || shuttingDown()) return;
    if (!state.status || (state.staleEndpoints || []).includes('status')) {
      toast('The Manager must be reachable to request cluster shutdown.', 'warn'); return;
    }
    if (!confirm('Shut down the ENTIRE cluster?\n\nThis closes Foundations Manager/dashboard and the owned gateway, and requests shutdown of all managed Minecraft workers. Connected players will disconnect. Externally managed processes are not terminated.\n\nYou must start Foundations again from its launcher. To restart only Minecraft servers, cancel and use Restart Servers.')) return;
    systemPending = true; renderState();
    try {
      await api('/api/system/shutdown', {method:'POST'});
      shutdownAccepted = true;
      toast('Cluster shutdown accepted. The dashboard will go offline as the Manager exits.', 'warn');
    } catch (error) {
      toast('Shutdown confirmation not received: ' + error.message + '. Check the Manager before retrying.', 'error');
    } finally { systemPending = false; renderState(); }
  }
  function applyButton(button, w, action) {
    const blocked = reason(w, action);
    button.disabled = !!blocked;
    button.title = blocked || title(action, w.id);
    button.setAttribute('aria-label', `${action === 'force-stop' ? 'Kill' : action === 'stop' ? 'Stop' : 'Restart'} ${w?.id || 'worker'}`);
  }
  function captureFocus(table) {
    const active = document.activeElement;
    if (!active || !table.contains(active)) return null;
    const tr = active.closest('tr[data-worker-id]');
    return tr ? {id:tr.dataset.workerId, action:active.dataset.action, manage:active.hasAttribute('data-manage')} : null;
  }
  function restoreFocus(table, focus) {
    if (!focus || !$('workerDrawer').classList.contains('hidden')) return;
    const tr = Array.from(table.rows).find(row => row.dataset.workerId === focus.id);
    if (!tr) return;
    const target = focus.action ? Array.from(tr.querySelectorAll('[data-action]')).find(b => b.dataset.action === focus.action)
      : focus.manage ? tr.querySelector('[data-manage]') : tr;
    (target && !target.disabled ? target : tr).focus({preventScroll:true});
  }
  function decorate(table) {
    if (!table) return;
    const workers = new Map(state.workers.map(w => [w.id, w]));
    for (const tr of table.rows) {
      const manage = tr.querySelector('[data-manage]');
      const w = manage && workers.get(manage.dataset.manage);
      if (!w) continue;
      tr.dataset.workerId = w.id;
      tr.classList.add('worker-row');
      tr.tabIndex = 0;
      tr.setAttribute('aria-label', `${w.id} details. Press Enter or Space to open.`);
      tr.setAttribute('aria-controls', 'workerDrawer');
      tr.setAttribute('aria-haspopup', 'dialog');
      manage.setAttribute('aria-label', `Manage ${w.id}`);
      if (w.managed) {
        let controls = tr.querySelector('.controls');
        if (!controls) {
          controls = document.createElement('div'); controls.className = 'controls';
          manage.parentElement.appendChild(controls); controls.appendChild(manage);
        }
        for (const action of ['restart', 'stop', 'force-stop']) {
          let button = Array.from(controls.querySelectorAll('[data-action]')).find(b => b.dataset.action === action);
          if (!button) {
            button = document.createElement('button'); button.type = 'button';
            button.className = action === 'force-stop' ? 'danger-outline small worker-kill' : 'secondary small';
            button.dataset.action = action; button.dataset.id = w.id;
            button.textContent = action === 'force-stop' ? 'Kill' : action === 'stop' ? 'Stop' : 'Restart';
            controls.appendChild(button);
          }
          button.onclick = event => { event.stopPropagation(); act(w.id, action); };
          applyButton(button, w, action);
        }
      }
    }
  }
  function bindTable(table) {
    if (!table) return;
    const rowFor = target => target.closest('tr[data-worker-id]');
    function open(tr) { openWorker(tr.dataset.workerId); $('closeDrawer').focus({preventScroll:true}); }
    table.addEventListener('click', event => {
      if (event.defaultPrevented || event.target.closest(interactive)) return;
      const tr = rowFor(event.target);
      if (!tr || tr.parentElement !== table) return;
      const selection = window.getSelection();
      if (selection && !selection.isCollapsed && (tr.contains(selection.anchorNode) || tr.contains(selection.focusNode))) return;
      open(tr);
    });
    table.addEventListener('keydown', event => {
      if (event.defaultPrevented || event.repeat || event.altKey || event.ctrlKey || event.metaKey || !['Enter',' '].includes(event.key)) return;
      const tr = rowFor(event.target);
      if (!tr || event.target !== tr) return;
      event.preventDefault(); open(tr);
    });
  }
  function renderState() {
    const workers = new Map(state.workers.map(w => [w.id, w]));
    for (const tableId of tableIds) {
      const table = $(tableId);
      if (!table) continue;
      for (const button of table.querySelectorAll('[data-action]')) {
        if (!controlledActions.has(button.dataset.action)) continue;
        applyButton(button, workers.get(button.dataset.id), button.dataset.action);
      }
    }
    const selected = workers.get(state.selectedWorker);
    for (const [id, action] of [['drawerRestart','restart'],['drawerStop','stop'],['drawerKill','force-stop']])
      if ($(id)) applyButton($(id), selected, action);
    const hasServers = state.workers.some(w => w.managed && !w.controlDetached && w.state !== 'RETIRED');
    for (const id of ['restartServersTop','restartServersBtn']) if ($(id)) {
      $(id).disabled = systemPending || shuttingDown() || unavailable() || !hasServers;
      $(id).title = 'Restart managed Minecraft workers only. Manager/dashboard and gateway remain online.';
    }
    for (const id of ['shutdownClusterTop','shutdownBtn']) if ($(id)) {
      $(id).disabled = systemPending || shuttingDown() || !state.status || (state.staleEndpoints || []).includes('status');
      $(id).title = 'Shut down Manager/dashboard, the owned gateway, and managed Minecraft workers.';
    }
    if (shuttingDown()) {
      $('topClusterState').textContent = 'Cluster shutdown requested';
      const banner = $('connectionBanner');
      banner.classList.remove('hidden');
      banner.textContent = 'Cluster shutdown requested. The Manager/dashboard will go offline. Use the Foundations launcher to start it again; external processes are not stopped.';
    }
  }
  // Keep direct filter/sort renders and periodic refreshes on the same decoration path.
  renderWorkers = function() {
    const table = $('workerRows'), focus = captureFocus(table);
    baseWorkers(); decorate(table); restoreFocus(table, focus);
  };
  renderDashboardWorkers = function() {
    const table = $('dashboardWorkerRows'), focus = captureFocus(table);
    baseDashboardWorkers(); decorate(table); restoreFocus(table, focus);
  };
  renderOperations = function() { baseOperations(); renderState(); };
  renderDrawer = function() { baseDrawer(); renderState(); };
  workerAction = act;
  restartServers = restart;
  $('workerFilter').removeEventListener('input', baseWorkers);
  $('workerFilter').addEventListener('input', renderWorkers);
  if ($('workerSort')) $('workerSort').onchange = renderWorkers;
  for (const id of ['restartServersTop','restartServersBtn']) $(id).onclick = restart;
  $('shutdownBtn').onclick = shutdown;

  const restartTop = $('restartServersTop');
  const serverGroup = document.createElement('div');
  serverGroup.className = 'server-controls'; serverGroup.setAttribute('role', 'group');
  serverGroup.setAttribute('aria-label', 'Minecraft server controls');
  restartTop.before(serverGroup); serverGroup.appendChild(restartTop);
  const clusterGroup = document.createElement('div');
  clusterGroup.className = 'cluster-controls'; clusterGroup.setAttribute('role', 'group');
  clusterGroup.setAttribute('aria-label', 'Entire cluster controls');
  const shutdownTop = document.createElement('button');
  shutdownTop.id = 'shutdownClusterTop'; shutdownTop.type = 'button';
  shutdownTop.className = 'top-btn danger-outline'; shutdownTop.textContent = '■ Shutdown Cluster';
  shutdownTop.onclick = shutdown; clusterGroup.appendChild(shutdownTop); serverGroup.after(clusterGroup);
  const drawer = $('workerDrawer');
  drawer.setAttribute('role', 'dialog'); drawer.setAttribute('aria-modal', 'true');
  drawer.setAttribute('aria-labelledby', 'drawerWorkerId');
  // Preserve existing drawer controls, but keep the three process actions together.
  $('drawerRestart').after($('drawerStop'), $('drawerKill'));
  $('drawerStop').textContent = 'Stop'; $('drawerKill').textContent = 'Kill';
  for (const id of tableIds) { bindTable($(id)); decorate($(id)); }
  renderState();
  return {render:renderState};
})();
