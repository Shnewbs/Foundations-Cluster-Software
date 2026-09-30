// Run the production transfer wait with deterministic API snapshots and a virtual clock.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const source=fs.readFileSync(path.join(__dirname,'../web/app.js'),'utf8');
const functions=source.slice(source.indexOf('async function getSession('),source.indexOf('async function requireLivePair('));
let checks=0;
function harness(frames){
 let now=0,polls=0,sleeps=0,frame=frames[0];const calls=[];
 const ctx={Date:{now:()=>now},jsonOpts:(method,body)=>({method,body}),testSleep:async ms=>{sleeps++;now+=ms;},api:async(url,options)=>{
  calls.push([url,options]);
  if(url==='/api/handoffs/request')return {id:'current'};
  if(url==='/api/handoffs'){frame=frames[Math.min(polls++,frames.length-1)];return {handoffs:frame.handoffs||[{id:'current',state:'SWITCHING_PROXY'}]};}
  if(url==='/api/sessions')return {sessions:[{playerId:'tester',anchorWorkerId:frame.anchor||'worker-001',simulationWorkerId:frame.sim||frame.anchor||'worker-001'}]};
  if(url==='/api/player-state')return {records:[{name:'tester',workerId:frame.profile||'worker-002'}]};
  throw new Error('Unexpected API '+url);
 }};
 vm.createContext(ctx);vm.runInContext(functions,ctx);
 return {ctx,calls,get sleeps(){return sleeps;}};
}
(async()=>{
 for(const state of ['ABORTED','FAILED']){
  const h=harness([{handoffs:[{id:'current',state,error:'Backend configuration read timed out'}]}]);
  await assert.rejects(h.ctx.waitPhysicalTransfer('tester','worker-002',45000,'current'),new RegExp(`current ${state}: Backend configuration read timed out`));
  assert.equal(h.sleeps,0,'Terminal handoff must stop before the 45-second polling timeout');checks++;
 }
 const fallback=harness([{handoffs:[{id:'current',state:'ABORTED'}]}]);
 await assert.rejects(fallback.ctx.waitPhysicalTransfer('tester','worker-002',45000,'current'),/No reason reported/);checks++;
 const unrelated=harness([{anchor:'worker-002',handoffs:[{id:'old',state:'ABORTED',error:'old failure'},{id:'current',state:'COMMITTED'}]}]);
 assert.equal((await unrelated.ctx.waitPhysicalTransfer('tester','worker-002',45000,'current')).anchorWorkerId,'worker-002');checks++;
 const prepared=harness([{sim:'worker-002',handoffs:[{id:'current',state:'SWITCHING_PROXY'}]}]);
 await assert.rejects(prepared.ctx.waitPhysicalTransfer('tester','worker-002',2000,'current'),/handoff=current; last state=SWITCHING_PROXY/);checks++;
 const committed=harness([{handoffs:[{id:'current',state:'COMMITTED'}]}]);
 await assert.rejects(committed.ctx.waitPhysicalTransfer('tester','worker-002',2000,'current'),/last state=COMMITTED/);checks++;
 const staleProfile=harness([{anchor:'worker-002',profile:'worker-001'},{anchor:'worker-002',profile:'worker-002'}]);
 await staleProfile.ctx.waitPhysicalTransfer('tester','worker-002',45000,'current');assert.equal(staleProfile.sleeps,1);checks++;
 const abortBeforeOwnership=harness([{anchor:'worker-002',handoffs:[{id:'current',state:'ABORTED',error:'do not claim success'}]}]);
 await assert.rejects(abortBeforeOwnership.ctx.waitPhysicalTransfer('tester','worker-002',45000,'current'),/do not claim success/);checks++;
 const integrated=harness([{handoffs:[{id:'current',state:'ABORTED',error:'destination rejected'}]}]);
 await assert.rejects(integrated.ctx.handoffTest('tester','worker-002'),/current ABORTED: destination rejected/);
 assert.equal(integrated.sleeps,0);assert.equal(integrated.calls.filter(([url])=>url==='/api/handoffs/request').length,1);checks++;
 console.log(`Test Lab handoff errors: PASS (${checks} cases)`);
})().catch(e=>{console.error(e);process.exitCode=1;});
