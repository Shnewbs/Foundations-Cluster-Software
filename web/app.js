const $=id=>document.getElementById(id);
const $$=sel=>Array.from(document.querySelectorAll(sel));
const state={status:null,gateway:null,dataplane:null,workers:[],orphans:[],sessions:[],handoffs:[],layers:{layers:[]},layerGroups:{groups:[]},regions:{regions:[]},playerStates:[],metrics:[],alerts:[],logs:[],config:{},auth:null,template:null,agent:null,startup:null,scaling:null,audit:[],consoleOutput:[],consoleHistory:[],consoleTransient:[],consolePaused:false,consoleCleared:false,consoleHistoryIndex:-1,page:'dashboard',selectedWorker:null,refreshing:false,pendingRegionTransfer:null};

function esc(v){return String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function bytes(n){n=Number(n)||0;if(!n)return'0 B';const u=['B','KB','MB','GB','TB'];let i=0;while(n>=1024&&i<u.length-1){n/=1024;i++;}return n.toFixed(i>=3?1:0)+' '+u[i];}
function duration(ms){ms=Number(ms)||0;if(!ms)return'—';let s=Math.floor(ms/1000);const d=Math.floor(s/86400);s%=86400;const h=Math.floor(s/3600);s%=3600;const m=Math.floor(s/60);if(d)return`${d}d ${h}h`;if(h)return`${h}h ${m}m`;return`${m}m`;}
function age(seconds){seconds=Number(seconds)||0;if(seconds<60)return`${seconds}s`;if(seconds<3600)return`${Math.floor(seconds/60)}m`;return`${Math.floor(seconds/3600)}h ${Math.floor((seconds%3600)/60)}m`;}
function num(v,d=1){return Number(v||0).toFixed(d);}
function toast(message,type=''){const host=$('toastHost');const d=document.createElement('div');d.className='toast '+type;d.textContent=message;host.appendChild(d);setTimeout(()=>d.remove(),5200);}

async function rawApi(path,opts={}){
  const r=await fetch(path,{signal:AbortSignal.timeout(path.includes("/api/regions/")?35000:12000),credentials:'same-origin',...opts,headers:{...(opts.headers||{})}});
  const body=await r.json().catch(()=>({}));
  if(!r.ok)throw Object.assign(new Error(body.error||`HTTP ${r.status}`),{status:r.status,body});
  return body;
}
async function api(path,opts={}){
  try{return await rawApi(path,opts);}catch(e){if(e.status===401&&path!=='/api/auth/login'){await requireAuth();}throw e;}
}
function jsonOpts(method,body){return{method,headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}};

async function requireAuth(){
  $('appShell').classList.add('hidden');$('authScreen').classList.remove('hidden');
  try{state.auth=await rawApi('/api/auth/status');renderAuth();}catch(e){$('authError').textContent='Cannot reach Cluster Manager: '+e.message;}
}
function renderAuth(){
  const setup=!!state.auth?.setupRequired;$('authSetup').classList.toggle('hidden',!setup);$('authLogin').classList.toggle('hidden',setup);$('authError').textContent='';
  if(!setup)$('loginUser').value=state.auth?.username||'admin';
}
async function bootAuthenticated(){
  $('authScreen').classList.add('hidden');$('appShell').classList.remove('hidden');
  try{const me=await api('/api/auth/me');$('accountName').textContent=me.username||'Admin';$('accountBtn').querySelector('.avatar').textContent=(me.username||'A').charAt(0).toUpperCase();}catch{}
  await loadConfig();await refreshCore();await refreshAudit();navigate(document.getElementById('page-'+location.hash.slice(1))?location.hash.slice(1):state.page);startClock();
}

$('setupForm').addEventListener('submit',async e=>{e.preventDefault();$('authError').textContent='';if($('setupPassword').value!==$('setupPassword2').value){$('authError').textContent='Passwords do not match.';return;}try{await rawApi('/api/auth/setup',jsonOpts('POST',{username:$('setupUser').value.trim(),password:$('setupPassword').value,remember:$('setupRemember').checked}));await bootAuthenticated();}catch(err){$('authError').textContent=err.message;}});
$('loginForm').addEventListener('submit',async e=>{e.preventDefault();$('authError').textContent='';try{await rawApi('/api/auth/login',jsonOpts('POST',{username:$('loginUser').value.trim(),password:$('loginPassword').value,remember:$('loginRemember').checked}));$('loginPassword').value='';await bootAuthenticated();}catch(err){$('authError').textContent=err.message;}});
$('logoutBtn').onclick=async()=>{try{await rawApi('/api/auth/logout',{method:'POST'});}catch{}$('accountDropdown').classList.add('hidden');await requireAuth();};

function navigate(page){state.page=page;history.replaceState(null,'','#'+page);$$('.page').forEach(p=>p.classList.toggle('active',p.id==='page-'+page));$$('.nav-item').forEach(b=>b.classList.toggle('active',b.dataset.page===page));if(page==='audit')refreshAudit();if(page==='logs')renderLogs();if(page==='console')refreshConsole(true);if(page==='settings'||page==='scaling'||page==='alerts'||page==='console'||page==='gateway')loadConfig();}
$$('[data-page]').forEach(b=>b.onclick=()=>navigate(b.dataset.page));$$('[data-page-jump]').forEach(b=>b.onclick=()=>navigate(b.dataset.pageJump));
$('accountBtn').onclick=()=> $('accountDropdown').classList.toggle('hidden');document.addEventListener('click',e=>{if(!e.target.closest('.account-menu'))$('accountDropdown').classList.add('hidden');});

function startClock(){if(startClock.started)return;startClock.started=true;setInterval(()=>{$('topClock').textContent=new Date().toLocaleString();},1000);$('topClock').textContent=new Date().toLocaleString();}

async function loadConfig(){
  try{const c=await api('/api/config');state.config=c.values||{};fillConfig();const a=await rawApi('/api/auth/status');state.auth=a;$('vaultType').textContent=a.secretStorage||'Encrypted local vault';$('securityStorage').textContent=(a.passwordStorage||'Argon2id')+' + Vault';}catch(e){if(e.status!==401)toast('Config load failed: '+e.message,'error');}
}
function fillConfig(){
  $$('[data-config]').forEach(el=>{const v=state.config[el.dataset.config];if(v==null||el.closest('form')?.dataset.dirty==='true')return;if(el.type==='checkbox')el.checked=String(v).toLowerCase()==='true';else el.value=v;});
  $('scalingModeHint').textContent=(state.config['scaling.mode']||'manual').toUpperCase();
}
function collectForm(form){const o={};form.querySelectorAll('[data-config]').forEach(el=>o[el.dataset.config]=el.type==='checkbox'?String(el.checked):el.value.trim());return o;}
async function saveConfigForm(form){
  try{const result=await api('/api/config',jsonOpts('PUT',collectForm(form)));form.dataset.dirty='false';await loadConfig();let msg='Settings saved.';if(result.restartRequired?.length)msg+=' Restart Cluster Manager for: '+result.restartRequired.join(', ');if(result.newWorkersOnly?.length)msg+=' New-worker-only: '+result.newWorkersOnly.join(', ');if(result.workerRestartRequired?.length)msg+=' Worker restart required: '+result.workerRestartRequired.join(', ');toast(msg,result.restartRequired?.length?'warn':'');showSettingsNotice(result);}catch(e){toast(e.message,'error');}
}
function showSettingsNotice(r){const n=$('settingsNotice');const parts=[];if(r.restartRequired?.length)parts.push('Manager restart required for '+r.restartRequired.join(', ')+'.');if(r.newWorkersOnly?.length)parts.push('These settings apply to newly spawned workers: '+r.newWorkersOnly.join(', ')+'.');if(r.workerRestartRequired?.length)parts.push('Restart workers to apply: '+r.workerRestartRequired.join(', ')+'.');if(!parts.length){n.classList.add('hidden');return;}n.textContent=parts.join(' ');n.classList.remove('hidden');}
$$('.config-form').forEach(f=>f.addEventListener('submit',e=>{e.preventDefault();saveConfigForm(f);}));['scalingForm','alertsForm','securityConfigForm','layerConfigForm','regionConfigForm','consoleConfigForm','gatewayConfigForm'].forEach(id=>{const el=$(id);if(el)el.addEventListener('submit',e=>{e.preventDefault();saveConfigForm(el);});});

async function refreshCore(){
  if(state.refreshing||$('appShell').classList.contains('hidden')||document.hidden)return;state.refreshing=true;
  const requests=[['status','/api/status'],['gateway','/api/gateway/status'],['dataplane','/api/dataplane/status'],['workers','/api/workers','workers'],['orphans','/api/workers/orphans','orphans'],['sessions','/api/sessions','sessions'],['handoffs','/api/handoffs','handoffs'],['layers','/api/layers'],['layerGroups','/api/layers/groups'],['regions','/api/regions'],['playerStates','/api/player-state','records'],['metrics','/api/metrics?seconds='+($('metricsRange')?.value||900),'samples'],['alerts','/api/alerts','alerts'],['logs','/api/logs?limit=320','lines'],['template','/api/template/status'],['agent','/api/node-agent/status'],['startup','/api/startup/status'],['scaling','/api/scaling/status'],['host','/api/host'],['trace','/api/transfers/trace','events'],['environment','/api/world/environment']];
  try{
    const results=await Promise.allSettled(requests.map(([,path])=>rawApi(path)));
    state.staleEndpoints=[];
    results.forEach((r,i)=>{const [key,,field]=requests[i];if(r.status==='fulfilled'){if(key!=='logs'||!state.logsPaused)state[key]=field?r.value[field]||[]:r.value;}else state.staleEndpoints.push(key);});
    if(results[0].status==='rejected'&&results[0].reason.status===401){await requireAuth();return;}
    if(results[0].status==='fulfilled')state.lastFresh=Date.now();
    renderAll();
  }finally{state.refreshing=false;}
}

function renderAll(){renderHeader();renderDashboard();renderGateway();renderDataPlane();renderHandoffs();renderWorkers();renderOrphans();renderLayers();renderRegions();renderWorldTopology();renderSessions();renderPlayerStates();renderStartup();renderScalingRecommendation();renderAlerts();renderLogs();renderConsoleTargets();renderTestLabOptions();if(!state.selectedWorker||!$('workerDrawer').classList.contains('hidden'))renderDrawer();if(typeof renderOperations==='function')renderOperations();}
function renderHeader(){const s=state.status;if(!s)return;$('modeBadge').textContent=String(s.scalingMode||'manual').toUpperCase();$('topClusterState').textContent=(s.lifecycle==='SHUTTING_DOWN'?'Shutting Down':'Cluster Online')+' · '+s.version;$('navWorkerCount').textContent=s.workers;$('navPlayerCount').textContent=s.players;$('navAlertCount').textContent=s.alerts||'';$('navAlertCount').classList.toggle('hidden',!(s.alerts>0));if($('navGatewayState')){$('navGatewayState').textContent=s.gatewayRunning?'●':'!';$('navGatewayState').classList.toggle('good',!!s.gatewayRunning);}if($('playerGatewayState')){$('playerGatewayState').textContent=s.gatewayRunning?'READY':'OFFLINE';$('playerGatewayState').className=s.gatewayRunning?'good':'bad';}}

function renderGateway(){
  const g=state.gateway;if(!g||!$('gatewayStateBadge'))return;
  const ready=!!g.running;$('gatewayStateBadge').textContent=ready?'READY':g.enabled?'ERROR':'DISABLED';$('gatewayStateBadge').className='pill '+(ready?'good':'pending');
  $('gEndpoint').textContent=`${g.bind}:${g.port}`;$('gActive').textContent=g.activeConnections||0;$('gRouted').textContent=g.routedConnections||0;$('gFailed').textContent=g.failedConnections||0;
  $('gPublicNode').textContent=`${g.bind}:${g.port}`;$('gListenerState').textContent=ready?'LISTENING':g.enabled?'ERROR':'DISABLED';$('gListenerState').className=ready?'good':'bad';$('gBackendHost').textContent=g.backendHost||'127.0.0.1';$('gWorkerBind').textContent=g.workerBindAddress||'127.0.0.1';if($('gTransport')){const transportState=g.running?(g.transportVersion||'starting'):(g.lastError?'failed':(g.transportVersion||'starting'));$('gTransport').textContent=((g.mode||'velocity')+' / '+transportState).toUpperCase();}if($('gLegacySwitch')){$('gLegacySwitch').textContent=g.legacyVelocityBackendSwitch?'ENABLED':'DISABLED';$('gLegacySwitch').className=g.legacyVelocityBackendSwitch?'pending':'good';}$('gatewayRouteCount').textContent=(g.routes||[]).length;
  const err=$('gatewayError');if(g.lastError){err.textContent='Gateway error: '+g.lastError;err.classList.remove('hidden');}else err.classList.add('hidden');
  const nodes=$('gatewayWorkerNodes');nodes.innerHTML='';const routable=state.workers.filter(w=>['READY','ACTIVE'].includes(w.state)&&!w.draining&&!w.maintenance);if(!routable.length)nodes.innerHTML='<div class="empty-cell">No routable backend workers yet.</div>';else for(const w of routable){const d=document.createElement('div');d.className='gateway-worker-node';d.innerHTML=`<strong>${esc(w.id)}</strong><span>${esc(g.backendHost||'127.0.0.1')}:${w.port}</span><small>${w.players||0} players · ${num(w.mspt,1)} mspt</small>`;nodes.appendChild(d);}
  const rows=$('gatewayRouteRows');rows.innerHTML='';const routes=g.routes||[];if(!routes.length){rows.innerHTML='<tr><td colspan="7" class="empty-cell">No active gateway-routed players.</td></tr>';}else for(const r of routes){const tr=document.createElement('tr');tr.innerHTML=`<td><strong>${esc(r.playerId)}</strong></td><td>${esc(r.workerId)}</td><td>${r.workerPort}</td><td>${esc(r.layerId||'—')}</td><td>${esc(r.regionId||'—')}<br><small>${esc(r.authorityWorkerId||'—')}${r.authorityEpoch?` @ ${r.authorityEpoch}`:''}</small></td><td>${esc(r.remoteAddress||'—')}</td><td>${duration(Date.now()-(r.connectedAtMillis||Date.now()))}</td>`;rows.appendChild(tr);}
}

function renderDataPlane(){
  const d=state.dataplane;if(!d||!$('dataPlaneState'))return;
  $('dataPlaneState').textContent=d.enabled?'ENABLED':'DISABLED';$('dataPlaneState').className='pill '+(d.enabled?'good':'pending');
  $('dpPublished').textContent=d.published||0;$('dpAcked').textContent=d.acked||0;$('dpPending').textContent=(d.pending||0)+(d.inflight||0);$('dpDropped').textContent=d.dropped||0;if($('dpChunkRequests'))$('dpChunkRequests').textContent=d.chunkRequests||0;if($('dpChunkSnapshots'))$('dpChunkSnapshots').textContent=d.chunkSnapshots||0;
  const rows=$('dataPlaneRows');rows.innerHTML='';const list=d.recent||[];if(!list.length){rows.innerHTML='<tr><td colspan="7" class="empty-cell">No remote simulation frames yet. Frames appear when an anchored player interacts with a region owned by another worker.</td></tr>';return;}
  for(const f of list.slice(0,20)){const tr=document.createElement('tr');tr.innerHTML=`<td><strong>${esc(f.kind)}</strong><br><small>${esc(f.id)}</small></td><td>${esc(f.sourceWorkerId)}</td><td>${esc(f.targetWorkerId)}</td><td>${esc(f.playerId||'—')}</td><td>${esc(f.regionId||'—')} ${f.authorityEpoch?`@${f.authorityEpoch}`:''}</td><td>${f.deliveries||0}</td><td>${duration(Date.now()-(f.createdAtMillis||Date.now()))}</td>`;rows.appendChild(tr);}
}

function renderHandoffs(){
  const rows=$('handoffRows');if(!rows)return;const list=state.handoffs||[];$('handoffCount').textContent=list.length;rows.innerHTML='';
  if(!list.length){rows.innerHTML='<tr><td colspan="6" class="empty-cell">No player transfers yet. Choose a live Gateway player and a destination worker.</td></tr>';return;}
  for(const h of list.slice().sort((a,b)=>(b.createdAtMillis||0)-(a.createdAtMillis||0))){const terminal=['COMMITTED','ABORTED','FAILED'].includes(h.state);const tr=document.createElement('tr');tr.innerHTML=`<td><strong>${esc(h.playerId)}</strong><br><small>${esc(h.id)}</small></td><td>${esc(h.sourceWorkerId)}</td><td>${esc(h.destinationWorkerId)}</td><td><span class="layer-chip ${esc(h.state)}">${esc(h.state)}</span>${h.error?`<br><small class="bad">${esc(h.error)}</small>`:''}</td><td>${esc(h.reason||'—')}</td><td>${!terminal?`<button class="danger-outline small" data-handoff-abort="${esc(h.id)}">Abort</button>`:'—'}</td>`;rows.appendChild(tr);}
  rows.querySelectorAll('[data-handoff-abort]').forEach(b=>b.onclick=()=>abortHandoff(b.dataset.handoffAbort));
}
async function requestHandoff(player,destination=''){try{const h=await api('/api/handoffs/request',jsonOpts('POST',{playerId:player,destinationWorkerId:destination,reason:'dashboard physical worker transfer'}));toast(`Player transfer ${h.id}: ${h.sourceWorkerId} → ${h.destinationWorkerId}`);await refreshCore();}catch(e){toast(e.message,'error');}}
async function abortHandoff(id){try{await api(`/api/handoffs/${encodeURIComponent(id)}/abort`,{method:'POST'});toast(`Aborted ${id}`);await refreshCore();}catch(e){toast(e.message,'error');}}
if($('handoffForm'))$('handoffForm').onsubmit=e=>{e.preventDefault();requestHandoff($('handoffPlayer').value.trim(),$('handoffWorker').value.trim());};

function renderDashboard(){
  const s=state.status;if(!s)return;$('mWorkers').textContent=`${s.readyWorkers}/${s.workers}`;$('mPlayers').textContent=s.players;$('mRegions').textContent=s.regions||0;$('mLayers').textContent=s.layers||0;$('mMspt').textContent=num(s.avgMspt,1)+' ms';$('mHealth').textContent=num(s.healthPercent,1)+'%';
  renderLoadChart();renderDistribution();renderDashboardWorkers();renderRecentEvents();if(typeof renderWorldOverview==='function')renderWorldOverview();
}
function renderDashboardWorkers(){const t=$('dashboardWorkerRows');t.innerHTML='';const list=state.workers.slice(0,6);if(!list.length){t.innerHTML='<tr><td colspan="8" class="empty-cell">No workers registered yet.</td></tr>';return;}for(const w of list){const tr=document.createElement('tr');tr.innerHTML=`<td><strong>${esc(w.id)}</strong></td><td><span class="state-chip"><i class="state-dot ${esc(w.state)}"></i>${esc(w.state)}</span>${w.gracefulStopPending?`<br><small>${esc(w.gracefulStopMessage||'Graceful stop')}</small>`:''}</td><td>${w.players}/${w.maxPlayers||'—'}</td><td>${num(w.cpuPercent,0)}%</td><td>${bytes(w.heapUsed)}</td><td class="${w.mspt>45?'bad':'good'}">${num(w.mspt,1)}</td><td>${duration(w.uptimeMillis)}</td><td><button class="secondary small" data-manage="${esc(w.id)}">Manage</button></td>`;t.appendChild(tr);}t.querySelectorAll('[data-manage]').forEach(b=>b.onclick=()=>openWorker(b.dataset.manage));}
function renderRecentEvents(){const el=$('recentEvents');el.innerHTML='';const lines=state.logs.slice(-8).reverse();if(!lines.length){el.innerHTML='<div class="event-row"><time>—</time><span>No events yet</span></div>';return;}for(const line of lines){const m=line.match(/^([^ ]+) \[([^\]]+)\] (.*)$/);const row=document.createElement('div');row.className='event-row';const when=m?new Date(m[1]).toLocaleTimeString([], {hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}):'—';row.innerHTML=`<time>${esc(when)}</time><span>${esc(m?m[3]:line)}</span>`;el.appendChild(row);}}
function renderLegacyWorldOverview(){const map=$('worldOverview');map.querySelectorAll('.world-node').forEach(n=>n.remove());const groups=(state.layerGroups.groups||[]).slice(0,6);groups.forEach((g,i)=>{const d=document.createElement('div');d.className='world-node node-pos-'+i;d.innerHTML=`<strong>Cell ${g.cellX},${g.cellZ}</strong><span>${g.population} sessions · ${g.layers} layer${g.layers===1?'':'s'}</span>`;map.appendChild(d);});if(!groups.length){state.workers.slice(0,3).forEach((w,i)=>{const d=document.createElement('div');d.className='world-node node-pos-'+i;d.innerHTML=`<strong>${esc(w.id)}</strong><span>Waiting for layer sessions</span>`;map.appendChild(d);});}}

