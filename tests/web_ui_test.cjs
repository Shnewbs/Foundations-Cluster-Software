const fs=require('fs'),path=require('path'),os=require('os'),{spawn,execFileSync}=require('child_process');
const assert=require('assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const root=path.resolve(__dirname,'..'),java=process.env.TEST_JAVA||'java';
const home=fs.mkdtempSync(path.join(os.tmpdir(),'foundations-web-'));
const output=process.env.TEST_OUTPUT||path.join(root,'TestReports','R18');fs.mkdirSync(output,{recursive:true});
fs.mkdirSync(path.join(home,'config'));
fs.copyFileSync(path.join(root,'config/cluster.properties.default'),path.join(home,'config/cluster.properties.default'));
fs.writeFileSync(path.join(home,'config/cluster.properties'),'web.bind=127.0.0.1\nweb.port=18766\nstartup.autoStartWorkers=false\ntemplate.autoBootstrap=false\nnodeAgent.required=false\ngateway.enabled=false\nlayer.autoSplit=false\nlayer.autoMerge=false\nregion.autoFailover=false\nworld.seed=12345\nworld.identity.enforce=false\nworker.heartbeatTimeoutSeconds=120\n');
fs.symlinkSync(path.join(root,'web'),path.join(home,'web'),'dir');
const cp=[path.join(root,'cluster-manager/build/test-classes'),path.join(root,'cluster-manager/build/FoundationsClusterManager.jar'),path.join(root,'cluster-manager/lib/bcprov.jar')].join(path.delimiter);
const credentials=path.join(home,'test-credentials.json');execFileSync(java,['-cp',cp,'com.foundations.cluster.manager.QaCredentials',home,credentials]);const tokens=JSON.parse(fs.readFileSync(credentials));fs.unlinkSync(credentials);
const log=fs.openSync(path.join(output,'manager-test.log'),'w');const proc=spawn(java,['-cp',cp,'com.foundations.cluster.manager.Main'],{cwd:home,env:{...process.env,FOUNDATIONS_CLUSTER_HOME:home},stdio:['ignore',log,log]});
const base='http://127.0.0.1:18766';let browser;const errors=[],checks=[];const delay=ms=>new Promise(r=>setTimeout(r,ms));
function check(ok,label){assert.ok(ok,label);checks.push(label);}
(async()=>{
 try{
  let ready=false;for(let i=0;i<80;i++){try{let r=await fetch(base+'/api/health');if(r.ok){ready=true;break;}}catch{}await delay(100);}
  assert.ok(ready,'Manager failed to bind');
  browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_EXECUTABLE,args:['--no-sandbox']});
  const context=await browser.newContext({viewport:{width:1536,height:1000},reducedMotion:'reduce'});const page=await context.newPage();
  page.on('pageerror',e=>errors.push(e.message));page.on('console',m=>{if(m.type()==='error'&&!m.text().includes('favicon'))errors.push(m.text());});
  const api=context.request;
  check((await api.get(base+'/api/status')).status()===401,'Unauthenticated admin API denied');
  let r=await api.post(base+'/api/auth/setup',{data:{username:'testadmin',password:'R16-test-only-password',remember:false}});check(r.status()===201,'First-run admin setup');
  check((await api.post(base+'/api/config',{headers:{Origin:'https://attacker.invalid'},data:{'cluster.name':'bad'}})).status()===403,'Cross-origin mutation rejected');
  const config=(await(await api.get(base+'/api/config')).json()).values;
  check((await api.put(base+'/api/config',{data:{'cluster.name':'Leak','web.port':'90000'}})).status()===400,'Invalid configuration rejected');
  check((await(await api.get(base+'/api/config')).json()).values['cluster.name']===config['cluster.name'],'Invalid configuration leaves earlier values unchanged');
  for(let i=1;i<=6;i++){
   const id='worker-'+String(i).padStart(3,'0');r=await api.post(base+'/api/agent/register',{headers:{'X-Foundations-Agent-Token':tokens.agent},data:{workerId:id,port:25565+i,players:[74,68,72,66,70,62][i-1],maxPlayers:100,ready:true,heapUsed:(8+i)*1073741824,heapMax:24*1073741824,mspt:15+i,msptP95:22+i,msptP99:27+i,nodeVersion:'0.0.6a.R9',javaVersion:'21',loadedChunks:200+i*50,uptimeMillis:86400000+i*100000,worldSeed:12345,worldEnvironment:'1|24000|0.0|-1.0|true|true|0|5000|9000|false|false|0.0|0.0',worldEnvironmentInstance:'fixture-'+id,worldEnvironmentSequence:1}});assert.equal(r.status(),200,await r.text());
   const ctl=await r.json();
   const ack=await api.post(base+'/api/agent/heartbeat',{headers:{'X-Foundations-Agent-Token':tokens.agent},data:{workerId:id,ready:true,worldSeed:12345,nodeVersion:'0.0.6a.R9',worldEnvironment:ctl.worldEnvironment,worldEnvironmentInstance:'fixture-'+id,worldEnvironmentEpoch:ctl.worldEnvironmentEpoch,worldEnvironmentRevision:ctl.worldEnvironmentRevision,worldEnvironmentSequence:2}});assert.equal(ack.status(),200,await ack.text());
   await api.post(base+'/api/regions/ensure',{data:{worldId:'foundations',dimensionId:'minecraft:overworld',regionX:(i-1)%3,regionZ:Math.floor((i-1)/3),workerId:id}});
  }
  const environment=await(await api.get(base+'/api/world/environment')).json();
  check(environment.fresh&&environment.ownerWorkerId==='worker-001','Shared environment elected owner through real API');
  check(environment.workers.length===6&&environment.workers.every(w=>w.synchronized),'All worker environment acknowledgements reached Manager');
  check((await api.post(base+'/api/workers/worker-002/pin')).status()===200,'Worker pin API');
  check((await(await api.get(base+'/api/workers')).json()).workers.find(w=>w.id==='worker-002').pinned,'Pin visible in inventory');
  check((await api.get(base+'/api/host')).status()===200,'Host health endpoint');
  let diag=await api.get(base+'/api/diagnostics/download');check(diag.status()===200&&(await diag.body()).subarray(0,2).toString()==='PK','Diagnostic ZIP download');
  await page.goto(base);await page.locator('#appShell').waitFor({state:'visible'});await page.waitForFunction(()=>document.querySelector('#mPlayers').textContent==='412');
  check(await page.locator('.nav-item').count()===18,'All administration pages retained');
  await page.waitForFunction(()=>state.metrics.length>=2,{},{timeout:12000});await page.screenshot({path:path.join(output,'dashboard-desktop.png'),fullPage:true});
  for(const name of ['workers','nodes','world','layers','regions','players','gateway','economy','scaling','console','logs','testlab','alerts','settings','diagnostics','roadmap','audit','dashboard']){
   await page.locator(`[data-page="${name}"]`).click();await page.locator(`#page-${name}`).waitFor({state:'visible'});check(await page.locator('.page.active').count()===1,'Navigation '+name);
  }
  await page.locator('[data-page="testlab"]').click();
  for(const id of ['testHandoffBtn','testChestBtn','testStopBtn']){await page.locator('#'+id).click();check((await page.locator('#testOutput').innerText()).includes('BLOCKED'),'Live button gives opt-in explanation '+id);}
  await page.locator('[data-page="dashboard"]').click();
  await page.locator('#dashboardWorkerRows [data-manage]').first().click();await page.locator('#workerDrawer').waitFor({state:'visible'});await page.locator('#drawerHistory').click();await page.locator('#workerHistoryChart').waitFor({state:'visible'});await page.keyboard.press('Escape');await page.locator('#workerDrawer').waitFor({state:'hidden'});checks.push('Worker metrics and Escape drawer close');
  await page.locator('#dashboardWorkerRows [data-manage]').first().click();await page.locator('#workerDrawer').click({position:{x:10,y:20}});await page.locator('#workerDrawer').waitFor({state:'hidden'});checks.push('Drawer outside click close');
  await page.keyboard.press('Control+k');await page.locator('#commandSearch').fill('Host');await page.keyboard.press('Enter');await page.locator('#page-nodes').waitFor({state:'visible'});checks.push('Keyboard search navigation');
  await page.locator('[data-page="settings"]').click();await page.locator('[data-config="cluster.name"]').fill('Unsaved draft');await page.evaluate(()=>loadConfig());check(await page.locator('[data-config="cluster.name"]').inputValue()==='Unsaved draft','Polling preserves unsaved settings');await page.evaluate(()=>document.querySelectorAll('form').forEach(f=>delete f.dataset.dirty));
  await page.locator('[data-page="scaling"]').click();
  await page.evaluate(()=>renderCapacityExplanation({memoryBudgetBytes:24*1073741824,systemReserveBytes:16*1073741824,memoryUsableBytes:8*1073741824,workerReservationBytes:14.4*1073741824,effectiveMaxWorkers:0,memoryHardLimit:true,memoryBudgetSource:'physical'}));
  check((await page.locator('#scalingCapacityNotice').innerText()).includes('30.4 GiB'),'Zero-slot warning explains total budget needed');
  check((await page.locator('#scalingCapacityNotice').innerText()).includes('not zero RAM'),'Automatic RAM detection explained');
  await page.screenshot({path:path.join(output,'scaling-capacity.png'),fullPage:true});
  await page.evaluate(()=>{const saved={template:state.template,agent:state.agent,startup:state.startup,scaling:state.scaling,gateway:state.gateway};window.savedStartup=saved;state.template={state:'READY',ready:true};state.agent={state:'READY',ready:true};state.startup={state:'GATEWAY_PREPARING',gatewayPrerequisitesReady:false};state.scaling={memoryHardLimit:false};state.gateway={lastError:'Bootstrap failed; see logs/velocity-bootstrap.log'};renderStartup();});
  check(await page.locator('#spawnBtn').isDisabled(),'Worker creation disabled until gateway prerequisites ready');
  check((await page.locator('#pipelineDetail').innerText()).includes('Gateway setup failed'),'Startup panel explains gateway failure');
  await page.evaluate(()=>{state.startup={state:'READY',gatewayPrerequisitesReady:true};renderStartup();});
  check(!(await page.locator('#spawnBtn').isDisabled()),'Worker creation enabled after prerequisites ready');
  await page.evaluate(()=>{Object.assign(state,window.savedStartup);delete window.savedStartup;renderStartup();renderCapacityExplanation(state.scaling);});
  await page.locator('[data-page="roadmap"]').click();await page.locator('#featureSearch').fill('seamless');check(await page.locator('.feature-card').count()>0,'Feature catalog search');
  await page.locator('[data-page="world"]').click();await page.screenshot({path:path.join(output,'world-map.png'),fullPage:true});
  await page.locator('[data-page="diagnostics"]').click();await page.screenshot({path:path.join(output,'diagnostics.png'),fullPage:true});
  check((await api.post(base+'/api/support',{data:{reason:'test-failure',report:'disabled'}})).status()===200,'Automatic support capture is opt-in');
  await page.locator('[data-config="support.onTestFailure"]').check();
  await page.locator('[data-config="support.keepCount"]').fill('3');
  await page.locator('button', {hasText:'Save Capture Settings'}).click();
  await page.waitForFunction(()=>state.config['support.onTestFailure']==='true');
  check((await(await api.get(base+'/api/config')).json()).values['support.keepCount']==='3','Support capture preferences persist');
  const downloadPromise=page.waitForEvent('download');await page.locator('#createSupportZip').click();
  const download=await downloadPromise;const zipPath=path.join(output,'fixture-support.zip');await download.saveAs(zipPath);
  check(fs.readFileSync(zipPath).subarray(0,2).toString()==='PK','One-click support ZIP downloads');
  const archives=(await(await api.get(base+'/api/support')).json()).archives;
  check(archives.length===1&&archives[0].bytes>0,'Saved support ZIP listed through API');
  check((await api.get(base+'/api/support/download?name=../config/cluster.properties')).status()===400,'Support download rejects traversal');
  const anon=await browser.newContext();check((await anon.request.get(base+'/api/support')).status()===401,'Support metadata requires administrator');await anon.close();
  await page.screenshot({path:path.join(output,'support-archives.png'),fullPage:true});
  await page.setViewportSize({width:390,height:844});await page.locator('[data-page="dashboard"]').click();await page.screenshot({path:path.join(output,'dashboard-mobile.png'),fullPage:true});
  check(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth+1),'Mobile page has no horizontal overflow');
  await page.setViewportSize({width:1536,height:1000});
  await page.route('**/api/host',route=>route.fulfill({status:503,contentType:'application/json',body:'{"error":"test outage"}'}));await page.evaluate(()=>refreshCore());check(await page.locator('#connectionBanner').isVisible(),'Partial API outage shows stale indicator');await page.unroute('**/api/host');await page.evaluate(()=>refreshCore());
  await page.locator('[data-page="diagnostics"]').click();
  check(await page.locator('#saveHealthRows').innerText().then(t=>t.includes('worker-001')),'Save health lists real worker reports');
  await page.locator('#normalSwitchPolicy').click();
  await page.waitForFunction(()=>document.querySelector('.toast')?.textContent?.includes('restart')||document.body.innerText.includes('Normal transfers saved'));
  check((await(await api.get(base+'/api/config')).json()).values['gateway.seamless.enabled']==='false','Normal transfer recovery button saves policy');
  check(await page.evaluate(()=>JSON.parse(supportSummary()).workers.length===6),'Support summary contains worker save status');
  let alerts=(await(await api.get(base+'/api/alerts')).json()).alerts;if(alerts.length){check((await api.post(base+'/api/alerts/acknowledge',{data:{id:alerts[0].id}})).status()===200,'Alert acknowledgement API');}
  const health=(await(await api.get(base+'/api/status')).json());check(health.players===412,'Live worker player aggregation');
  await api.post(base+'/api/agent/offline',{headers:{'X-Foundations-Agent-Token':tokens.agent},data:{workerId:'worker-006'}});check((await(await api.get(base+'/api/status')).json()).players===350,'Stopped worker excluded from cluster metrics');
  check(errors.filter(x=>!x.includes('503')).length===0,'No JavaScript errors: '+errors.join('; '));
  r=await api.post(base+'/api/system/shutdown');check(r.status()===202,'Planned shutdown accepted');
  const deadline=Date.now()+8000;while(proc.exitCode===null&&Date.now()<deadline)await delay(100);check(proc.exitCode===0,'Manager clean shutdown');
  const tail=fs.readFileSync(path.join(output,'manager-test.log'),'utf8');check(!tail.includes('Emergency backend-loss quarantine'),'No shutdown failover storm');
  fs.writeFileSync(path.join(output,'web-results.json'),JSON.stringify({passed:checks.length,checks,errors},null,2));console.log(`Web/API integration: PASS (${checks.length} checks)`);
 }finally{if(browser)await browser.close();if(proc.exitCode===null)proc.kill('SIGTERM');fs.closeSync(log);fs.rmSync(home,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exitCode=1;});
