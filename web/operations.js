/* Foundations R16 operator workspace. All data comes from the authenticated API. */
let featureCatalog=[];
const paletteColors=['#42b3ff','#4ce49b','#ad86ff','#ffca68','#4ad5d3','#f792a6','#afcf70'];
const mapView={zoom:1,x:0,z:0,dimension:'',selected:null};

function downloadFile(name,content,type='text/plain'){
  const url=URL.createObjectURL(new Blob([content],{type}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
function csv(rows){return rows.map(row=>row.map(value=>{let s=String(value??'');if(/^[=+@\-\t\r]/.test(s))s="'"+s;return '"'+s.replaceAll('"','""')+'"';}).join(',')).join('\r\n');}
function exportRows(name,rows,fields){downloadFile(name,csv([fields,...rows.map(r=>fields.map(f=>r[f]))]),'text/csv');}
function stat(label,value,sub=''){return `<article class="metric-card"><div><strong>${esc(value)}</strong><span>${esc(label)}</span><small>${esc(sub)}</small></div></article>`;}
function row(label,value){return `<div><span>${esc(label)}</span><b>${esc(value)}</b></div>`;}
function unknownBytes(value){return value==null?'Unavailable':bytes(value);}
function renderOperations(){
  const banner=$('connectionBanner'),stale=state.staleEndpoints||[],shutdown=state.status?.lifecycle==='SHUTTING_DOWN';
  banner.classList.toggle('hidden',!stale.length&&!shutdown);
  banner.textContent=shutdown?'Cluster shutdown in progress. Automatic recovery is paused.':stale.length?`Connection degraded · ${stale.join(', ')} unavailable. Last status: ${state.lastFresh?new Date(state.lastFresh).toLocaleTimeString():'not received'}. Displayed readings may be stale.`:'';
  document.body.classList.toggle('connection-stale',stale.includes('status'));
  if(stale.includes('status'))$('topClusterState').textContent='Manager Unreachable';
  const host=state.host;
  if(host){
    $('hostMetrics').innerHTML=stat('Host CPU',host.cpuPercent==null?'Unavailable':num(host.cpuPercent)+'%',host.processors+' logical processors')+stat('Available RAM',unknownBytes(host.memoryFree),unknownBytes(host.memoryTotal)+' total')+stat('Free Storage',unknownBytes(host.diskFree),unknownBytes(host.diskTotal)+' total')+stat('Manager Uptime',duration(host.managerUptimeMillis),'Java '+host.javaVersion);
    $('hostOs').textContent=host.os+' · '+host.arch;
    $('hostDetails').innerHTML=row('Node','local')+row('Manager heap',bytes(host.managerHeapUsed)+' / '+bytes(host.managerHeapMax))+row('Manager threads',host.managerThreads)+row('Swap available',unknownBytes(host.swapFree))+row('Network throughput','Not reported')+row('Disk latency / temperature','Not reported');
    const sc=state.scaling||{};
    $('hostCapacity').innerHTML=row('Live / desired workers',`${sc.liveWorkers||0} / ${sc.desiredWorkers||0}`)+row('Safe worker limit',sc.effectiveMaxWorkers??'—')+row('Available worker slots',sc.availableWorkerSlots??'—')+row('Per-worker reservation',bytes(sc.workerReservationBytes))+row('Pinned workers',state.workers.filter(w=>w.pinned).length)+row('Migration heap ceiling',(state.config['placement.maxHeapPercent']||85)+'%');
  }
  const w=state.workers.find(w=>w.id===state.selectedWorker);
  if(w){$('drawerPin').textContent=w.pinned?'Unpin online':'Pin online';$('drawerPin').title='Pinned workers are excluded from automatic scale-down';}
  renderSaveHealth();renderEnvironment();renderSwitchPolicy();renderReadiness();renderTrace();renderWorldOverview();if(state.page==='world')renderRegionMap();
}
function renderReadiness(){
  const checks=[
    ['Manager',state.status?.version=== '0.0.6a.R19'?'ready':'attention',state.status?.version||'Unavailable'],
    ['Worker agents',state.workers.length&&state.workers.filter(w=>w.ready).every(w=>w.nodeVersion===state.agent?.version)?'ready':'inspect',`${state.status?.readyWorkers||0} routable / ${state.workers.length} registered`],
    ['Gateway',state.status?.gatewayRunning?'ready':'attention',state.gateway?.transport||'Gateway offline'],
    ['Authority fencing',state.status?.regionWriteFencing?'ready':'attention',`${state.status?.activeRegions||0} active regions · ${state.status?.suspectRegions||0} suspect`],
    ['Physical migration','live test','Anchor + simulation must both move'],
    ['Seamless NeoForge','unproven','Inspect launch policy; client seamlessness still requires live observation'],
    ['Shutdown guard',state.status?.lifecycle==='SHUTTING_DOWN'?'active':'ready','Recovery suppression across coordinators'],
    ['State backup','offline tool','BACKUP_CLUSTER.bat · checksums + verify']
  ];
  $('readinessChecks').innerHTML=checks.map(([label,status,detail])=>`<article class="readiness-card"><div><strong>${esc(label)}</strong><span class="pill ${status==='ready'?'good':'pending'}">${esc(status.toUpperCase())}</span></div><p>${esc(detail)}</p></article>`).join('');
}
function renderTrace(){
  const q=$('traceSearch').value.toLowerCase();
  const events=(state.trace||[]).slice().reverse().filter(e=>JSON.stringify(e).toLowerCase().includes(q));
  $('transferTraceRows').innerHTML=events.map(e=>`<tr><td>${esc(new Date(e.time).toLocaleTimeString())}</td><td>${esc(e.handoffId)}</td><td><span class="pill">${esc(e.stage)}</span></td><td>${e.elapsedMillis} ms</td><td class="wrap-cell">${esc(e.detail)}</td></tr>`).join('')||'<tr><td colspan="5" class="empty-cell">No matching transfer events. Request a physical transfer from Players or Test Lab.</td></tr>';
}
function renderAlerts(){
  const hide=$('hideAcknowledged')?.checked,list=state.alerts.filter(a=>!hide||!a.acknowledgedBy);
  $('alertsList').innerHTML=list.map(a=>`<div class="alert-card ${esc(a.severity)}"><b>${esc(a.severity)}</b><span>${esc(a.target)}</span><div>${esc(a.message)}<small>${a.firstSeen?'Since '+esc(new Date(a.firstSeen).toLocaleString()):''}</small></div>${a.acknowledgedBy?`<span class="acknowledged">Acknowledged by ${esc(a.acknowledgedBy)}</span>`:`<button class="secondary small" data-ack="${esc(a.id)}">Acknowledge</button>`}</div>`).join('')||'<div class="empty-state">No '+(hide?'unacknowledged':'active')+' incidents.</div>';
  $('alertsList').querySelectorAll('[data-ack]').forEach(b=>b.onclick=async()=>{try{await api('/api/alerts/acknowledge',jsonOpts('POST',{id:b.dataset.ack}));await refreshCore();}catch(e){toast(e.message,'error');}});
}
function renderLoadChart(){
  const canvas=$('loadChart'),rect=canvas.getBoundingClientRect();if(rect.width<20)return;
  const width=rect.width,height=194,dpr=devicePixelRatio||1;canvas.width=width*dpr;canvas.height=height*dpr;canvas.style.height=height+'px';
  const ctx=canvas.getContext('2d');ctx.scale(dpr,dpr);const left=34,right=35,top=16,bottom=26,pw=width-left-right,ph=height-top-bottom;
  const samples=state.metrics||[],maxMspt=Math.max(50,...samples.map(s=>Math.ceil(s.mspt/25)*25));
  ctx.font='10px system-ui';ctx.lineWidth=1;
  for(let i=0;i<=4;i++){let y=top+ph*i/4;ctx.strokeStyle='#263641';ctx.beginPath();ctx.moveTo(left,y);ctx.lineTo(width-right,y);ctx.stroke();ctx.fillStyle='#93a4b1';ctx.textAlign='right';ctx.fillText((100-i*25)+'%',left-5,y+3);ctx.textAlign='left';ctx.fillStyle='#d6b775';ctx.fillText(Math.round(maxMspt*(1-i/4)),width-right+6,y+3);}
  if(samples.length<2){ctx.fillStyle='#91a6b6';ctx.textAlign='center';ctx.fillText('Collecting live samples…',width/2,height/2);return;}
  const minTime=samples[0].time,span=samples.at(-1).time-minTime||1;
  const series=[{color:'#42adff',value:s=>s.cpu/100},{color:'#54df8f',value:s=>s.heapMax?s.heapUsed/s.heapMax:0},{color:'#ffcb5c',value:s=>s.mspt/maxMspt}];
  for(const sr of series){ctx.strokeStyle=sr.color;ctx.lineWidth=1.7;ctx.beginPath();samples.forEach((s,i)=>{const x=left+pw*(s.time-minTime)/span,y=top+ph*(1-Math.max(0,Math.min(1,sr.value(s))));if(!i)ctx.moveTo(x,y);else ctx.lineTo(x,y);});ctx.stroke();}
  ctx.textAlign='center';ctx.fillStyle='#91a6b6';for(let i=0;i<4;i++){const idx=Math.floor((samples.length-1)*i/3);ctx.fillText(new Date(samples[idx].time).toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'}),left+pw*i/3,height-6);}
  $('metricsFootnote').textContent=`${samples.length} samples · observed ${new Date(minTime).toLocaleString()} onward · history resets on restart`;
}
function regionData(){const regions=state.regions?.regions||[];if(!regions.some(r=>(r.worldId+'/'+r.dimensionId)===mapView.dimension))mapView.dimension=regions.length?regions[0].worldId+'/'+regions[0].dimensionId:'';return regions.filter(r=>(r.worldId+'/'+r.dimensionId)===mapView.dimension);}
function drawMap(canvas,compact=false){
  const rect=canvas.getBoundingClientRect();if(rect.width<20)return;const width=rect.width,height=compact?228:400,dpr=devicePixelRatio||1;canvas.width=width*dpr;canvas.height=height*dpr;canvas.style.height=height+'px';const ctx=canvas.getContext('2d');ctx.scale(dpr,dpr);ctx.fillStyle='#0a171c';ctx.fillRect(0,0,width,height);
  const data=regionData(),xs=data.map(r=>r.regionX),zs=data.map(r=>r.regionZ);const cx=xs.length?(Math.min(...xs)+Math.max(...xs))/2:0,cz=zs.length?(Math.min(...zs)+Math.max(...zs))/2:0;
  const scale=Math.min(84,(width-90)/(Math.max(2,...xs.map(x=>Math.abs(x-cx)*2))+1),(height-70)/(Math.max(2,...zs.map(z=>Math.abs(z-cz)*2))+1))*(compact?1:mapView.zoom);
  const ox=width/2-(cx+(compact?0:mapView.x))*scale,oz=height/2-(cz+(compact?0:mapView.z))*scale;
  ctx.strokeStyle='#17333a';ctx.lineWidth=1;for(let x=ox%scale;x<width;x+=scale){ctx.beginPath();ctx.moveTo(x,0);ctx.lineTo(x,height);ctx.stroke();}for(let y=oz%scale;y<height;y+=scale){ctx.beginPath();ctx.moveTo(0,y);ctx.lineTo(width,y);ctx.stroke();}
  const owners=[...new Set(data.map(r=>r.ownerWorkerId))].sort();const hits=[];
  data.forEach(r=>{const x=ox+(r.regionX-.5)*scale,y=oz+(r.regionZ-.5)*scale,color=paletteColors[Math.max(0,owners.indexOf(r.ownerWorkerId))%paletteColors.length];ctx.fillStyle=color+'35';ctx.fillRect(x+2,y+2,scale-4,scale-4);ctx.strokeStyle=r.state==='ACTIVE'?color:'#ff807e';ctx.lineWidth=r.id===mapView.selected?3:1;ctx.strokeRect(x+2,y+2,scale-4,scale-4);if(scale>35){ctx.fillStyle='#e2edf1';ctx.font='10px system-ui';ctx.textAlign='center';ctx.fillText(`${r.regionX}, ${r.regionZ}`,x+scale/2,y+scale/2);if(scale>65){ctx.fillStyle=color;ctx.font='9px system-ui';ctx.fillText(r.ownerWorkerId||'unassigned',x+scale/2,y+scale/2+15);}}hits.push({x,y,size:scale,r});});
  if(!data.length){ctx.fillStyle='#acc2cd';ctx.font='12px system-ui';ctx.textAlign='center';ctx.fillText('No region authority recorded yet',width/2,height/2);ctx.font='10px system-ui';ctx.fillText('Ensure a region or connect a worker to begin',width/2,height/2+21);}
  ctx.fillStyle='#94b4c0';ctx.font='10px system-ui';ctx.textAlign='left';ctx.fillText('N / −Z ↑',12,18);ctx.fillText(`${data.length} regions · ${owners.length} owners`,12,height-12);
  canvas.onclick=e=>{const x=e.clientX-rect.left,y=e.clientY-rect.top,hit=hits.find(h=>x>=h.x&&x<=h.x+h.size&&y>=h.y&&y<=h.y+h.size);if(compact){navigate('world');renderRegionMap();return;}if(hit){mapView.selected=hit.r.id;$('mapSelection').textContent=`${hit.r.id} · ${hit.r.state} · owner ${hit.r.ownerWorkerId||'none'} · epoch ${hit.r.epoch}`;renderRegionMap();}};
}
function renderWorldOverview(){drawMap($('overviewMap'),true);}
function renderRegionMap(){
  const dims=[...new Set((state.regions?.regions||[]).map(r=>r.worldId+'/'+r.dimensionId))];const selector=$('mapDimension');const signature=dims.join('|');if(!dims.includes(mapView.dimension))mapView.dimension=dims[0]||'';if(selector.dataset.signature!==signature){selector.innerHTML=dims.map(d=>`<option>${esc(d)}</option>`).join('');selector.value=mapView.dimension;selector.dataset.signature=signature;}
  drawMap($('regionMap'));
}
function renderFeatures(){
  const q=$('featureSearch').value.toLowerCase(),status=$('featureStatusFilter').value;
  const list=featureCatalog.filter(f=>(!status||f.status===status)&&JSON.stringify(f).toLowerCase().includes(q));
  $('featureSummary').textContent=`${list.length} of ${featureCatalog.length} design areas · implemented means code exists; acceptance items still require live game testing.`;
  $('featureCards').innerHTML=list.map(f=>`<article class="feature-card"><div><span class="pill stage-${esc(f.status)}">${esc(f.status)}</span><small>${esc(f.phase)}</small></div><h3>${esc(f.name)}</h3><p>${esc(f.detail)}</p><div class="feature-next">${esc(f.next)}</div></article>`).join('')||'<div class="empty-state">No matching features.</div>';
}
function openPalette(){const d=$('commandPalette');d.showModal();$('commandSearch').value='';renderPalette();$('commandSearch').focus();}
function renderPalette(){
  const q=$('commandSearch').value.toLowerCase(),items=[...$$('.nav-item').map(b=>({name:b.textContent.trim(),hint:'Page',open:()=>navigate(b.dataset.page)})),...state.workers.map(w=>({name:w.id,hint:w.state+(w.pinned?' · pinned':''),open:()=>openWorker(w.id)}))].filter(i=>i.name.toLowerCase().includes(q));
  const host=$('commandResults');host.innerHTML='';items.slice(0,25).forEach(item=>{let b=document.createElement('button');b.type='button';b.className='palette-result';b.innerHTML=`<span>${esc(item.name)}</span><small>${esc(item.hint)}</small>`;b.onclick=()=>{$('commandPalette').close();item.open();};host.append(b);});
  if(!items.length)host.textContent='No matches';
}
function initOperations(){
  const world=$('page-world'),panel=document.createElement('section');panel.className='panel map-panel';panel.innerHTML='<div class="panel-title"><strong>Region Ownership</strong><div class="map-controls"><select id="mapDimension" aria-label="Map dimension"></select><button id="mapZoomOut" aria-label="Zoom out">−</button><button id="mapZoomIn" aria-label="Zoom in">+</button><button id="mapReset">Fit</button></div></div><canvas id="regionMap" tabindex="0" aria-label="Region ownership map, use arrow keys to pan"></canvas><div id="mapSelection" class="panel-caption">Select a cell to inspect authority. Arrow keys pan; + / − zoom. Map shows ownership, not terrain.</div>';world.insertBefore(panel,world.children[1]);
  $('mapDimension').onchange=e=>{mapView.dimension=e.target.value;renderRegionMap();renderWorldOverview();};
  $('mapZoomIn').onclick=()=>{mapView.zoom=Math.min(5,mapView.zoom*1.25);renderRegionMap();};$('mapZoomOut').onclick=()=>{mapView.zoom=Math.max(.25,mapView.zoom/1.25);renderRegionMap();};$('mapReset').onclick=()=>{Object.assign(mapView,{zoom:1,x:0,z:0});renderRegionMap();};
  $('regionMap').onkeydown=e=>{const delta={ArrowLeft:[-1,0],ArrowRight:[1,0],ArrowUp:[0,-1],ArrowDown:[0,1]}[e.key];if(delta){e.preventDefault();mapView.x+=delta[0];mapView.z+=delta[1];renderRegionMap();}};
  $('metricsRange').onchange=refreshCore;$('workerSort').onchange=renderWorkers;
  $('drawerPin').onclick=()=>{const w=state.workers.find(w=>w.id===state.selectedWorker);if(w)workerAction(w.id,w.pinned?'unpin':'pin');};
  $('drawerConsole').onclick=()=>{const id=state.selectedWorker;closeWorkerDrawer();navigate('console');$('consoleTarget').value=id;refreshConsole(true);};
  $('drawerHistory').onclick=async()=>{const id=state.selectedWorker;try{const data=await api('/api/metrics?workerId='+encodeURIComponent(id));if(id!==state.selectedWorker)return;const canvas=$('workerHistoryChart');canvas.classList.remove('hidden');const w=canvas.clientWidth||480,h=140;canvas.width=w*devicePixelRatio;canvas.height=h*devicePixelRatio;const c=canvas.getContext('2d');c.scale(devicePixelRatio,devicePixelRatio);c.fillStyle='#0a171f';c.fillRect(0,0,w,h);c.fillStyle='#b8cbd6';c.font='11px system-ui';c.fillText('MSPT avg / p95 / p99 · up to one hour',14,20);const values=data.samples||[];if(values.length<2){c.fillText('Collecting worker telemetry…',14,65);return;}const max=Math.max(50,...values.map(v=>v.p99));['mspt','p95','p99'].forEach((key,k)=>{c.strokeStyle=['#4ce49b','#42b3ff','#ffca68'][k];c.beginPath();values.forEach((v,i)=>{const x=14+i*(w-28)/(values.length-1),y=120-v[key]/max*85;i?c.lineTo(x,y):c.moveTo(x,y);});c.stroke();});}catch(e){toast(e.message,'error');}};
  $('exportWorkers').onclick=()=>exportRows('Foundations-workers.csv',state.workers,['id','state','players','mspt','msptP95','msptP99','cpuPercent','heapUsed','heapMax','pinned']);
  $('exportAudit').onclick=()=>exportRows('Foundations-audit.csv',state.audit,['time','actor','action','target','detail']);
  $('auditSearch').oninput=renderAudit;$('traceSearch').oninput=renderTrace;
  $('exportTrace').onclick=()=>downloadFile('Foundations-transfer-trace.json',JSON.stringify(state.trace||[],null,2),'application/json');
  $('downloadDiagnostics').onclick=()=>{location.href='/api/diagnostics/download';};
  $('pauseLogs').onclick=()=>{state.logsPaused=!state.logsPaused;$('pauseLogs').textContent=state.logsPaused?'Resume':'Pause';if(!state.logsPaused)refreshCore();};
  $('downloadLogs').onclick=()=>downloadFile('Foundations-visible-log.txt',$('logs').textContent);
  $('hideAcknowledged').onchange=renderAlerts;
  $('featureSearch').oninput=renderFeatures;$('featureStatusFilter').onchange=renderFeatures;
  fetch('/features.json').then(r=>r.json()).then(data=>{featureCatalog=data;renderFeatures();}).catch(e=>{$('featureCards').textContent='Feature catalog unavailable: '+e.message;});
  $('commandPaletteBtn').onclick=openPalette;$('commandSearch').oninput=renderPalette;
  $('commandPalette').addEventListener('click',e=>{if(e.target===$('commandPalette'))$('commandPalette').close();});
  $('commandPalette').addEventListener('keydown',e=>{const buttons=$$('#commandResults button'),idx=buttons.indexOf(document.activeElement);if(e.key==='ArrowDown'||e.key==='ArrowUp'){e.preventDefault();buttons[(idx+(e.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length]?.focus();}if(e.key==='Enter'&&document.activeElement===$('commandSearch')){e.preventDefault();buttons[0]?.click();}});
  document.addEventListener('keydown',e=>{if((e.ctrlKey||e.metaKey)&&e.key==='k'){e.preventDefault();openPalette();}});
  document.addEventListener('input',e=>{if(e.target.matches('[data-config]'))e.target.closest('form').dataset.dirty='true';});
  window.addEventListener('beforeunload',e=>{if(document.querySelector('form[data-dirty="true"]')){e.preventDefault();e.returnValue='';}});
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)refreshCore();});
  window.addEventListener('resize',()=>{renderWorldOverview();if(state.page==='world')renderRegionMap();});
  document.addEventListener('click',e=>{if(e.target.closest('[data-page="world"],[data-page-jump="world"]'))requestAnimationFrame(renderRegionMap);});
  // Keyboard focus stays within the visible modal/drawer.
  document.addEventListener('keydown',e=>{if(e.key!=='Tab')return;const modal=document.querySelector('.modal:not(.hidden)')||document.querySelector('.drawer.open');if(!modal)return;const focus=[...modal.querySelectorAll('button,input,select,a,textarea,[tabindex]')].filter(el=>!el.disabled&&el.getClientRects().length);if(!focus.length)return;let first=focus[0],last=focus.at(-1);if(e.shiftKey&&(document.activeElement===first||!modal.contains(document.activeElement))){e.preventDefault();last.focus();}else if(!e.shiftKey&&(document.activeElement===last||!modal.contains(document.activeElement))){e.preventDefault();first.focus();}});
  $$('canvas').forEach(c=>{if(!c.hasAttribute('role'))c.setAttribute('role','img');});
}
initOperations();


function renderCapacityExplanation(sc){
  const box=$('scalingCapacityNotice');if(!box)return;
  const gib=value=>`${(Number(value)/1073741824).toFixed(1)} GiB`;
  const known=Number(sc.memoryBudgetBytes)>0,blocked=sc.memoryHardLimit&&Number(sc.effectiveMaxWorkers)===0;
  box.classList.toggle('capacity-blocked',blocked);
  const source=sc.memoryBudgetSource==='configured'?'Configured budget':'OS-visible physical RAM';
  const heading=blocked?'No worker fits the saved memory settings':known?`Saved memory settings allow ${sc.effectiveMaxWorkers} worker(s)`:'Physical RAM detection unavailable';
  const formula=known?`${source}: ${gib(sc.memoryBudgetBytes)} − system reserve: ${gib(sc.systemReserveBytes)} = ${gib(sc.memoryUsableBytes)} for workers. Each worker reserves ${gib(sc.workerReservationBytes)} (heap plus overhead, unless overridden).`:'Set an explicit RAM budget for this host to calculate a memory limit.';
  const next=blocked?`One worker needs at least ${gib(Number(sc.systemReserveBytes)+Number(sc.workerReservationBytes))} total budget with these settings. Review the system reserve below or Worker JVM Profile in Settings. RAM budget 0 means automatic detection, not zero RAM.`:'RAM budget 0 means automatic detection. This shows saved settings; changes take effect after Save Scaling Settings.';
  box.innerHTML=`<strong>${esc(heading)}</strong><p>${esc(formula)}</p><p>${esc(next)}</p>`;
}

function renderSwitchPolicy(){
  const el=$('switchPolicyStatus');if(!el)return;
  const g=state.gateway||{},launch=g.seamlessAtLaunch;
  el.textContent=typeof launch!=='boolean'?'Proxy launch policy unavailable. Check the startup section of velocity.log.':launch!==g.seamlessConfigured?'Restart required: saved policy differs from the running proxy launch policy.':launch?'Experimental seamless enabled at proxy launch. Client behavior still needs live acceptance.':'Normal reconfiguration is active. Enable the checkbox, save, and fully restart the cluster to test seamless switching.';
}
function renderEnvironment(){
  const el=$('environmentStatus');if(!el)return;const e=state.environment;
  if(!e){el.textContent='Environment status unavailable';return;}
  el.textContent=!e.enabled?'Synchronization disabled':!e.fresh?'Waiting for a fresh environment owner':`Owner: ${e.ownerWorkerId} · Day ${Math.floor(e.dayTime/24000)} · Time ${e.dayTime%24000} ticks · ${e.thundering?'Thunder':e.raining?'Rain':'Clear'}`;
  const rows=$('environmentWorkers');rows.innerHTML=(e.workers||[]).map(w=>`<tr><td>${esc(w.workerId)}</td><td>${w.synchronized?'Synchronized':'Waiting / stale'}</td><td>${w.dayTime}</td><td>${w.raining?'Rain':'Clear'}</td></tr>`).join('')||'<tr><td colspan="4">No environment reports yet.</td></tr>';
}

function renderSaveHealth(){
  const el=$('saveHealthRows');if(!el)return;
  el.innerHTML=(state.workers||[]).map(w=>{const fresh=['READY','ACTIVE'].includes(w.state)&&w.heartbeatAgeSeconds<10;const label=!fresh?'Unavailable / stale':w.failedWrites?'Save failure':w.pendingWrites?'Saving':w.lastDurableWrite?'Acknowledged':'Idle — no writes yet';return `<tr><td>${esc(w.id)}</td><td>${esc(label)}</td><td>${w.pendingWrites??'—'}</td><td>${w.failedWrites??'—'}</td><td>${w.lastDurableWrite?esc(new Date(w.lastDurableWrite).toLocaleTimeString()):'No acknowledgement yet'}</td></tr>`;}).join('')||'<tr><td colspan="5">No workers registered</td></tr>';
}
function supportSummary(){return JSON.stringify({generatedAt:new Date().toISOString(),version:state.status?.version,proxy:state.gateway?.transport,workers:(state.workers||[]).map(w=>({id:w.id,state:w.state,node:w.nodeVersion,pending:w.pendingWrites,failed:w.failedWrites,lastDurableWrite:w.lastDurableWrite})),regions:(state.regions?.regions||[]).map(r=>({id:r.id,owner:r.ownerWorkerId,state:r.state,save:r.saveStatus})),transfers:(state.trace||[]).slice(-20)},null,2);}
if($('copySupportSummary'))$('copySupportSummary').onclick=async()=>{try{await navigator.clipboard.writeText(supportSummary());toast('Support summary copied');}catch{downloadFile('foundations-support-summary.json',supportSummary(),'application/json');}};
if($('exportTestReport'))$('exportTestReport').onclick=()=>downloadFile('foundations-test-output.txt',$('testOutput').textContent);
if($('normalSwitchPolicy'))$('normalSwitchPolicy').onclick=async()=>{try{await api('/api/config',jsonOpts('PUT',{'gateway.seamless.enabled':'false'}));await loadConfig();toast('Normal transfers saved. Fully restart the cluster to apply.');}catch(e){toast(e.message,'error');}};


let supportRefreshPending=false;
async function refreshSupportArchives(){
  if(supportRefreshPending)return;
  supportRefreshPending=true;
  try{
    const data=await api('/api/support');
    $('createSupportZip').disabled=data.busy;
    $('supportCaptureStatus').textContent=data.busy?'Compressing support ZIP…':data.lastError?'Capture failed: '+data.lastError:data.lastCreated?'Latest capture: '+data.lastCreated:'Ready to capture runtime and logs.';
    $('supportArchiveRows').innerHTML=(data.archives||[]).map(a=>`<tr><td>${esc(a.name)}</td><td>${bytes(a.bytes)}</td><td><a class="secondary small" href="/api/support/download?name=${encodeURIComponent(a.name)}" download>Download ZIP</a></td></tr>`).join('')||'<tr><td colspan="3" class="empty-cell">No saved support ZIPs yet.</td></tr>';
    return data;
  }catch(e){$('supportCaptureStatus').textContent='Support captures unavailable: '+e.message;}
  finally{supportRefreshPending=false;}
}
$('createSupportZip').onclick=async()=>{
  const button=$('createSupportZip');button.disabled=true;
  try{
    await api('/api/support',jsonOpts('POST',{reason:'manual',report:($('testOutput')?.textContent||'').slice(-262144)}));
    $('supportCaptureStatus').textContent='Compressing support ZIP…';
    const deadline=Date.now()+120000;
    while(Date.now()<deadline){
      await new Promise(resolve=>setTimeout(resolve,750));
      const data=await refreshSupportArchives();
      if(data&&!data.busy){if(data.lastError)throw new Error(data.lastError);if(data.lastCreated)location.href='/api/support/download?name='+encodeURIComponent(data.lastCreated);return;}
    }
    toast('Capture continues in the background. Download it from the saved ZIP list.','warn');
  }catch(e){toast('Support ZIP: '+e.message,'error');}
  finally{button.disabled=false;}
};
setInterval(()=>{if(state.page==='diagnostics')refreshSupportArchives();},3000);