function renderLegacyLoadChart(){
  const canvas=$('loadChart'),rect=canvas.getBoundingClientRect();if(rect.width<20)return;const dpr=window.devicePixelRatio||1,w=rect.width,h=210;canvas.width=Math.floor(w*dpr);canvas.height=Math.floor(h*dpr);const ctx=canvas.getContext('2d');ctx.scale(dpr,dpr);ctx.clearRect(0,0,w,h);const left=35,right=12,top=16,bottom=24,pw=w-left-right,ph=h-top-bottom;
  ctx.strokeStyle='#223a47';ctx.lineWidth=1;ctx.fillStyle='#7790a1';ctx.font='9px system-ui';for(let i=0;i<=4;i++){const y=top+ph*i/4;ctx.beginPath();ctx.moveTo(left,y);ctx.lineTo(w-right,y);ctx.stroke();ctx.fillText((100-i*25)+'%',4,y+3);}const samples=state.metrics;if(samples.length<2)return;const series=[{color:'#4da3ff',v:s=>Math.max(0,Math.min(100,s.cpu))},{color:'#46df7b',v:s=>s.heapMax?Math.max(0,Math.min(100,100*s.heapUsed/s.heapMax)):0},{color:'#ffbe4b',v:s=>Math.max(0,Math.min(100,s.mspt))}];for(const sr of series){ctx.strokeStyle=sr.color;ctx.lineWidth=1.7;ctx.beginPath();samples.forEach((s,i)=>{const x=left+pw*i/(samples.length-1),y=top+ph*(1-sr.v(s)/100);if(i===0)ctx.moveTo(x,y);else ctx.lineTo(x,y);});ctx.stroke();}ctx.fillStyle='#7790a1';for(let i=0;i<4;i++){const idx=Math.floor((samples.length-1)*i/3),x=left+pw*idx/(samples.length-1),label=new Date(samples[idx].time).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'});ctx.fillText(label,Math.max(left,x-12),h-7);}
}
function renderDistribution(){
  const canvas=$('distributionChart'),ctx=canvas.getContext('2d'),w=canvas.width,h=canvas.height,cx=w/2,cy=h/2,r=72,inner=48;ctx.clearRect(0,0,w,h);const colors=['#4da3ff','#69c1ff','#ffcf52','#a567ff','#55df79','#ff6b72','#28d7c1','#ec8d4c'];const data=state.workers.filter(w=>w.players>0),total=data.reduce((a,w)=>a+w.players,0);let start=-Math.PI/2;if(total===0){ctx.strokeStyle='#29404d';ctx.lineWidth=r-inner;ctx.beginPath();ctx.arc(cx,cy,(r+inner)/2,0,Math.PI*2);ctx.stroke();}else data.forEach((item,i)=>{const end=start+Math.PI*2*item.players/total;ctx.beginPath();ctx.moveTo(cx,cy);ctx.arc(cx,cy,r,start,end);ctx.closePath();ctx.fillStyle=colors[i%colors.length];ctx.fill();start=end;});ctx.beginPath();ctx.arc(cx,cy,inner,0,Math.PI*2);ctx.fillStyle='#0d1822';ctx.fill();ctx.fillStyle='#eaf2fb';ctx.textAlign='center';ctx.font='700 22px system-ui';ctx.fillText(String(total),cx,cy+2);ctx.font='10px system-ui';ctx.fillStyle='#91a6b6';ctx.fillText('Players',cx,cy+18);const legend=$('distributionLegend');legend.innerHTML='';data.slice(0,7).forEach((item,i)=>{const row=document.createElement('div');row.className='legend-row';row.innerHTML=`<i class="legend-dot legend-c${i%8}"></i><span>${esc(item.id)}</span><b>${item.players}</b>`;legend.appendChild(row);});if(!data.length)legend.innerHTML='<div class="legend-row"><i class="legend-dot legend-c0"></i><span>No active players</span><b>0</b></div>';
}

function renderWorkers(){
  const s=state.status;if(!s)return;$('wTotal').textContent=s.workers;$('wRoutable').textContent=s.readyWorkers;$('wPlayers').textContent=s.players;$('wHeap').textContent=bytes(s.heapUsed)+' / '+bytes(s.heapMax);
  const filter=$('workerFilter').value.trim().toLowerCase(),t=$('workerRows');t.innerHTML='';const list=state.workers.filter(w=>!filter||w.id.toLowerCase().includes(filter)||w.state.toLowerCase().includes(filter)).sort((a,b)=>{const key=$('workerSort')?.value||'id';return key==='id'?a.id.localeCompare(b.id):Number(b[key])-Number(a[key]);});if(!list.length){t.innerHTML='<tr><td colspan="9" class="empty-cell">No matching workers.</td></tr>';return;}
  for(const w of list){const tr=document.createElement('tr');tr.innerHTML=`<td><strong>${esc(w.id)}</strong><br><small>PID ${w.pid<0?'external':w.pid} · :${w.port}${w.warm?' · warm':''}${w.pinned?' · PINNED':''}${w.managerControlled?' · manager-owned':(w.controlDetached?' · DETACHED':'')}</small></td><td><span class="state-chip"><i class="state-dot ${esc(w.state)}"></i>${esc(w.state)}</span>${w.gracefulStopPending?`<br><small>${esc(w.gracefulStopMessage||'Graceful stop')}</small>`:''}</td><td>${w.players}/${w.maxPlayers||'—'}</td><td>${num(w.mspt,1)} / ${num(w.msptP95,1)} / ${num(w.msptP99,1)}</td><td>${num(w.cpuPercent,1)}%</td><td>${bytes(w.heapUsed)} / ${bytes(w.heapMax)}</td><td>${w.loadedChunks||'—'}</td><td>${w.heartbeatAgeSeconds<0?'—':w.heartbeatAgeSeconds+'s'}</td><td><div class="controls"><button class="secondary" data-manage="${esc(w.id)}">Manage</button><button class="secondary" data-action="${w.draining?'undrain':'drain'}" data-id="${esc(w.id)}">${w.draining?'Undrain':'Drain'}</button>${w.managed?`<button class="secondary" data-action="restart" data-id="${esc(w.id)}">Restart</button>`:''}</div></td>`;t.appendChild(tr);}
  t.querySelectorAll('[data-manage]').forEach(b=>b.onclick=()=>openWorker(b.dataset.manage));t.querySelectorAll('[data-action]').forEach(b=>b.onclick=()=>workerAction(b.dataset.id,b.dataset.action));
}
$('workerFilter').addEventListener('input',renderWorkers);$('refreshWorkers').onclick=refreshCore;
async function workerAction(id,action){
  const w=state.workers.find(x=>x.id===id);
    if(action==='stop'&&!confirm(`Gracefully stop ${id}? Foundations will stop new routing and begin evacuation immediately and keep the worker alive while an in-flight migration is making progress. If the migration completion timeout expires, the stop is aborted.`))return;
  if(action==='restart'&&!confirm(`Gracefully restart ${id}? Foundations will evacuate any anchored/simulated players and Region Authority first, stop the worker only after migration commits, then relaunch it.`))return;
  if((action==='force-stop'||action==='kill')&&!confirm(`FORCE STOP ${id}? NO PLAYER MIGRATION IS GUARANTEED. This immediately terminates the worker JVM and connected players can be disconnected. Use normal Stop for migration-aware evacuation.`))return;
  if(action==='retire'&&!confirm(`Retire ${id}? The worker and its population layers will drain, then the JVM will stop when sessions reach zero.`))return;
  if(action==='delete'&&!confirm(`Delete the stopped worker directory for ${id}? Logs and audit records are preserved.`))return;
  if(action==='force-delete'){
    const typed=prompt(`Force Delete purges 3a session/layer routing state, kills the JVM if needed, and deletes the worker directory.\n\nType ${id} to continue:`,'');
    if(typed!==id)return;
  }
  try{const r=await api(`/api/workers/${encodeURIComponent(id)}/${action}`,{method:'POST'});toast(`${id}: ${action} requested${r.state?' · '+r.state:''}`,action.includes('delete')||action==='kill'||action==='force-stop'?'warn':'');if(action.includes('delete')){closeWorkerDrawer();}await refreshCore();}catch(e){toast(e.message,'error');}
}
function openWorker(id){
  state.selectedWorker=id;
  const drawer=$('workerDrawer');
  drawer.classList.remove('hidden');
  requestAnimationFrame(()=>drawer.classList.add('open'));
  renderDrawer();loadDrawerLog();
}
function closeWorkerDrawer(){
  const drawer=$('workerDrawer');
  if(drawer.classList.contains('hidden'))return;
  drawer.classList.remove('open');
  const finish=()=>{drawer.classList.add('hidden');state.selectedWorker=null;drawer.removeEventListener('transitionend',finish);};
  drawer.addEventListener('transitionend',finish);
  setTimeout(()=>{if(!drawer.classList.contains('hidden'))finish();},260);
}
$('closeDrawer').onclick=closeWorkerDrawer;
$('workerDrawer').addEventListener('click',e=>{if(e.target===$('workerDrawer'))closeWorkerDrawer();});
document.addEventListener('keydown',e=>{if(e.key==='Escape'&&!$('workerDrawer').classList.contains('hidden'))closeWorkerDrawer();});
function renderDrawer(){
  if(!state.selectedWorker)return;const w=state.workers.find(x=>x.id===state.selectedWorker);if(!w)return;
  $('drawerWorkerId').textContent=w.id;
  const stopRemain=w.gracefulStopPending?Math.max(0,(Number(w.gracefulStopDeadlineMillis||0)-Date.now())/1000):0;
  $('drawerWorkerStats').innerHTML=`<div class="stat-box"><span>State</span><strong>${esc(w.state)}</strong></div><div class="stat-box"><span>PID / Port</span><strong>${w.pid} / ${w.port}</strong></div><div class="stat-box"><span>Process</span><strong>${w.processAlive?'ALIVE':'STOPPED'}</strong></div><div class="stat-box"><span>Players</span><strong>${w.players} / ${w.maxPlayers||'—'}</strong></div><div class="stat-box"><span>Sessions</span><strong>${w.sessionCount||0}</strong></div><div class="stat-box"><span>Population Layers</span><strong>${w.layerCount||0} (${w.layerPopulation||0} assigned)</strong></div><div class="stat-box"><span>Authority Regions</span><strong>${w.authorityRegionCount||0}</strong></div><div class="stat-box"><span>MSPT avg / p95</span><strong>${num(w.mspt,1)} / ${num(w.msptP95,1)}</strong></div><div class="stat-box"><span>Heap</span><strong>${bytes(w.heapUsed)} / ${bytes(w.heapMax)}</strong></div><div class="stat-box"><span>CPU</span><strong>${num(w.cpuPercent,1)}%</strong></div><div class="stat-box"><span>Chunks / Dimensions</span><strong>${w.loadedChunks} / ${w.dimensions}</strong></div><div class="stat-box"><span>Uptime</span><strong>${duration(w.uptimeMillis)}</strong></div><div class="stat-box"><span>Java / Node</span><strong>${esc(w.javaVersion||'—')} / ${esc(w.nodeVersion||'—')}</strong></div><div class="stat-box"><span>World Seed</span><strong>${w.worldSeedKnown?String(w.worldSeed):'—'}</strong><small>${w.worldSeedKnown?(w.worldSeedMatch?'MATCH':'MISMATCH'):'Waiting for Node telemetry'}</small></div><div class="stat-box"><span>World Identity</span><strong>${esc(w.worldIdentity||'—')}</strong><small>${w.worldIdentity?(w.worldIdentityMatch?'MATCH':'MISMATCH'):'Waiting for fingerprint'}</small></div>${w.gracefulStopPending?`<div class="stat-box"><span>Graceful Stop</span><strong>${stopRemain.toFixed(1)}s · ${w.gracefulStopMigratedPlayers||0}/${w.gracefulStopInitialPlayers||0} moved</strong><small>${esc(w.gracefulStopMessage||'Evacuating')}</small></div>`:''}`;
  $('drawerDrain').textContent=w.draining?'Undrain':'Drain';$('drawerDrain').dataset.action=w.draining?'undrain':'drain';$('drawerMaintenance').textContent=w.maintenance?'Unmaintain':'Maintenance';$('drawerMaintenance').dataset.action=w.maintenance?'unmaintenance':'maintenance';
  const local=!!w.managed,alive=!!w.processAlive,safeDelete=local&&!alive&&(w.players||0)===0&&(w.sessionCount||0)===0&&(w.layerCount||0)===0&&(w.authorityRegionCount||0)===0;
  $('drawerRestart').disabled=!local||w.state==='RETIRED'||w.gracefulStopPending;$('drawerStop').disabled=!local||!alive||w.gracefulStopPending;$('drawerKill').disabled=!local||!alive;$('drawerRetire').disabled=!local||w.state==='RETIRED'||w.gracefulStopPending;$('drawerDelete').disabled=!safeDelete;$('drawerForceDelete').disabled=!local;
  $('drawerCancelStop').classList.toggle('hidden',!w.gracefulStopPending);$('drawerCancelStop').disabled=!w.gracefulStopPending;
}
async function loadDrawerLog(){if(!state.selectedWorker)return;try{const l=await api(`/api/workers/${encodeURIComponent(state.selectedWorker)}/log?limit=250`);$('drawerLog').textContent=l.lines.join('\n');$('drawerLog').scrollTop=$('drawerLog').scrollHeight;}catch(e){$('drawerLog').textContent=e.message;}}
$('drawerDrain').onclick=()=>state.selectedWorker&&workerAction(state.selectedWorker,$('drawerDrain').dataset.action);$('drawerMaintenance').onclick=()=>state.selectedWorker&&workerAction(state.selectedWorker,$('drawerMaintenance').dataset.action);$('drawerRestart').onclick=()=>state.selectedWorker&&workerAction(state.selectedWorker,'restart');$('drawerStop').onclick=()=>state.selectedWorker&&workerAction(state.selectedWorker,'stop');$('drawerCancelStop').onclick=()=>state.selectedWorker&&workerAction(state.selectedWorker,'cancel-stop');$('drawerKill').onclick=()=>state.selectedWorker&&workerAction(state.selectedWorker,'force-stop');$('drawerRetire').onclick=()=>state.selectedWorker&&workerAction(state.selectedWorker,'retire');$('drawerDelete').onclick=()=>state.selectedWorker&&workerAction(state.selectedWorker,'delete');$('drawerForceDelete').onclick=()=>state.selectedWorker&&workerAction(state.selectedWorker,'force-delete');

function renderOrphans(){
  const panel=$('orphanPanel'),rows=$('orphanRows');if(!panel||!rows)return;panel.classList.toggle('hidden',!state.orphans.length);rows.innerHTML='';
  for(const o of state.orphans){const tr=document.createElement('tr');tr.innerHTML=`<td><strong>${esc(o.workerId)}</strong></td><td>${o.pid}</td><td>${o.port>0?':'+o.port:'—'}</td><td><small>${esc(o.command||'java')}</small></td><td><button class="danger" data-orphan-kill="${o.pid}">Kill Orphan</button></td>`;rows.appendChild(tr);}
  rows.querySelectorAll('[data-orphan-kill]').forEach(b=>b.onclick=async()=>{const pid=b.dataset.orphanKill;if(!confirm(`Kill orphaned Foundations worker PID ${pid}? This is a hard process termination.`))return;try{await api(`/api/workers/orphans/${pid}/kill`,{method:'POST'});toast(`Killed orphan PID ${pid}`,'warn');await refreshCore();}catch(e){toast(e.message,'error');}});
}

function renderStartup(){if(!state.template||!state.agent||!state.startup)return;setBadge('templateState',state.template.state);setBadge('agentState',state.agent.state);setBadge('baselineState',state.startup.baselineComplete?'READY':state.startup.state==='STARTING_WORKERS'?'BOOTING':'WAITING');$('startupStateBadge').textContent=state.startup.state;let detail;if(state.template.state==='INSTALLING')detail='Installing the NeoForge server template.';else if(state.template.state==='EULA_REQUIRED')detail='NeoForge is installed. Review and accept the Minecraft EULA to continue.';else if(state.agent.state==='BUILDING')detail='Building the Foundations Cluster Node. See logs/node-agent-build.log.';else if(state.agent.state==='OUTDATED')detail=`Older Cluster Node detected (${state.agent.outdatedJar||'unknown'}). Build/install Node ${state.agent.version} before starting 4a workers.`;else if(state.agent.state==='ERROR')detail='Cluster Node build error: '+(state.agent.lastError||'unknown');else if(state.startup.state==='GATEWAY_PREPARING')detail=state.gateway?.lastError?'Gateway setup failed: '+state.gateway.lastError+' Worker creation is paused until forwarding dependencies are installed.':'Preparing gateway and Proxy Compatible Forge forwarding dependencies. See logs/velocity-bootstrap.log.';else if(state.startup.state==='STARTING_WORKERS')detail='Starting configured baseline workers.';else if(state.startup.state==='READY')detail='Startup pipeline ready. One-click launches can now restore the control plane and baseline worker set.';else detail='Startup state: '+state.startup.state;$('pipelineDetail').textContent=detail;$('setupTemplateBtn').disabled=state.template.installing||state.template.installed;$('acceptEulaBtn').classList.toggle('hidden',!(state.template.installed&&!state.template.eulaAccepted));$('buildAgentBtn').disabled=state.agent.building||state.agent.installed||!state.template.installed;const can=state.template.ready&&state.agent.ready&&state.startup.gatewayPrerequisitesReady!==false&&(!state.scaling?.memoryHardLimit||state.scaling.availableWorkerSlots>0);$('spawnBtn').disabled=!can;$('spawnWarmBtn').disabled=!can;$('quickSpawn').disabled=!can;}
function setBadge(id,text){const el=$(id);el.textContent=text;el.className=(text==='READY'||text==='ACTIVE')?'good':(text.includes('ERROR')||text==='MISSING')?'bad':(text==='EULA_REQUIRED'||text==='WAITING'||text==='OUTDATED')?'pending':'';}

function renderWorldTopology(){
  const groups=state.layerGroups.groups||[],summary=state.layers||{},regions=state.regions?.regions||[],regionChunks=Number(state.regions?.regionChunks||32);
  if($('worldGroups'))$('worldGroups').textContent=groups.length;if($('worldLayers'))$('worldLayers').textContent=summary.activeLayers||0;if($('worldAssignments'))$('worldAssignments').textContent=summary.assignments||0;if($('worldCellSize'))$('worldCellSize').textContent=(summary.cellChunks||16)+'×'+(summary.cellChunks||16);
  const host=$('worldCellGrid');if(!host)return;host.innerHTML='';if(!groups.length){host.innerHTML='<div class="empty-cell-card">No active layer cells yet. Place a session or create a layer to populate this view.</div>';return;}
  for(const g of groups){const rx=Math.floor(Number(g.chunkMinX||0)/regionChunks),rz=Math.floor(Number(g.chunkMinZ||0)/regionChunks);const r=regions.find(x=>x.dimensionId===g.dimensionId&&Number(x.regionX)===rx&&Number(x.regionZ)===rz);const authority=r?`${r.ownerWorkerId||'UNASSIGNED'} @ epoch ${r.epoch}`:'not assigned';const d=document.createElement('div');d.className='cell-card '+String(g.pressure||'').toLowerCase();d.innerHTML=`<div class="cell-head"><strong>${esc(g.dimensionId)} · ${g.cellX}, ${g.cellZ}</strong><span class="cell-pressure">${esc(g.pressure)}</span></div><div class="cell-meta"><div>Population<b>${g.population}</b></div><div>Layers<b>${g.layers}</b></div><div>Chunks X<b>${g.chunkMinX}…${g.chunkMaxX}</b></div><div>Chunks Z<b>${g.chunkMinZ}…${g.chunkMaxZ}</b></div></div><div class="notice"><b>Authority r.${rx}.${rz}</b> · ${esc(authority)}${r?` · ${esc(r.state)}`:''}</div>`;host.appendChild(d);}
}
function renderLayers(){
  const data=state.layers||{},list=data.layers||[];if($('lActive'))$('lActive').textContent=data.activeLayers||0;if($('lGroups'))$('lGroups').textContent=data.groups||0;if($('lAssigned'))$('lAssigned').textContent=data.assignments||0;if($('lThresholds'))$('lThresholds').textContent=`${data.targetPlayers||0} / ${data.splitPlayers||0}`;
  const t=$('layerRows');if(!t)return;t.innerHTML='';if(!list.length){t.innerHTML='<tr><td colspan="8" class="empty-cell">No population layers yet. The first layer is created automatically when a session is placed into a cell.</td></tr>';return;}
  for(const l of list){const tr=document.createElement('tr');const freeze=l.state==='FROZEN'?'unfreeze':'freeze',drain=l.state==='DRAINING'?'undrain':'drain';tr.innerHTML=`<td><strong>${esc(l.id)}</strong><span class="lease-id">slot ${l.slot} · epoch ${l.leaseEpoch}</span></td><td>${esc(l.dimensionId)}<br><small>${l.cellX}, ${l.cellZ}</small></td><td><strong>${esc(l.workerId)}</strong></td><td><span class="layer-chip ${esc(l.state)}">${esc(l.state)}</span><br><small>${esc(l.note||'')}</small></td><td>${l.population}</td><td>${num(l.workerMspt,1)}</td><td>${l.leaseRemainingSeconds}s<span class="lease-id" title="${esc(l.leaseId)}">${esc(l.leaseId)}</span></td><td><div class="layer-actions">${l.state!=='RETIRED'&&l.state!=='UNAVAILABLE'?`<button class="secondary" data-layer-action="${freeze}" data-layer="${esc(l.id)}">${freeze==='freeze'?'Freeze':'Unfreeze'}</button><button class="secondary" data-layer-action="${drain}" data-layer="${esc(l.id)}">${drain==='drain'?'Drain':'Undrain'}</button>`:''}${l.state!=='RETIRED'?`<button class="danger-outline" data-layer-action="retire" data-layer="${esc(l.id)}">Retire</button>`:''}</div></td>`;t.appendChild(tr);}
  t.querySelectorAll('[data-layer-action]').forEach(b=>b.onclick=()=>layerAction(b.dataset.layer,b.dataset.layerAction));
}
async function layerAction(id,action){if(action==='retire'&&!confirm(`Retire ${id}? This is only allowed when it has zero assigned sessions.`))return;try{await api(`/api/layers/${encodeURIComponent(id)}/${action}`,{method:'POST'});toast(`${id}: ${action}`);await refreshCore();}catch(e){toast(e.message,'error');}}
async function createLayerFromForm(){try{const body={worldId:$('layerWorld').value.trim(),dimensionId:$('layerDimension').value.trim(),cellX:$('layerCellX').value,cellZ:$('layerCellZ').value,workerId:$('layerWorker').value.trim()};const l=await api('/api/layers/create',jsonOpts('POST',body));toast(`Created ${l.id} on ${l.workerId}`);await refreshCore();}catch(e){toast(e.message,'error');}}
if($('layerCreateForm'))$('layerCreateForm').onsubmit=e=>{e.preventDefault();createLayerFromForm();};if($('createLayerTop'))$('createLayerTop').onclick=()=>{navigate('layers');$('layerWorld').focus();};

function healthyRegionWorkers(currentOwner=''){
  return state.workers.filter(w=>w.id!==currentOwner&&['READY','ACTIVE'].includes(w.state)&&!w.draining&&!w.maintenance&&w.heartbeatAgeSeconds>=0&&w.heartbeatAgeSeconds<15);
}
function regionWorkerOptions(currentOwner='',includeAuto=true){
  let html=includeAuto?'<option value="__auto__">Auto-select best healthy worker</option>':'';
  for(const w of healthyRegionWorkers(currentOwner)) html+=`<option value="${esc(w.id)}">${esc(w.id)} · ${esc(w.state)} · ${num(w.mspt,1)} ms · ${w.players||0} players</option>`;
  return html;
}
function bestRegionWorker(currentOwner=''){
  return healthyRegionWorkers(currentOwner).slice().sort((a,b)=>{
    const ar=a.state==='READY'?0:1,br=b.state==='READY'?0:1;if(ar!==br)return ar-br;
    const am=Number(a.mspt||0),bm=Number(b.mspt||0);if(am!==bm)return am-bm;
    return Number(a.players||0)-Number(b.players||0);
  })[0]||null;
}
function refreshRegionWorkerPicker(){const el=$('regionWorker');if(!el)return;const prior=el.value||'__auto__';const next=regionWorkerOptions('',true);if(el.dataset.optionsHtml!==next){el.innerHTML=next;el.dataset.optionsHtml=next;}if([...el.options].some(o=>o.value===prior))el.value=prior;else el.value='__auto__';}
function openRegionTransferModal(id){
  const r=(state.regions?.regions||[]).find(x=>x.id===id);if(!r){toast('Region no longer exists.','warn');return;}
  const workers=healthyRegionWorkers(r.ownerWorkerId);if(!workers.length){toast('No healthy destination workers are available.','warn');return;}
  state.pendingRegionTransfer={regionId:id,ownerWorkerId:r.ownerWorkerId,epoch:r.epoch};
  $('transferRegionId').textContent=id;$('transferRegionOwner').textContent=r.ownerWorkerId||'UNASSIGNED';$('transferRegionEpoch').textContent=r.epoch;
  const sel=$('transferRegionWorker'),best=bestRegionWorker(r.ownerWorkerId);sel.innerHTML=`<option value="__auto__">Auto-select best healthy worker${best?` (${esc(best.id)})`:''}</option>${regionWorkerOptions(r.ownerWorkerId,false)}`;sel.value='__auto__';
  $('transferRegionHint').textContent=`${workers.length} healthy destination worker${workers.length===1?'':'s'} available. Selection is held while the dashboard refreshes.`;
  $('regionTransferModal').classList.remove('hidden');
}
function closeRegionTransferModal(){state.pendingRegionTransfer=null;$('regionTransferModal').classList.add('hidden');}
async function confirmRegionTransfer(){
  const p=state.pendingRegionTransfer;if(!p)return;const region=(state.regions?.regions||[]).find(x=>x.id===p.regionId);if(!region){closeRegionTransferModal();toast('Region no longer exists.','warn');return;}
  const sel=$('transferRegionWorker'),requested=sel.value;let worker=requested==='__auto__'?bestRegionWorker(region.ownerWorkerId)?.id:requested;
  const healthy=healthyRegionWorkers(region.ownerWorkerId).some(w=>w.id===worker);if(!worker||!healthy){toast('That destination is no longer healthy. The picker has been refreshed.','warn');openRegionTransferModal(region.id);return;}
  const btn=$('confirmRegionTransfer');btn.disabled=true;btn.textContent='Transferring…';
  try{const moveSessions=$('transferRegionMoveSessions')?.checked??false;await api(`/api/regions/${encodeURIComponent(region.id)}/transfer`,jsonOpts('POST',{workerId:worker,moveSessions}));toast(moveSessions?`${region.id} → ${worker}; moving connected player backend(s)`: `${region.id} authority → ${worker}`);closeRegionTransferModal();await refreshCore();}
  catch(e){toast(e.message,'error');}
  finally{btn.disabled=false;btn.textContent='Transfer Authority';}
}

function renderRegions(){
  refreshRegionWorkerPicker();
  const data=state.regions||{},list=data.regions||[];if($('rTotal'))$('rTotal').textContent=data.total||0;if($('rActive'))$('rActive').textContent=data.active||0;if($('rSuspect'))$('rSuspect').textContent=(data.suspect||0)+(data.recovering||0);if($('rOwners'))$('rOwners').textContent=data.owners||0;if($('regionCount'))$('regionCount').textContent=list.length;
  if($('regionFencingBadge')){$('regionFencingBadge').textContent=data.writeFencingEnforced?'WRITE FENCING ACTIVE':(state.status?.persistenceEnabled?'BLOCK + BLOCKENTITY PERSISTENCE':'CONTROL PLANE');$('regionFencingBadge').className=(data.writeFencingEnforced||state.status?.persistenceEnabled)?'pill good':'pill pending';}
  if($('regionSafetyNotice')&&state.status?.persistenceEnabled){$('regionSafetyNotice').innerHTML=`<b>R19 mutation capture and confirmed saves active.</b> Durable journals track <strong>${state.status.persistenceBlocks||0}</strong> block state${(state.status.persistenceBlocks||0)===1?'':'s'} and <strong>${state.status.persistenceBlockEntities||0}</strong> BlockEntity state${(state.status.persistenceBlockEntities||0)===1?'':'s'} (revision ${state.status.persistenceRevision||0}). Full BlockEntity NBT and NeoForge attachment data are preserved for compatible vanilla/modded blocks. Workers keep independent world folders.`;}
  const t=$('regionRows');if(!t)return;t.innerHTML='';if(!list.length){t.innerHTML='<tr><td colspan="7" class="empty-cell">No authority regions yet. A region is created automatically when the first session/layer becomes active in that area.</td></tr>';return;}
  for(const r of list){const destinations=healthyRegionWorkers(r.ownerWorkerId).length;const tr=document.createElement('tr');tr.innerHTML=`<td><strong>${esc(r.id)}</strong><br><small>${esc(r.dimensionId)} · ${r.regionX}, ${r.regionZ}</small></td><td><strong>${esc(r.ownerWorkerId||'UNASSIGNED')}</strong></td><td><span class="layer-chip ${esc(r.state)}">${esc(r.state)}</span><br><small>${esc(r.note||'')}</small><br><small>${esc(r.saveStatus||'')}</small>${r.savePending?`<button class="secondary small" data-region-action="cancel-save" data-region="${esc(r.id)}">Cancel save / transfer</button>`:''}</td><td>${r.epoch}</td><td>${r.leaseRemainingSeconds}s<br><small title="${esc(r.leaseId)}">${esc((r.leaseId||'').slice(0,8))}</small></td><td>${esc(r.previousOwnerWorkerId||'—')}</td><td><div class="layer-actions region-actions"><button class="secondary" data-region-action="transfer" data-region="${esc(r.id)}" ${destinations?'':'disabled'}>Transfer…</button><button class="secondary" data-region-action="${r.state==='FROZEN'?'unfreeze':'freeze'}" data-region="${esc(r.id)}">${r.state==='FROZEN'?'Unfreeze':'Freeze'}</button><button class="danger-outline" data-region-action="expire" data-region="${esc(r.id)}">Expire Lease</button></div><small class="action-hint">${destinations?`${destinations} healthy destination${destinations===1?'':'s'}`:'No healthy destination'}</small></td>`;t.appendChild(tr);}
  t.querySelectorAll('[data-region-action]').forEach(b=>b.onclick=()=>regionAction(b.dataset.region,b.dataset.regionAction));
}
async function regionAction(id,action){if(action==='transfer'){if((state.regions?.regions||[]).find(r=>r.id===id)?.savePending){toast("This region is already saving", "error");return;}openRegionTransferModal(id);return;}let body={};if(action==='expire'&&!confirm(`Force-expire ${id}'s authority lease? Automatic failover may move it to another worker.`))return;try{await api(`/api/regions/${encodeURIComponent(id)}/${action}`,jsonOpts('POST',body));toast(`${id}: ${action}`);await refreshCore();}catch(e){toast(e.message,'error');}}
async function createRegionFromForm(){try{const body={worldId:$('regionWorld').value.trim(),dimensionId:$('regionDimension').value.trim(),regionX:$('regionX').value,regionZ:$('regionZ').value,workerId:$('regionWorker').value==='__auto__'?'':$('regionWorker').value.trim()};const r=await api('/api/regions/ensure',jsonOpts('POST',body));toast(`Authority ${r.id} → ${r.ownerWorkerId||'UNASSIGNED'}`);await refreshCore();}catch(e){toast(e.message,'error');}}
if($('regionCreateForm'))$('regionCreateForm').onsubmit=e=>{e.preventDefault();createRegionFromForm();};

function renderSessions(){const t=$('sessionRows');t.innerHTML='';$('sessionCount').textContent=state.sessions.length;if(!state.sessions.length){t.innerHTML='<tr><td colspan="9" class="empty-cell">No placed sessions yet.</td></tr>';return;}for(const s of state.sessions){const tr=document.createElement('tr');tr.innerHTML=`<td>${esc(s.playerId)}</td><td>${esc(s.partyId||'—')}</td><td><strong>${esc(s.layerId||'—')}</strong></td><td>${esc(s.dimensionId)}<br><small>${s.cellX}, ${s.cellZ}</small></td><td><strong>${esc(s.regionId||'—')}</strong><br><small>${esc(s.authorityWorkerId||'—')}${s.authorityEpoch?` @ ${s.authorityEpoch}`:''}</small></td><td><strong>${esc(s.anchorWorkerId||s.workerId)}</strong></td><td><strong>${esc(s.simulationWorkerId||s.workerId)}</strong></td><td>${age(s.ageSeconds)}</td><td><div class="layer-actions"><button class="primary small" data-handoff-player="${esc(s.playerId)}">Live Handoff</button><button class="secondary small" data-release="${esc(s.sessionId)}">Release</button></div></td>`;t.appendChild(tr);}t.querySelectorAll('[data-release]').forEach(b=>b.onclick=()=>releaseSession(b.dataset.release));t.querySelectorAll('[data-handoff-player]').forEach(b=>b.onclick=()=>{const dest=prompt(`Destination simulation worker for ${b.dataset.handoffPlayer} (leave blank for automatic):`,'');if(dest!==null)requestHandoff(b.dataset.handoffPlayer,dest.trim());});}
function renderPlayerStates(){const t=$('playerStateRows');if(!t)return;t.innerHTML='';const list=state.playerStates||[];if(!list.length){t.innerHTML='<tr><td colspan="7" class="empty-cell">No player-state snapshots yet. Join through the Gateway with the 0.0.6a Cluster Node to populate this table.</td></tr>';return;}for(const p of list){const tr=document.createElement('tr');tr.innerHTML=`<td><strong>${esc(p.name||'—')}</strong><br><small>${esc(p.uuid)}</small></td><td>${esc(p.workerId||'—')}</td><td>${esc(p.dimensionId||'—')}</td><td>${num(p.x,1)}, ${num(p.y,1)}, ${num(p.z,1)}</td><td>${esc(p.gameMode||'—')}</td><td>${p.op?'YES':'NO'}</td><td>${age(p.ageSeconds)}</td>`;t.appendChild(tr);}}
$('placementForm').onsubmit=async e=>{e.preventDefault();try{const playerId=$('playerId').value.trim(),partyId=$('partyId').value.trim(),worldId=$('placeWorld').value.trim(),dimensionId=$('placeDimension').value.trim(),cellX=$('placeCellX').value,cellZ=$('placeCellZ').value,targetLayerId=$('placeTargetLayer').value.trim();const r=await api('/api/sessions/place',jsonOpts('POST',{playerId,sessionId:playerId,partyId,worldId,dimensionId,cellX,cellZ,targetLayerId}));$('placementResult').textContent=`${r.playerId} → ${r.layerId||'no-layer'} → ${r.workerId} · region ${r.regionId||'—'} authority ${r.authorityWorkerId||'—'}@${r.authorityEpoch||0} (${r.host}:${r.port}) · ${r.reason}`;await refreshCore();}catch(err){$('placementResult').textContent='Placement failed: '+err.message;}};
async function releaseSession(id){try{await api(`/api/sessions/${encodeURIComponent(id)}/release`,{method:'POST'});await refreshCore();}catch(e){toast(e.message,'error');}}


function renderScalingRecommendation(){
  const box=$('scalingRecommendation'),text=$('scalingRecommendationText'),button=$('approveScaleUp');if(!box||!state.scaling)return;const sc=state.scaling;if(typeof renderCapacityExplanation==='function')renderCapacityExplanation(sc);const r=(sc.recommendation||'').trim(),action=(sc.recommendationAction||'').trim();box.classList.toggle('hidden',!r);text.textContent=r;if(button){button.disabled=!action;button.textContent=action==='scale_down'?'Approve Consolidation':action==='scale_up'?'Approve +1 Worker':'No Action';}
  const host=$('scalingRuntime');if(host){const memMax=Number(sc.memoryMaxWorkers)<0?'unbounded':sc.memoryMaxWorkers;const avail=Number(sc.availableWorkerSlots)<0?'∞':sc.availableWorkerSlots;host.innerHTML=`<div class="stat-box"><span>Live / Desired</span><strong>${sc.liveWorkers||0} / ${sc.desiredWorkers||0}</strong><small>${sc.players||0} players · target ${sc.targetPlayersPerWorker||0}/worker</small></div><div class="stat-box"><span>Effective JVM Cap</span><strong>${sc.effectiveMaxWorkers??'—'}</strong><small>configured ${sc.configuredMaxWorkers??'—'} · memory ${memMax}</small></div><div class="stat-box"><span>RAM Budget</span><strong>${bytes(sc.memoryUsableBytes||0)}</strong><small>${bytes(sc.systemReserveBytes||0)} reserved for OS/cluster</small></div><div class="stat-box"><span>Per Worker Reservation</span><strong>${bytes(sc.workerReservationBytes||0)}</strong><small>${avail} safe slot(s) available</small></div><div class="stat-box"><span>Cluster Seed</span><strong>${esc(sc.clusterSeed||'—')}</strong><small>numeric ${sc.expectedWorldSeed??'—'}</small></div><div class="stat-box"><span>World Identity</span><strong>${esc(sc.worldIdentity||'PENDING')}</strong><small>seed · mod versions · datapacks · worldgen configs</small></div><div class="stat-box"><span>Last Autoscaler Action</span><strong>${esc(sc.lastAction||'None yet')}</strong></div>`;}
}
$('approveScaleUp').onclick=async()=>{try{const r=await api('/api/scaling/approve',{method:'POST'});toast(r.action||'Scaling recommendation approved');await refreshCore();}catch(e){toast(e.message,'error');}};

function renderLegacyAlerts(){const host=$('alertsList');host.innerHTML='';if(!state.alerts.length){host.innerHTML='<div class="alert-card info"><b>Healthy</b><span>Cluster</span><div>No active telemetry alerts.</div></div>';return;}for(const a of state.alerts){const d=document.createElement('div');d.className='alert-card '+a.severity;d.innerHTML=`<b>${esc(a.severity)}</b><span>${esc(a.target)}</span><div>${esc(a.message)}</div>`;host.appendChild(d);}}

function renderConsoleTargets(){
  const sel=$('consoleTarget');if(!sel)return;const current=sel.value||'cluster';
  const options=[['cluster','Cluster Manager'],['all-workers','All Minecraft Workers'],...state.workers.slice().sort((a,b)=>a.id.localeCompare(b.id)).map(w=>[w.id,`${w.id} · ${w.state} · :${w.port}`])];
  sel.innerHTML='';for(const [v,label] of options){const o=document.createElement('option');o.value=v;o.textContent=label;sel.appendChild(o);}sel.value=options.some(x=>x[0]===current)?current:'cluster';
  updateConsoleHint();
}
function updateConsoleHint(){const target=$('consoleTarget')?.value||'cluster',hint=$('consoleHint');if(!hint)return;if(target==='cluster')hint.innerHTML='Cluster target: type <b>help</b> to list Foundations commands. Destructive OS-shell access is intentionally unavailable.';else if(target==='all-workers')hint.innerHTML='Broadcast target: the command is sent to every <b>locally managed running JVM</b>. Orphan/external/offline workers are skipped.';else hint.innerHTML=`${esc(target)}: native Minecraft server console. Examples: <b>list</b>, <b>say hello</b>, <b>save-all</b>, <b>whitelist list</b>.`;
}
function consoleFilterLines(lines){const f=$('consoleFilter')?.value||'all';if(f==='all')return lines;return lines.filter(line=>{const x=line.toLowerCase();if(f==='info')return x.includes('info');if(f==='warn')return x.includes('warn');if(f==='error')return x.includes('error')||x.includes('fatal')||x.includes('exception');if(f==='chat')return x.includes('[chat]')||line.includes(']: <')||x.includes('minecraftserver]: <');return true;});}
function renderConsoleOutput(){const el=$('consoleOutput');if(!el)return;let lines=[...state.consoleOutput,...state.consoleTransient];lines=consoleFilterLines(lines);el.textContent=lines.join('\n')||(state.consoleCleared?'Console view cleared. Change target or run a command to resume output.':'No console output yet.');if(!state.consolePaused)el.scrollTop=el.scrollHeight;}
async function refreshConsole(force=false){if(!$('consoleTarget'))return;renderConsoleTargets();await Promise.all([refreshConsoleOutput(force),refreshConsoleHistory()]);}
async function refreshConsoleOutput(force=false){if(state.page!=='console'&&!force)return;if((state.consolePaused||state.consoleCleared)&&!force)return;try{const target=$('consoleTarget').value,limit=Number(state.config['console.outputLines']||800);const r=await api(`/api/console/output?target=${encodeURIComponent(target)}&limit=${limit}`);state.consoleOutput=r.lines||[];renderConsoleOutput();}catch(e){if(e.status!==401){state.consoleOutput=[`Console output error: ${e.message}`];renderConsoleOutput();}}}
async function refreshConsoleHistory(){if(!$('consoleHistory'))return;try{const limit=Number(state.config['console.historyLines']||100),r=await api(`/api/console/history?limit=${limit}`);state.consoleHistory=r.entries||[];renderConsoleHistory();}catch(e){if(e.status!==401)toast('Console history failed: '+e.message,'error');}}
function renderConsoleHistory(){const host=$('consoleHistory');if(!host)return;host.innerHTML='';const list=state.consoleHistory.slice().reverse();if(!list.length){host.innerHTML='<div class="empty-cell">No commands yet.</div>';return;}for(const h of list){const d=document.createElement('div');d.className='console-history-item';d.innerHTML=`<span>${esc(new Date(h.time).toLocaleString())}</span><b>${esc(h.target)}</b><code>${esc(h.command)}</code>`;d.title=h.result||'';d.onclick=()=>{const target=$('consoleTarget');if(Array.from(target.options).some(o=>o.value===h.target))target.value=h.target;$('consoleCommand').value=h.command;state.consoleHistoryIndex=-1;updateConsoleHint();$('consoleCommand').focus();};host.appendChild(d);}}
async function executeConsoleCommand(){const input=$('consoleCommand'),target=$('consoleTarget').value,command=input.value.trim();if(!command)return;try{const r=await api('/api/console/execute',jsonOpts('POST',{target,command}));const stamp=new Date().toLocaleTimeString();state.consoleTransient.push(`[${stamp}] > ${command}`,...(r.lines||[]).map(x=>`[console] ${x}`));if(state.consoleTransient.length>120)state.consoleTransient=state.consoleTransient.slice(-120);state.consoleCleared=false;input.value='';state.consoleHistoryIndex=-1;renderConsoleOutput();await refreshConsoleHistory();setTimeout(()=>refreshConsoleOutput(true),500);}catch(e){state.consoleTransient.push(`[console ERROR] ${e.message}`);state.consoleCleared=false;renderConsoleOutput();toast(e.message,'error');}}
if($('consoleForm'))$('consoleForm').onsubmit=e=>{e.preventDefault();executeConsoleCommand();};
if($('consoleTarget'))$('consoleTarget').onchange=()=>{state.consoleTransient=[];state.consoleCleared=false;state.consoleHistoryIndex=-1;updateConsoleHint();refreshConsoleOutput(true);};
if($('consoleFilter'))$('consoleFilter').onchange=renderConsoleOutput;
if($('consolePause'))$('consolePause').onclick=()=>{state.consolePaused=!state.consolePaused;$('consolePause').textContent=state.consolePaused?'Resume':'Pause';if(!state.consolePaused){state.consoleCleared=false;refreshConsoleOutput(true);}else toast('Console output paused locally. Commands remain available.');};
if($('consoleClear'))$('consoleClear').onclick=()=>{state.consoleOutput=[];state.consoleTransient=[];state.consoleCleared=true;renderConsoleOutput();};
if($('refreshConsoleHistory'))$('refreshConsoleHistory').onclick=refreshConsoleHistory;
if($('consoleDownload'))$('consoleDownload').onclick=()=>{const target=$('consoleTarget').value,text=[...state.consoleOutput,...state.consoleTransient].join('\n');const blob=new Blob([text],{type:'text/plain'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=`foundations-console-${target}-${new Date().toISOString().replace(/[:.]/g,'-')}.log`;document.body.appendChild(a);a.click();a.remove();URL.revokeObjectURL(url);};
if($('consoleCommand'))$('consoleCommand').addEventListener('keydown',e=>{if(!['ArrowUp','ArrowDown'].includes(e.key))return;const list=state.consoleHistory.slice().reverse();if(!list.length)return;e.preventDefault();if(e.key==='ArrowUp')state.consoleHistoryIndex=Math.min(list.length-1,state.consoleHistoryIndex+1);else state.consoleHistoryIndex=Math.max(-1,state.consoleHistoryIndex-1);$('consoleCommand').value=state.consoleHistoryIndex>=0?list[state.consoleHistoryIndex].command:'';setTimeout(()=>{$('consoleCommand').selectionStart=$('consoleCommand').selectionEnd=$('consoleCommand').value.length;},0);});

function renderLogs(){if(state.logsPaused)return;const q=$('logSearch').value.trim().toLowerCase();const lines=q?state.logs.filter(x=>x.toLowerCase().includes(q)):state.logs;$('logs').textContent=lines.join('\n')||'No cluster-manager log entries yet.';$('logs').scrollTop=$('logs').scrollHeight;}
$('logSearch').addEventListener('input',renderLogs);$('refreshLogs').onclick=refreshCore;

async function refreshAudit(){if($('appShell').classList.contains('hidden'))return;try{const a=await api('/api/audit?limit=250');state.audit=a.entries||[];renderAudit();}catch(e){if(e.status!==401)toast('Audit load failed: '+e.message,'error');}}
function renderAudit(){const t=$('auditRows');t.innerHTML='';if(!state.audit.length){t.innerHTML='<tr><td colspan="5" class="empty-cell">No administrative events yet.</td></tr>';return;}for(const a of state.audit.slice().reverse()){const tr=document.createElement('tr');tr.innerHTML=`<td>${esc(new Date(a.time).toLocaleString())}</td><td>${esc(a.actor)}</td><td><strong>${esc(a.action)}</strong></td><td>${esc(a.target)}</td><td>${esc(a.detail)}</td>`;t.appendChild(tr);}}
$('refreshAudit').onclick=refreshAudit;

// ---------- R8 WebUI Acceptance Test Lab ----------
let webLiveTestBusy=false;
function setWebLiveTestBusy(busy){
  webLiveTestBusy=busy;
  for(const id of ['testSafeBtn','testFullBtn','testHandoffBtn','testChestBtn','testGrowthBtn','testStopBtn','testPlayer','testSource','testDest','testDestructive']){const el=$(id);if(el)el.disabled=busy;}
}
function blockWebTest(message){testLine('BLOCKED  '+message);testState('BLOCKED','pending');toast(message,'warn');}
async function runSingleWebTest(name,selection,confirmation,run){
  if(webLiveTestBusy)return toast('A test is already running.','warn');
  if(!$('testDestructive').checked)return blockWebTest('Enable “Allow live/destructive tests for this run” first.');
  const player=$('testPlayer').value,source=$('testSource').value,dest=$('testDest').value;
  if(selection==='player'&&(!player||!dest))return blockWebTest('Select a connected player and a destination worker.');
  if(selection==='pair'&&(!source||!dest||source===dest))return blockWebTest('Select two distinct live workers.');
  if(selection==='source'&&!source)return blockWebTest('Select a live source worker.');
  if(confirmation&&!confirm(confirmation({player,source,dest})))return;
  setWebLiveTestBusy(true);testLine(`=== ${name} ===`);testState('RUNNING','pending');
  try{const ok=await testRun(name,()=>run({player,source,dest}));testState(ok?'PASS':'FAIL',ok?'good':'bad');}
  catch(e){testLine('FAIL  '+name+' — '+e.message);testState('FAIL','bad');}
  finally{setWebLiveTestBusy(false);try{await refreshCore();}catch(e){testLine('WARN  Dashboard refresh failed: '+e.message);}}
}
function testLine(line){const out=$('testOutput');if(!out)return;if(out.textContent==='No tests run yet.')out.textContent='';out.textContent+=(out.textContent?'\n':'')+line;out.scrollTop=out.scrollHeight;}
function testState(text,kind='subtle'){const el=$('testRunState');if(!el)return;el.textContent=text;el.className='pill '+kind;}
const testSleep=ms=>new Promise(r=>setTimeout(r,ms));
function liveWorkers(){return state.workers.filter(w=>['READY','ACTIVE'].includes(w.state));}
function controlledLiveWorkers(){return liveWorkers().filter(w=>w.managerControlled===true);}
function renderTestLabOptions(){
  if(!$('testPlayer')||webLiveTestBusy)return;
  const players=[...new Set(state.sessions.map(s=>s.playerId).filter(Boolean))];
  const ids=controlledLiveWorkers().map(w=>w.id);
  const fill=(el,vals,selected)=>{const old=el.value||selected||'';el.innerHTML='';for(const v of vals){const o=document.createElement('option');o.value=o.textContent=v;el.appendChild(o);}if(vals.includes(old))el.value=old;};
  fill($('testPlayer'),players);fill($('testSource'),ids);fill($('testDest'),ids,ids[1]||ids[0]);
  if(ids.length>1&&$('testSource').value===$('testDest').value)$('testDest').value=ids.find(x=>x!==$('testSource').value)||ids[0];
}
let lastSupportFailureCapture=0;
async function captureTestFailure(){
  if(String(state.config?.['support.onTestFailure'])!=='true'||Date.now()-lastSupportFailureCapture<60000)return;
  lastSupportFailureCapture=Date.now();
  try{
    const r=await api('/api/support',jsonOpts('POST',{reason:'test-failure',report:($('testOutput')?.textContent||'').slice(-262144)}));
    if(r.accepted)testLine('INFO  Support ZIP capture started. Download from Diagnostics → Support ZIPs.');
  }catch(e){testLine('WARN  Support ZIP capture failed: '+e.message);}
}
async function testRun(name,fn){const t=performance.now();testLine(`RUN   ${name}`);try{const detail=await fn();testLine(`PASS  ${name}${detail?' — '+detail:''} (${Math.round(performance.now()-t)} ms)`);return true;}catch(e){testLine(`FAIL  ${name} — ${e.message} (${Math.round(performance.now()-t)} ms)`);await captureTestFailure();return false;}}
// HF10-UIFIX1: Manager is the authority for the required Node version.
async function testExpectedNodeVersion(){
  const node=await api('/api/node-agent/status');
  const expected=node?.version;
  if(typeof expected!=='string'||!/^0\.0\.6a\.R[1-9]\d*$/.test(expected))
    throw new Error('Expected Node version unavailable from /api/node-agent/status; refusing to assume a version');
  return expected;
}
async function webSafeSuite(requireTwo=false){
  let ok=true;testLine(requireTwo?'=== FOUNDATIONS WEBUI PREFLIGHT (FULL ACCEPTANCE) ===':'=== FOUNDATIONS WEBUI SAFE ACCEPTANCE ===');
  testLine('INFO  Safe checks verify control-plane readiness; client-visible seamlessness requires a live transfer.');
  testLine('INFO  WebUI checks HF10-UIFIX1; required Node version is read from Manager.');
  ok=(await testRun('Manager Health',async()=>{const h=await api('/api/health');if(!h.ok)throw new Error('Manager health=false');return `Manager ${h.version||'unknown'}`;}))&&ok;
  ok=(await testRun('Runtime Version',async()=>{
    const [x,h]=await Promise.all([api('/api/status'),api('/api/health')]);
    const version=String(x.version||''),match=/^0\.0\.6a\.R(\d+)$/.exec(version);
    if(!match||Number(match[1])<19)throw new Error(`runtime=${version||'unknown'}; expected 0.0.6a.R19 or a later revision on the same runtime line`);
    if(h.version!==version)throw new Error(`Manager API version mismatch: health=${h.version}; status=${version}`);
    if(Number(x.healthPercent||0)<80)throw new Error(`cluster health ${x.healthPercent}% < 80%`);
    return `${version}; health=${Number(x.healthPercent||0).toFixed(0)}%`;
  }))&&ok;
  ok=(await testRun('Gateway Transport',async()=>{
    const g=await api('/api/gateway/status');
    if(!g.running)throw new Error('Gateway is not running');
    if(!(g.processOwned||g.processAdopted)||Number(g.processPid||0)<=0)throw new Error(`proxy process ownership unknown pid=${g.processPid||0}`);
    const tv=String(g.transportVersion||'');
    if(tv!=='conduit-1.8.0-foundations-r19-guarded-replay')throw new Error(`Expected the Conduit 1.8.0 / Gateway R19 heartbeat; got ${tv||'unknown'}. Wait for startup or check the installed adapter.`);
    return `port=${g.port} pid=${g.processPid} ${g.processOwned?'owned':'adopted'} ${tv}`;
  }))&&ok;
  ok=(await testRun('Transfer Policy',async()=>{
    const g=await api('/api/gateway/status');
    const launch=g.seamlessAtLaunch,configured=g.seamlessConfigured;
    if(typeof configured!=='boolean')throw new Error('Gateway configured policy is unavailable');
    if(typeof launch!=='boolean')return 'WARN launch policy unknown for this process; inspect velocity.log startup.';
    if(launch!==configured)return 'WARN saved policy differs from proxy launch policy. A full cluster restart is required.';
    return launch?'Experimental seamless was enabled at proxy launch. A live client test is still required.':'Normal reconfiguration was enabled at proxy launch; the preconfiguration screen is expected. Enable experimental seamless in Diagnostics, save, then fully restart.';

  }))&&ok;
  ok=(await testRun('Worker Registration',async()=>{const w=await api('/api/workers');const live=(w.workers||[]).filter(x=>['READY','ACTIVE'].includes(x.state));const need=requireTwo?2:1;if(live.length<need)throw new Error(`ready=${live.length}; required=${need}`);return `ready=${live.length} total=${(w.workers||[]).length}`;}))&&ok;
  ok=(await testRun('Worker Control Ownership',async()=>{const w=await api('/api/workers');const live=(w.workers||[]).filter(x=>['READY','ACTIVE'].includes(x.state));const detached=live.filter(x=>x.managerControlled!==true).map(x=>x.id);if(detached.length&&requireTwo)throw new Error(`live but not Manager-controlled=${detached.join(', ')}`);if(detached.length)return `WARN detached=${detached.join(', ')}`;return `Manager-controlled=${live.length}`;}))&&ok;
  ok=(await testRun('Cluster Node Versions',async()=>{
    const expected=await testExpectedNodeVersion();
    const w=await api('/api/workers');
    const live=(w.workers||[]).filter(x=>['READY','ACTIVE'].includes(x.state));
    if(!live.length)throw new Error('No live workers; expected Node '+expected);
    const bad=live.filter(x=>x.nodeVersion!==expected);
    if(bad.length)throw new Error('Expected Node '+expected+'; mismatched workers: '+bad.map(x=>x.id+'='+(x.nodeVersion||'unknown')).join(', '));
    return 'expected='+expected+'; '+live.length+' live workers match';
  }))&&ok;
  ok=(await testRun('World Identity',async()=>{const w=await api('/api/workers');const live=(w.workers||[]).filter(x=>['READY','ACTIVE'].includes(x.state));const bad=live.filter(x=>((x.worldIdentity&&!x.worldIdentityMatch)||(x.worldSeedKnown&&!x.worldSeedMatch))).map(x=>x.id);if(bad.length)throw new Error('mismatch='+bad.join(', '));const schemas=[...new Set(live.map(x=>Number(x.worldIdentitySchema||1)))];if(schemas.some(x=>x!==2))throw new Error('identity schema='+schemas.join(', ')+'; expected 2');const ids=[...new Set(live.map(x=>x.worldIdentity).filter(Boolean))];if(ids.length>1)throw new Error('workers disagree on schema-2 fingerprint');return `schema=2 fingerprint=${ids[0]?ids[0].slice(0,12)+'…':'pending'}`;}))&&ok;
  ok=(await testRun('Shared Time / Weather',async()=>{
    const e=await api('/api/world/environment');
    if(!e.enabled)return 'WARN environment synchronization is disabled';
    if(!e.fresh||!e.ownerWorkerId)throw new Error('No fresh authoritative world environment');
    const workers=(await api('/api/workers')).workers||[];
    const missing=workers.filter(w=>['READY','ACTIVE'].includes(w.state)).filter(w=>!(e.workers||[]).some(x=>x.workerId===w.id&&x.synchronized)).map(w=>w.id);
    if(missing.length)throw new Error('Environment snapshot not acknowledged: '+missing.join(', '));
    return `owner=${e.ownerWorkerId}; dayTime=${e.dayTime}; rain=${e.raining}; thunder=${e.thundering}`;
  }))&&ok;
  ok=(await testRun('Persistence Journals',async()=>{const x=await api('/api/status');if(!x.persistenceEnabled)throw new Error('persistence disabled');if(String(x.persistenceProtocol||'')!=='block+blockentity-journal-v3')throw new Error(`protocol=${x.persistenceProtocol||'—'}; expected block+blockentity-journal-v3`);return `${x.persistenceProtocol} blocks=${x.persistenceBlocks||0} blockEntities=${x.persistenceBlockEntities||0}`;}))&&ok;
  ok=(await testRun('Durable Save Health',async()=>{
    const expected=await testExpectedNodeVersion();
    const w=((await api('/api/workers')).workers||[]).filter(x=>['READY','ACTIVE'].includes(x.state));
    if(!w.length)throw new Error('No live workers');
    const isCounter=n=>Number.isSafeInteger(n)&&n>=0;
    const unsupported=w.filter(x=>x.nodeVersion!==expected||!isCounter(x.pendingWrites)||!isCounter(x.failedWrites));
    if(unsupported.length)throw new Error('Save telemetry unavailable or incompatible: '+unsupported.map(x=>x.id+' ('+(x.nodeVersion||'unknown')+')').join(', ')+'; expected Node '+expected+' with non-negative integer pendingWrites/failedWrites');
    const bad=w.filter(x=>x.failedWrites>0||x.pendingWrites>0);
    if(bad.length)throw new Error(bad.map(x=>`${x.id}: ${x.pendingWrites} pending / ${x.failedWrites} failed`).join('; '));
    return `${w.length} workers on ${expected} report no unconfirmed writes`;
  }))&&ok;
  ok=(await testRun('Session Directory',async()=>{const x=await api('/api/sessions');const list=x.sessions||[];if(requireTwo&&!list.length)throw new Error('No connected player session');const bad=list.filter(z=>!(z.anchorWorkerId||z.workerId));if(bad.length)throw new Error(`${bad.length} session(s) lack anchor`);return `sessions=${list.length}`;}))&&ok;
  ok=(await testRun('Player-State API',async()=>{const x=await api('/api/player-state');return `records=${(x.records||[]).length}`;}))&&ok;
  ok=(await testRun('Scaling Guardrails',async()=>{const x=await api('/api/scaling/status');if(!x.memoryHardLimit)throw new Error('Hard RAM limit is not active');return `mode=${x.mode} effectiveMax=${x.effectiveMaxWorkers}`;}))&&ok;
  return ok;
}
async function getSession(player){const x=await api('/api/sessions');return (x.sessions||[]).find(s=>String(s.playerId||'').toLowerCase()===String(player).toLowerCase());}
async function waitPhysicalTransfer(player,dest,timeout=45000,handoffId=''){
  const end=Date.now()+timeout;let lastState='not reported';
  while(Date.now()<end){
    if(handoffId){
      const h=((await api('/api/handoffs')).handoffs||[]).find(x=>x.id===handoffId);
      if(h){
        lastState=h.state||'unknown';
        if(['ABORTED','FAILED'].includes(h.state))throw new Error(`Handoff ${handoffId} ${h.state}: ${h.error||'No reason reported; inspect the transfer timeline and velocity.log.'}`);
      }
    }
    const s=await getSession(player),anchor=s&&(s.anchorWorkerId||s.workerId),sim=s&&(s.simulationWorkerId||s.workerId);
    if(anchor===dest&&sim===dest){
      const ps=(await api('/api/player-state')).records||[];
      const rec=ps.find(r=>String(r.name||'').toLowerCase()===String(player).toLowerCase());
      if(!rec||!rec.workerId||rec.workerId===dest)return s;
    }
    await testSleep(1000);
  }
  throw new Error(`player did not physically migrate to ${dest}${handoffId?`; handoff=${handoffId}; last state=${lastState}`:''}`);
}
async function handoffTest(player,dest){const s=await getSession(player);if(!s)throw new Error(`${player} is not in session directory`);const src=s.anchorWorkerId||s.workerId;if(src===dest)throw new Error(`player is already physically anchored on ${dest}`);const h=await api('/api/handoffs/request',jsonOpts('POST',{playerId:player,destinationWorkerId:dest,reason:'WebUI physical migration acceptance'}));await waitPhysicalTransfer(player,dest,45000,h.id);return `${src} → ${dest}; anchor+simulation migrated; handoff=${h.id||'—'}`;}
async function requireLivePair(source,dest){const workers=(await api('/api/workers')).workers||[];const live=workers.filter(w=>['READY','ACTIVE'].includes(w.state)&&w.managerControlled===true).map(w=>w.id);let src=source,dst=dest;if(!live.includes(src)||!live.includes(dst)||src===dst){src=live[0]||'';dst=live.find(x=>x!==src)||'';}if(!src||!dst)throw new Error(`Need two distinct Manager-controlled live workers; controlled=${live.join(', ')||'none'}`);return [src,dst];}
async function consoleTail(worker,limit=320){const d=await api(`/api/console/output?target=${encodeURIComponent(worker)}&limit=${limit}`);return (d.lines||[]).join('\n');}
async function consoleWait(worker,command,token,timeout=12000){
  const marker='FOUNDATIONS_TEST_'+Date.now()+'_'+Math.random().toString(36).slice(2);
  await api('/api/console/execute',jsonOpts('POST',{target:worker,command:'say '+marker}));
  await api('/api/console/execute',jsonOpts('POST',{target:worker,command}));
  const end=Date.now()+timeout;let fresh="(no fresh response)";
  while(Date.now()<end){
    await testSleep(500);const latest=await consoleTail(worker,500);const at=latest.lastIndexOf(marker);
    if(at<0)continue;const lineEnd=latest.indexOf('\n',at);if(lineEnd<0)continue;
    const delta=latest.slice(lineEnd+1);fresh=delta.trim().slice(-1600)||fresh;
    if(delta.toLowerCase().includes(token.toLowerCase()))return delta;
    const failure=delta.split('\n').find(line=>/The target block is not a block entity|That position is not loaded|Could not set the block|No elements found|Unknown or incomplete command|Incorrect argument|Failed to place feature|Probe area is not loaded|Probe failed:/.test(line));
    if(failure)throw new Error(`${worker}: ${command} — ${failure.trim()}`);
  }
  throw new Error(`${worker}: ${command} — expected ${token}; fresh response: ${fresh}`);
}
async function inventorySnapshot(player,worker){
  if(!/^[A-Za-z0-9_]{1,16}$/.test(player))throw new Error('Inventory acceptance requires a valid Minecraft player name');
  const token=player+' has the following entity data:';
  const out=await consoleWait(worker,`data get entity ${player} Inventory`,token);
  const line=out.split('\n').find(x=>x.includes(token));
  const inventory=line?.slice(line.indexOf(token)+token.length).trim();
  if(!inventory||!inventory.startsWith('[')||!inventory.endsWith(']'))throw new Error('Inventory response was absent or incomplete');
  return inventory;
}
async function inventoryRoundTrip(player,source,dest){let s=await getSession(player);if(!s)throw new Error('player session missing');let anchor=s.anchorWorkerId||s.workerId;if(anchor!==source)await handoffTest(player,source);const before=await inventorySnapshot(player,source);await handoffTest(player,dest);const mid=await inventorySnapshot(player,dest);await handoffTest(player,source);const after=await inventorySnapshot(player,source);if(before!==mid||before!==after)throw new Error('Inventory NBT changed across physical worker migration round-trip');return `inventory NBT matched across physical ${source} ↔ ${dest} migration`; }
async function moveRegion(regionId,worker,timeout=40000){let rr=(await api('/api/regions')).regions?.find(r=>r.id===regionId);if(!rr)throw new Error(`${regionId} is not registered`);if(rr.ownerWorkerId!==worker)await api(`/api/regions/${encodeURIComponent(regionId)}/transfer`,jsonOpts('POST',{workerId:worker,moveSessions:false}));const end=Date.now()+timeout;while(Date.now()<end){await testSleep(750);rr=(await api('/api/regions')).regions?.find(r=>r.id===regionId);if(rr&&rr.ownerWorkerId===worker&&rr.state==='ACTIVE'){const ready=await consoleWait(worker,'foundations_region_ready '+JSON.stringify(regionId),'FOUNDATIONS_REGION_READY');if(ready.includes('FOUNDATIONS_REGION_READY true'))return;}}throw new Error(`${regionId} did not become ready on ${worker}; Manager state=${rr?.state||'missing'}; worker restoration or save seal is still pending`);}
async function preparePersistenceFixture(source,dest){
  [source,dest]=await requireLivePair(source,dest);
  const region='foundations/minecraft:overworld/r.0.0',tickets=[];
  await api('/api/regions/ensure',jsonOpts('POST',{worldId:'foundations',dimensionId:'minecraft:overworld',regionX:'0',regionZ:'0',workerId:source}));
  await moveRegion(region,source);
  const fixture={source,dest,region,tickets,created:false};
  try{
    for(const worker of [source,dest]){
      const query=await consoleWait(worker,'execute in minecraft:overworld run forceload query 0 0','marked for force loading');
      if(query.includes('is not marked')){
        await consoleWait(worker,'execute in minecraft:overworld run forceload add 0 0','to be force loaded');tickets.push(worker);
      }
    }
    await testSleep(1000);return fixture;
  }catch(e){await cleanupPersistenceFixture(fixture);throw e;}
}
async function cleanupPersistenceFixture(fixture,command){
  if(!fixture)return;
  if(fixture.created&&command){
    try{
      const owner=(await api('/api/regions')).regions?.find(r=>r.id===fixture.region&&r.state==='ACTIVE')?.ownerWorkerId;
      if(!owner)throw new Error('no ACTIVE owner; temporary blocks retained');
      await api('/api/console/execute',jsonOpts('POST',{target:owner,command:'execute in minecraft:overworld run '+command}));
    }catch(e){testLine('WARN  Fixture cleanup: '+e.message);}
  }
  for(const worker of fixture.tickets)try{await api('/api/console/execute',jsonOpts('POST',{target:worker,command:'execute in minecraft:overworld run forceload remove 0 0'}));}catch(e){testLine(`WARN  ${worker} forceload cleanup: ${e.message}`);}
}
async function chestSnapshot(worker,stage){
  try{
    const out=await consoleWait(worker,'execute in minecraft:overworld run data get block 8 300 8 Items','has the following block data:');
    const line=out.split('\n').find(x=>x.includes('has the following block data:'));
    const nbt=line.slice(line.indexOf('has the following block data:')+29).trim();
    // Ask Minecraft's NBT matcher to check the slot, ID and exact count, including
    // fields whose printed order changes. The fresh marker prevents stale success.
    const marker='FOUNDATIONS_CHEST_OK_'+Date.now()+'_'+Math.random().toString(36).slice(2);
    try{await consoleWait(worker,`execute in minecraft:overworld if data block 8 300 8 Items[{Slot:0b,id:"minecraft:diamond",count:17}] run say ${marker}`,marker,4000);}
    catch(e){throw new Error(`Expected 17 diamonds in slot 0; observed ${nbt}; ${e.message}`);}
    return nbt;
  }catch(e){throw new Error(`${stage} — ${e.message}`);}
}
async function chestTest(source,dest){
  let fixture,stage='prepare source';
  try{
    fixture=await preparePersistenceFixture(source,dest);({source,dest}=fixture);
    stage='create source chest (requires air at 8,300,8)';
    await consoleWait(source,'execute in minecraft:overworld run setblock 8 300 8 minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",count:17}]} keep','Changed the block at');fixture.created=true;
    await chestSnapshot(source,'source contents before transfer');
    stage='transfer to destination';await moveRegion(fixture.region,dest);
    await chestSnapshot(dest,'destination contents after restore');
    stage='return to source';await moveRegion(fixture.region,source);
    await chestSnapshot(source,'source contents after round-trip');
    return 'minecraft:diamond x17 in slot 0 survived acknowledged authority round-trip';
  }catch(e){throw new Error(`${stage} — ${e.message}`);}
  finally{await cleanupPersistenceFixture(fixture,'setblock 8 300 8 air replace');}
}
const growthFixtureFloors=[280,296,256,232,208,184,160];
function growthVolume(minY){return {minY,maxY:minY+23};}
function growthBounds(volume){return '0 '+volume.minY+' 0 15 '+volume.maxY+' 15';}
async function growthSnapshot(worker,volume=growthVolume(280)){
  const out=await consoleWait(worker,'execute in minecraft:overworld run foundations_region_probe '+growthBounds(volume),'FOUNDATIONS_REGION_PROBE');
  const match=out.match(/FOUNDATIONS_REGION_PROBE sha256=([a-f0-9]{64}) blocks=(\d+) logs=(\d+) leaves=(\d+)/);
  if(!match)throw new Error(worker+': incomplete growth probe response');
  return {hash:match[1],blocks:Number(match[2]),logs:Number(match[3]),leaves:Number(match[4])};
}
async function selectGrowthVolume(worker){
  for(const minY of growthFixtureFloors){
    const volume=growthVolume(minY);
    if((await growthSnapshot(worker,volume)).blocks===0)return volume;
    testLine('INFO  Tree fixture area '+growthBounds(volume)+' is occupied; checking the next area.');
  }
  throw new Error('No empty 16x24x16 tree fixture area in chunk 0,0 at Y='+growthFixtureFloors.join(', ')+'. Occupied blocks were preserved; no tree was placed.');
}
async function cleanupGrowthFixture(fixture,volume){
  if(!fixture)return;
  try{
    if(fixture.created&&volume){
      const owner=(await api('/api/regions')).regions?.find(r=>r.id===fixture.region&&r.state==='ACTIVE')?.ownerWorkerId;
      if(!owner)throw new Error('no ACTIVE owner; temporary tree retained');
      // Retain the area when its observed hash differs after a failed transfer or edit.
      const current=await growthSnapshot(owner,volume);
      if(current.blocks!==0){
        if(!fixture.treeHash||current.hash!==fixture.treeHash)throw new Error('tree area changed; blocks retained at '+growthBounds(volume));
        await api('/api/console/execute',jsonOpts('POST',{target:owner,command:'execute in minecraft:overworld run fill '+growthBounds(volume)+' minecraft:air replace'}));
        const end=Date.now()+8000;let cleared=false;
        while(Date.now()<end){if((await growthSnapshot(owner,volume)).blocks===0){cleared=true;break;}await testSleep(250);}
        if(!cleared)throw new Error('tree removal was not confirmed at '+growthBounds(volume));
      }
    }
  }finally{await cleanupPersistenceFixture(fixture);}
}
async function growthTest(source,dest){
  let fixture,volume,stage='prepare tree fixture',failure,result;
  try{
    fixture=await preparePersistenceFixture(source,dest);({source,dest}=fixture);
    volume=await selectGrowthVolume(source);
    testLine('INFO  Tree fixture selected: '+growthBounds(volume)+' on '+source+'.');
    stage='generate tree on source';
    // Recheck the whole volume immediately before requesting the feature.
    if((await growthSnapshot(source,volume)).blocks!==0)throw new Error('Selected tree area became occupied; nothing was replaced');
    await consoleWait(source,'execute in minecraft:overworld run setblock 8 '+(volume.minY+2)+' 8 minecraft:dirt keep','Changed the block at');fixture.created=true;
    await consoleWait(source,'execute in minecraft:overworld run place feature minecraft:oak 8 '+(volume.minY+3)+' 8','Placed "minecraft:oak"');
    await testSleep(1500);const before=await growthSnapshot(source,volume);if(!before.logs||!before.leaves)throw new Error('Fixture did not generate both logs and leaves');
    fixture.treeHash=before.hash;
    stage='verify destination tree';await moveRegion(fixture.region,dest);const mid=await growthSnapshot(dest,volume);
    if(before.hash!==mid.hash)throw new Error('Tree block states differ: source='+before.hash+' destination='+mid.hash);
    stage='verify returned tree';await moveRegion(fixture.region,source);const after=await growthSnapshot(source,volume);
    if(before.hash!==after.hash)throw new Error('Tree block states differ on return: source='+before.hash+' returned='+after.hash);
    result='Generated oak: '+before.logs+' logs, '+before.leaves+' leaves; all 6144 block states matched across authority round-trip at '+growthBounds(volume);
  }catch(e){failure=new Error(stage+' — '+e.message);}
  try{await cleanupGrowthFixture(fixture,volume);}
  catch(e){testLine('WARN  Tree fixture cleanup: '+e.message);if(!failure)failure=new Error('clean up tree fixture — '+e.message);}
  if(failure)throw failure;
  return result;
}
async function gracefulStopTest(worker,timeout=90000){const ws=(await api('/api/workers')).workers||[];const live=ws.filter(w=>['READY','ACTIVE'].includes(w.state));const target=live.find(w=>w.id===worker&&w.managerControlled===true);if(!target)throw new Error(`${worker} is not Manager-controlled/live`);if(live.length<2)throw new Error('Need at least two live workers');const players=Number(target.players||0);await api(`/api/workers/${encodeURIComponent(worker)}/stop`,{method:'POST'});const end=Date.now()+timeout;while(Date.now()<end){await testSleep(1000);const now=(await api('/api/workers')).workers?.find(w=>w.id===worker);if(now&&!now.processAlive&&Number(now.players||0)===0)return `evacuatedPlayers=${players}; state=${now.state}`;}throw new Error(`${worker} did not stop within ${timeout/1000}s`);}
async function restartWorkerTest(worker,timeout=90000){
  await api(`/api/workers/${encodeURIComponent(worker)}/restart`,{method:'POST'});
  const end=Date.now()+timeout;let last=null;
  while(Date.now()<end){
    await testSleep(1000);const w=(await api('/api/workers')).workers?.find(x=>x.id===worker);last=w||last;
    if(w&&['READY','ACTIVE'].includes(w.state)&&w.processAlive!==false){
      if(w.worldIdentity&&!w.worldIdentityMatch)throw new Error('worker returned with world identity mismatch');
      return `state=${w.state} node=${w.nodeVersion}`;
    }
  }
  const detail=last?`state=${last.state}; processAlive=${last.processAlive}; ready=${last.ready}; draining=${last.draining}; maintenance=${last.maintenance}`:'worker missing from inventory';
  throw new Error(`${worker} did not return READY: ${detail}`+(['DRAINED','DRAINING','MAINTENANCE'].includes(last?.state)?'. Review its operator drain/maintenance setting.':''));
}
async function runWebTests(full=false){if(webLiveTestBusy)return toast('A test is already running.','warn');if(full&&!$('testDestructive').checked){blockWebTest('Enable “Allow live/destructive tests for this run” first.');return;}const player=$('testPlayer').value,source=$('testSource').value,dest=$('testDest').value;if(full&&(!player||!source||!dest||source===dest)){toast('Full acceptance requires one connected player and two distinct live workers.','error');return;}if(full&&!confirm(`Run FULL acceptance on ${player} across ${source} and ${dest}? This will move the player, create temporary chest/tree fixtures, transfer authority, stop/evacuate ${source}, and restart it.`))return;$('testOutput').textContent='';testState('RUNNING','pending');setWebLiveTestBusy(true);try{let ok=await webSafeSuite(full);if(full&&!ok)testLine('SKIP  Live acceptance: preflight failed. No transfer or restart tests were run.');if(full&&ok){const sess=await getSession(player);if(!sess)throw new Error('Connected player session disappeared');const physical=sess.anchorWorkerId||sess.workerId;if(physical!==source)ok=(await testRun('Align Player Physical Source',()=>handoffTest(player,source)))&&ok;if(ok)ok=(await testRun('Player Inventory Physical Migration Round-Trip',()=>inventoryRoundTrip(player,source,dest)))&&ok;if(ok)ok=(await testRun('Chest / BlockEntity Transfer',()=>chestTest(source,dest)))&&ok;if(ok)ok=(await testRun('Generated Tree Persistence',()=>growthTest(source,dest)))&&ok;const beforeStop=await getSession(player);const stopAnchor=beforeStop&&(beforeStop.anchorWorkerId||beforeStop.workerId);if(ok&&!stopAnchor)throw new Error('No physical player anchor available for evacuation test');let evacuationInventory='';if(ok)evacuationInventory=await inventorySnapshot(player,stopAnchor);if(ok)ok=(await testRun('Graceful Worker Stop / Evacuation',()=>gracefulStopTest(stopAnchor)))&&ok;if(ok)ok=(await testRun('Player Survived Evacuation',async()=>{const s=await getSession(player);if(!s)throw new Error('session disappeared');const a=s.anchorWorkerId||s.workerId;if(a===stopAnchor)throw new Error(`still anchored to stopped worker ${stopAnchor}`);return `anchor=${a}`;}))&&ok;if(ok)ok=(await testRun('Evacuation Inventory Continuity',async()=>{const s=await getSession(player);if(!s)throw new Error('session disappeared');const a=s.anchorWorkerId||s.workerId;const after=await inventorySnapshot(player,a);if(evacuationInventory!==after)throw new Error('Inventory NBT changed across physical anchor evacuation');return `inventory matched on new anchor ${a}`;}))&&ok;if(ok)ok=(await testRun('Worker Restart / Rejoin',()=>restartWorkerTest(stopAnchor)))&&ok;}testState(ok?'PASS':'FAIL',ok?'good':'bad');testLine(`=== ${full?'FULL':'SAFE'} SUITE ${ok?'PASS':'FAIL'} ===`);}catch(e){testLine('FATAL '+e.message);testState('FAIL','bad');await captureTestFailure();}finally{setWebLiveTestBusy(false);try{await refreshCore();}catch(e){testLine('WARN  Dashboard refresh failed: '+e.message);}}}
if($('testSafeBtn'))$('testSafeBtn').onclick=()=>runWebTests(false);
if($('testFullBtn'))$('testFullBtn').onclick=()=>runWebTests(true);
if($('testHandoffBtn'))$('testHandoffBtn').onclick=()=>runSingleWebTest('Physical Backend Handoff','player',null,({player,dest})=>handoffTest(player,dest));
if($('testChestBtn'))$('testChestBtn').onclick=()=>runSingleWebTest('Chest / BlockEntity Transfer','pair',()=> 'Run temporary chest/BlockEntity authority-transfer test?',({source,dest})=>chestTest(source,dest));
if($('testGrowthBtn'))$('testGrowthBtn').onclick=()=>runSingleWebTest('Generated Tree Persistence','pair',()=> 'Find an empty 16x24x16 area in spawn chunk 0,0 (Y 160-319), generate a temporary oak there, verify its blocks across an authority round-trip, then remove the fixture? Occupied areas are skipped.',({source,dest})=>growthTest(source,dest));
if($('testStopBtn'))$('testStopBtn').onclick=()=>runSingleWebTest('Graceful Stop + Restore','source',({source})=>`Gracefully stop/evacuate ${source}, verify it reaches OFFLINE, then restore it?`,async({source})=>{const stopped=await gracefulStopTest(source);testLine('INFO  '+stopped);return await restartWorkerTest(source);});



async function spawn(warm){try{const r=await api('/api/workers/spawn?warm='+warm,{method:'POST'});toast('Spawned '+r.workerId+(warm?' as warm capacity':''));navigate('workers');await refreshCore();}catch(e){toast('Spawn failed: '+e.message,'error');}}
$('spawnBtn').onclick=()=>spawn(false);$('spawnWarmBtn').onclick=()=>spawn(true);$('quickSpawn').onclick=()=>spawn(false);
$('setupTemplateBtn').onclick=async()=>{try{await api('/api/template/bootstrap',{method:'POST'});toast('NeoForge template setup started.');await refreshCore();}catch(e){toast(e.message,'error');}};
$('acceptEulaBtn').onclick=async()=>{if(!confirm('By continuing, you confirm that you reviewed and agree to the Minecraft EULA. Accept it for this server template?'))return;try{await api('/api/template/eula/accept',{method:'POST'});toast('Minecraft EULA accepted for the server template.');await refreshCore();}catch(e){toast(e.message,'error');}};
$('buildAgentBtn').onclick=async()=>{try{await api('/api/node-agent/build',{method:'POST'});toast('Cluster Node build started.');await refreshCore();}catch(e){toast(e.message,'error');}};

async function restartServers(){if(!confirm('Rolling-restart all managed Minecraft server JVMs? The manager/dashboard stays online.'))return;try{const r=await api('/api/system/restart-workers',{method:'POST'});toast(`Rolling restart started for ${r.workers} worker(s).`);}catch(e){toast(e.message,'error');}}
$('restartServersTop').onclick=restartServers;$('restartServersBtn').onclick=restartServers;
$('shutdownBtn').onclick=async()=>{if(!confirm('Gracefully stop all managed Minecraft workers and shut down Foundations Cluster Runtime?'))return;try{await api('/api/system/shutdown',{method:'POST'});toast('Cluster shutdown requested.','warn');$('topClusterState').textContent='Cluster Shutting Down';}catch(e){toast(e.message,'error');}};

if($('closeRegionTransferModal'))$('closeRegionTransferModal').onclick=closeRegionTransferModal;
if($('cancelRegionTransfer'))$('cancelRegionTransfer').onclick=closeRegionTransferModal;
if($('confirmRegionTransfer'))$('confirmRegionTransfer').onclick=confirmRegionTransfer;
if($('regionTransferModal'))$('regionTransferModal').addEventListener('click',e=>{if(e.target===$('regionTransferModal'))closeRegionTransferModal();});

$('changePasswordBtn').onclick=()=>{$('passwordModal').classList.remove('hidden');$('passwordError').textContent='';};$('closePasswordModal').onclick=()=> $('passwordModal').classList.add('hidden');
$('passwordForm').onsubmit=async e=>{e.preventDefault();$('passwordError').textContent='';if($('newPassword').value!==$('newPassword2').value){$('passwordError').textContent='New passwords do not match.';return;}try{await api('/api/security/password',jsonOpts('POST',{currentPassword:$('currentPassword').value,newPassword:$('newPassword').value}));$('passwordModal').classList.add('hidden');$('currentPassword').value=$('newPassword').value=$('newPassword2').value='';toast('Password changed. Sign in again.','warn');await requireAuth();}catch(err){$('passwordError').textContent=err.message;}};
$('revokeSessionsBtn').onclick=async()=>{if(!confirm('Revoke every remembered dashboard session, including this one?'))return;try{await api('/api/security/revoke-sessions',{method:'POST'});toast('All sessions revoked. Sign in again.','warn');await requireAuth();}catch(e){toast(e.message,'error');}};

window.addEventListener('resize',()=>{if(state.status){renderLoadChart();renderDistribution();}});

(async function init(){
  try{state.auth=await rawApi('/api/auth/status');if(state.auth.authenticated)await bootAuthenticated();else{renderAuth();$('authScreen').classList.remove('hidden');}}catch(e){$('authError').textContent='Cluster Manager unavailable: '+e.message;}
  setInterval(refreshCore,3000);setInterval(refreshAudit,15000);setInterval(()=>{if(state.page==='console'&&!state.consolePaused&&!state.consoleCleared)refreshConsoleOutput();},1500);
})();
