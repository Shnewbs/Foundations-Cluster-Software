// Actual HF16 control functions with inert API/DOM fixtures. Never stops a real process.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../web/worker-controls.js'), 'utf8');
const begin = source.indexOf('const workerControls = (() => {');
const end = source.indexOf('  // Keep direct filter/sort renders', begin);
assert.ok(begin >= 0 && end > begin, 'Production control module markers');
const functions = source.slice(begin, end) + 'return {reason,confirmation,act,restart,shutdown,renderState};})();';
let checks = 0;
function check(condition, message) { assert.ok(condition, message); checks++; }
function fixture() {
  const controls = new Map(), calls = [], messages = [], confirmations = [], delegated = [];
  const ctx = {
    state:{status:{lifecycle:'RUNNING'},staleEndpoints:[],workers:[
      {id:'worker-001',managed:true,processAlive:true,state:'READY',pendingWrites:2,failedWrites:1},
      {id:'worker-002',managed:true,processAlive:false,state:'OFFLINE'},
      {id:'worker-003',managed:false,processAlive:true,state:'READY'}
    ],selectedWorker:null},
    $:id => {
      if (!controls.has(id)) controls.set(id, {disabled:false,textContent:'',title:'',attrs:{},
        classList:{remove(){},contains(){return true;}},querySelectorAll(){return [];},
        setAttribute(k,v){this.attrs[k]=v;}});
      return controls.get(id);
    },
    workerAction:async(...args)=>delegated.push(args),renderWorkers(){},renderDashboardWorkers(){},renderOperations(){},renderDrawer(){},
    refreshCore:async()=>{},toast:(...args)=>messages.push(args),
    confirm:text=>{confirmations.push(text);return ctx.accept;},accept:true,
    api:async(url,options)=>{calls.push([url,options]);return {state:'QUEUED',workers:2};},
    console
  };
  vm.createContext(ctx);vm.runInContext(functions, ctx);
  return {ctx,api:vm.runInContext('workerControls',ctx),controls,calls,messages,confirmations,delegated};
}
(async()=>{
  let f=fixture(), w=f.ctx.state.workers[0];
  check(f.api.reason(w,'restart')==='', 'ready local worker can restart');
  check(f.api.reason(w,'stop')==='', 'ready local worker can stop');
  check(f.api.reason(w,'force-stop')==='', 'ready local worker can be killed');
  check(f.api.reason(f.ctx.state.workers[1],'restart')==='', 'offline local worker can relaunch');
  for(const action of ['stop','force-stop'])check(f.api.reason(f.ctx.state.workers[1],action)!=='','offline '+action+' disabled');
  for(const action of ['restart','stop','force-stop'])check(f.api.reason(f.ctx.state.workers[2],action)!=='','external '+action+' disabled');
  check(f.api.reason(undefined,'restart')!=='','removed worker rejected');
  check(f.api.reason({...w,controlDetached:true},'force-stop')!=='','detached control rejected');
  check(f.api.reason({...w,state:'RETIRED'},'restart')!=='','retired restart rejected');
  check(f.api.reason({...w,state:'STOPPING'},'restart')!=='','stopping restart rejected');
  check(f.api.reason({...w,gracefulStopPending:true},'stop')!=='','duplicate graceful stop rejected');
  check(f.api.reason({...w,gracefulStopPending:true},'force-stop')==='','emergency kill still allowed during evacuation');
  for(const key of ['workers','status']){f.ctx.state.staleEndpoints=[key];check(f.api.reason(w,'stop')!=='','stale '+key+' blocks mutations');}
  f.ctx.state.staleEndpoints=[];f.ctx.state.status.lifecycle='SHUTTING_DOWN';
  check(f.api.reason(w,'restart')!=='','observed shutdown blocks restart');
  f=fixture();f.ctx.accept=false;await f.api.act('worker-001','stop');
  check(f.calls.length===0,'cancelled stop sends nothing');
  f.ctx.accept=true;
  for(const [action,suffix] of [['restart','restart'],['stop','stop'],['kill','force-stop']]){
    await f.api.act('worker-001',action);
    const [url,opts]=f.calls.at(-1);check(url==='/api/workers/worker-001/'+suffix&&opts.method==='POST','correct POST for '+action);
  }
  check(f.confirmations.at(-1).includes('UNSAVED WORLD CHANGES MAY BE LOST'),'kill explains world-data loss');
  check(f.confirmations.at(-1).includes('pending writes: 2; failed writes: 1'),'kill includes bounded telemetry context');
  check(f.confirmations.at(-1).includes('do not prove'),'zero/low counters never claim world is saved');
  const before=f.calls.length;await f.api.act('worker-003','stop');await f.api.act('missing','kill');
  check(f.calls.length===before,'external/missing worker sends no mutation');
  await f.api.act('worker-001','drain');check(f.delegated.length===1,'non-process actions retain existing implementation');
  f=fixture();let finish;f.ctx.api=(url,opts)=>{f.calls.push([url,opts]);return new Promise(resolve=>{finish=resolve;});};
  const request=f.api.act('worker-001','restart');await Promise.resolve();
  check(f.api.reason(f.ctx.state.workers[0],'stop').includes('already'),'in-flight worker requests are locked');
  await f.api.act('worker-001','restart');check(f.calls.length===1,'duplicate click cannot send a second worker request');
  finish({state:'QUEUED'});await request;check(f.api.reason(f.ctx.state.workers[0],'stop')==='','successful request unlocks controls');
  f=fixture();f.ctx.api=async()=>{throw new Error('fixture denied');};await f.api.act('worker-001','stop');
  check(f.messages.some(m=>m[0].includes('fixture denied')&&m[1]==='error'),'worker API failure surfaced');
  check(f.api.reason(f.ctx.state.workers[0],'stop')==='','failed request unlocks controls');
  f=fixture();await f.api.restart();
  check(f.calls.length===1&&f.calls[0][0]==='/api/system/restart-workers','server restart is not cluster shutdown');
  check(f.confirmations[0].includes('Manager/dashboard and gateway stay online'),'restart confirmation explains scope');
  f=fixture();f.ctx.accept=false;await f.api.restart();check(f.calls.length===0,'cancelled server restart sends nothing');
  f=fixture();f.ctx.state.workers=[];await f.api.restart();check(f.calls.length===0,'empty fleet cannot restart');
  f=fixture();f.ctx.state.staleEndpoints=['workers'];await f.api.restart();check(f.calls.length===0,'stale fleet cannot restart');
  f=fixture();let finishSystem;f.ctx.api=(url,opts)=>{f.calls.push([url,opts]);return new Promise(resolve=>finishSystem=resolve);};
  const system=f.api.restart();await Promise.resolve();await f.api.restart();await f.api.shutdown();
  check(f.calls.length===1,'system requests are mutually exclusive while submitting');
  check(f.controls.get('shutdownClusterTop').disabled,'cluster shutdown disabled during competing request');
  finishSystem({workers:2});await system;
  check(!f.controls.get('shutdownClusterTop').disabled,'system controls unlock after response');
  f=fixture();await f.api.shutdown();
  check(f.calls.length===1&&f.calls[0][0]==='/api/system/shutdown','cluster shutdown uses only shutdown endpoint');
  check(f.confirmations[0].includes('Externally managed processes are not terminated'),'shutdown scope excludes external processes');
  check(f.controls.get('shutdownClusterTop').disabled&&f.controls.get('restartServersTop').disabled,'accepted shutdown disables both top actions');
  await f.api.shutdown();await f.api.restart();await f.api.act('worker-001','restart');
  check(f.calls.length===1,'accepted shutdown is locally terminal');
  check(f.controls.get('topClusterState').textContent.includes('requested'),'accepted shutdown not misreported as completed');
  f=fixture();f.ctx.api=async()=>{throw new Error('fixture network error');};await f.api.shutdown();
  check(f.messages.some(m=>m[0].includes('confirmation not received')),'unconfirmed shutdown is explicit');
  check(!f.controls.get('shutdownClusterTop').disabled,'unconfirmed shutdown does not claim terminal success');
  f=fixture();f.ctx.accept=false;await f.api.shutdown();check(f.calls.length===0,'cancelled cluster shutdown sends nothing');
  console.log(`Worker lifecycle controls: PASS (${checks} checks; inert API/DOM fixtures, no real process actions)`);
})().catch(error=>{console.error(error);process.exitCode=1;});
