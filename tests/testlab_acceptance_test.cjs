// Runs the actual WebUI acceptance functions with read-only API fixtures.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../web/app.js'),'utf8');
const locks=source.slice(source.indexOf('let webLiveTestBusy='),source.indexOf('function testLine('));
const suite=locks+'\n'+source.slice(source.indexOf('let lastSupportFailureCapture='),source.indexOf('async function getSession('));
const runner=source.slice(source.indexOf('async function runWebTests('),source.indexOf("if($('testSafeBtn'))"));
let checks=0;
function fixture(){return {
 '/api/node-agent/status':{version:'0.0.6a.R12',state:'READY',ready:true},
 '/api/health':{ok:true,version:'0.0.6a.R19'},
 '/api/status':{version:'0.0.6a.R19',healthPercent:100,persistenceEnabled:true,persistenceProtocol:'block+blockentity-journal-v3'},
 '/api/gateway/status':{running:true,seamlessAtLaunch:false,seamlessConfigured:false,processOwned:true,processPid:101,port:25565,transportVersion:'conduit-1.8.0-foundations-r19-guarded-replay'},
 '/api/world/environment':{enabled:true,fresh:true,ownerWorkerId:'worker-001',dayTime:100,workers:[{workerId:'worker-001',synchronized:true},{workerId:'worker-002',synchronized:true}]},
 '/api/config':{values:{'gateway.seamless.enabled':'false'}},
 '/api/workers':{workers:[1,2].map(n=>({id:`worker-00${n}`,state:'READY',managerControlled:true,nodeVersion:'0.0.6a.R12',pendingWrites:0,failedWrites:0,worldIdentitySchema:2,worldIdentity:'same',worldIdentityMatch:true,worldSeedKnown:true,worldSeedMatch:true}))},
 '/api/sessions':{sessions:[{playerId:'tester',anchorWorkerId:'worker-001'}]},
 '/api/player-state':{records:[{}]},'/api/scaling/status':{memoryHardLimit:true,mode:'manual',effectiveMaxWorkers:3}
};}
function context(data){
 const lines=[],calls=[],controls=new Map();let clock=0;
 const ctx={state:{config:{}},Date,api:async(url,options)=>{assert.ok(!options||options.method==='GET','Safe suite attempted a mutation');calls.push(url);assert.ok(url in data,url);return structuredClone(data[url]);},testLine:x=>lines.push(x),performance:{now:()=>clock++},testState:()=>{},toast:()=>{},confirm:()=>true,refreshCore:async()=>{},$:id=>{if(!controls.has(id))controls.set(id,{checked:true,value:id==='testPlayer'?'tester':id==='testSource'?'worker-001':'worker-002',textContent:'',disabled:false});return controls.get(id);}};
 vm.createContext(ctx);vm.runInContext(suite+'\n'+runner,ctx);return {ctx,lines,calls,controls};
}
async function check(name,change,pass,contains){const data=fixture();change?.(data);const c=context(data);assert.equal(await c.ctx.webSafeSuite(true),pass,name);if(contains)assert.ok(c.lines.some(s=>s.includes(contains)),name+' missing explanation');checks++;return c;}
(async()=>{
 await check('Current physical runtime passes',null,true,'preconfiguration screen is expected');
 const seamless=await check('Experimental configuration passes readiness',d=>{d['/api/gateway/status'].seamlessAtLaunch=true;d['/api/gateway/status'].seamlessConfigured=true;},true,'live client test is still required');
 assert.ok(!seamless.lines.some(x=>x.includes('PASS  Seamless')));checks++;
 await check('Later compatible revision does not retain a stale R18 pin',d=>{d['/api/status'].version=d['/api/health'].version='0.0.6a.R19';},true);
 await check('Old runtime rejected',d=>{d['/api/status'].version=d['/api/health'].version='0.0.6a.R15';},false,'expected 0.0.6a.R19');
 await check('Different runtime line rejected',d=>{d['/api/status'].version=d['/api/health'].version='0.0.7a.R1';},false);
 await check('Malformed version rejected',d=>d['/api/status'].version='0.0.6a.R19-garbage',false);
 await check('Manager API disagreement rejected',d=>d['/api/health'].version='0.0.6a.R16',false,'API version mismatch');
 await check('Unhealthy cluster rejected',d=>d['/api/status'].healthPercent=70,false);
 await check('Stopped gateway rejected',d=>d['/api/gateway/status'].running=false,false);
 await check('Unknown process ownership rejected',d=>d['/api/gateway/status'].processOwned=false,false);
 await check('Adopted process accepted',d=>{d['/api/gateway/status'].processOwned=false;d['/api/gateway/status'].processAdopted=true;},true);
 await check('Startup marker does not replace actual heartbeat',d=>d['/api/gateway/status'].transportVersion='conduit-1.8.0-foundations-r16-starting',false);
 await check('R15 transport rejected',d=>d['/api/gateway/status'].transportVersion='conduit-1.8.0-foundations-r15-seamless-neoforge',false);
 await check('Invalid transfer setting rejected',d=>d['/api/gateway/status'].seamlessConfigured='invalid',false);
 await check('Restart required is explicit',d=>d['/api/gateway/status'].seamlessConfigured=true,true,'restart is required');
 await check('Stale environment fails readiness',d=>d['/api/world/environment'].fresh=false,false);
 await check('Unacknowledged worker fails readiness',d=>d['/api/world/environment'].workers[1].synchronized=false,false);
 await check('Stale Node still rejected',d=>d['/api/workers'].workers[0].nodeVersion='0.0.6a.R6',false);
 await check('R8 cannot claim healthy durable saves',d=>d['/api/workers'].workers[0].nodeVersion='0.0.6a.R8',false,'FAIL  Durable Save Health — Save telemetry unavailable');
 await check('Missing counters cannot claim healthy durable saves',d=>delete d['/api/workers'].workers[0].pendingWrites,false,'Save telemetry unavailable');
 await check('Pending writes block live acceptance',d=>d['/api/workers'].workers[0].pendingWrites=1,false,'1 pending');
 await check('Failed writes block live acceptance',d=>d['/api/workers'].workers[0].failedWrites=1,false,'1 failed');

 await check('R11 mixed with R12 is rejected',d=>d['/api/workers'].workers[0].nodeVersion='0.0.6a.R11',false,'Expected Node 0.0.6a.R12; mismatched workers: worker-001=0.0.6a.R11');
 await check('All R11 workers rejected by R12 Manager',d=>d['/api/workers'].workers.forEach(w=>w.nodeVersion='0.0.6a.R11'),false);
 await check('Manager requirement drives matching future revision',d=>{d['/api/node-agent/status'].version='0.0.6a.R13';d['/api/workers'].workers.forEach(w=>w.nodeVersion='0.0.6a.R13');},true,'expected=0.0.6a.R13');
 await check('Newer worker is not blindly accepted',d=>d['/api/workers'].workers[0].nodeVersion='0.0.6a.R13',false);
 await check('Manager still expecting R11 fails R12 workers',d=>d['/api/node-agent/status'].version='0.0.6a.R11',false,'Expected Node 0.0.6a.R11');
 for(const v of [undefined,null,12,'','R12','0.0.6a.R12-garbage'])await check('Unavailable/malformed Manager requirement '+String(v),d=>d['/api/node-agent/status'].version=v,false,'Expected Node version unavailable');
 await check('Unavailable Node endpoint fails closed',d=>delete d['/api/node-agent/status'],false);
 for(const key of ['pendingWrites','failedWrites'])for(const v of [null,'0',NaN,Infinity,-1,.5])await check('Malformed telemetry '+key+' '+String(v),d=>d['/api/workers'].workers[0][key]=v,false,'Save telemetry unavailable');
 const current=await check('Current capture worker IDs pass with synthetic healthy counters',d=>{d['/api/workers'].workers[0].id='worker-003';d['/api/workers'].workers[1].id='worker-004';d['/api/world/environment'].workers=[{workerId:'worker-003',synchronized:true},{workerId:'worker-004',synchronized:true}];},true,'WebUI checks HF10-UIFIX1');
 assert.equal(current.calls.filter(x=>x==='/api/node-agent/status').length,2);checks++;
 const stale=context(fixture());stale.ctx.state.agent={version:'0.0.6a.R11'};assert.equal(await stale.ctx.webSafeSuite(true),true);checks++;
 const bad=fixture();bad['/api/gateway/status'].running=false;const c=context(bad);await c.ctx.runWebTests(true);
 assert.ok(c.lines.some(x=>x.startsWith('SKIP  Live acceptance')));assert.ok(c.lines.includes('=== FULL SUITE FAIL ==='));assert.ok(c.calls.every(x=>!x.includes('handoffs')));checks++;
 console.log(`Test Lab acceptance: PASS (${checks} cases)`);
})().catch(e=>{console.error(e);process.exitCode=1;});
