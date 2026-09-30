const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict'),path=require('node:path');
const s=fs.readFileSync(path.join(__dirname,'../web/app.js'),'utf8');const code=s.slice(s.indexOf('async function restartWorkerTest('),s.indexOf('async function runWebTests('));
let checks=0;
function harness(sequence){let i=0,time=0;const ctx={Date:{now:()=>time},testSleep:async ms=>{time+=ms;},api:async(url,opts)=>opts?{}:{workers:[{id:'worker-001',processAlive:true,ready:true,nodeVersion:'0.0.6a.R11',...sequence[Math.min(i++,sequence.length-1)]}]}};vm.createContext(ctx);vm.runInContext(code,ctx);return ctx;}
(async()=>{
 const ready=harness([{state:'BOOTING',ready:false},{state:'READY'}]);assert.match(await ready.restartWorkerTest('worker-001',3000),/state=READY/);checks++;
 const drained=harness([{state:'DRAINED',draining:true}]);await assert.rejects(drained.restartWorkerTest('worker-001',2000),/state=DRAINED.*draining=true.*operator drain/);checks++;
 const maint=harness([{state:'MAINTENANCE',maintenance:true}]);await assert.rejects(maint.restartWorkerTest('worker-001',2000),/maintenance=true/);checks++;
 const mismatch=harness([{state:'READY',worldIdentity:'different',worldIdentityMatch:false}]);await assert.rejects(mismatch.restartWorkerTest('worker-001',2000),/identity mismatch/);checks++;
 const dead=harness([{state:'READY',processAlive:false}]);await assert.rejects(dead.restartWorkerTest('worker-001',2000),/processAlive=false/);checks++;
 console.log(`Test Lab restart: PASS (${checks} checks)`);
})().catch(e=>{console.error(e);process.exitCode=1;});
