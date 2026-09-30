// Exercise the actual click handlers, including the undefined-state regression from R17.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const js=fs.readFileSync(process.env.TEST_APP||path.join(__dirname,'../web/app.js'),'utf8');
const section=js.slice(js.indexOf('// ---------- R8 WebUI Acceptance Test Lab'),js.indexOf('async function spawn('));
let checks=0;
function setup(){
 const controls=new Map(),events=[],calls=[],context={performance:{now:()=>0},setTimeout:fn=>queueMicrotask(fn),Date,console,jsonOpts:(method,body)=>({method,body:JSON.stringify(body)}),
  $:id=>{if(!controls.has(id))controls.set(id,{value:id==='testPlayer'?'Tester':id==='testDest'?'worker-002':'worker-001',checked:true,disabled:false,textContent:'',className:'',scrollHeight:0});return controls.get(id);},
  toast:message=>events.push(message),confirm:()=>true,refreshCore:async()=>{},state:{workers:[],sessions:[]}};
 vm.createContext(context);vm.runInContext(section,context);
 for(const name of ['handoffTest','chestTest','growthTest','gracefulStopTest','restartWorkerTest'])context[name]=async(...args)=>{calls.push([name,...args]);return 'fixture success';};
 context.webSafeSuite=async()=>true;
 return {context,controls,events,calls,click:id=>context.$(id).onclick(),output:()=>context.$('testOutput').textContent,status:()=>context.$('testRunState').textContent};
}
(async()=>{
 for(const [id,expected] of [['testHandoffBtn',['handoffTest']],['testChestBtn',['chestTest']],['testGrowthBtn',['growthTest']],['testStopBtn',['gracefulStopTest','restartWorkerTest']]]){
  const c=setup();await c.click(id);assert.deepEqual(c.calls.map(x=>x[0]),expected);assert.equal(c.status(),'PASS');assert.ok(!c.context.$(id).disabled);checks++;
 }
 for(const id of ['testHandoffBtn','testChestBtn','testGrowthBtn','testStopBtn','testFullBtn']){const c=setup();c.context.$('testDestructive').checked=false;await c.click(id);assert.equal(c.calls.length,0);assert.match(c.output(),/BLOCKED/);checks++;}
 const rejected=setup();rejected.context.handoffTest=async()=>{throw Error('Destination unavailable');};await rejected.click('testHandoffBtn');assert.equal(rejected.status(),'FAIL');assert.match(rejected.output(),/Destination unavailable/);checks++;
 const stopped=setup();stopped.context.gracefulStopTest=async()=>{throw Error('Evacuation timed out');};await stopped.click('testStopBtn');assert.equal(stopped.status(),'FAIL');assert.ok(!stopped.calls.some(x=>x[0]==='restartWorkerTest'));checks++;
 const restore=setup();restore.context.restartWorkerTest=async()=>{throw Error('Restart failed');};await restore.click('testStopBtn');assert.equal(restore.status(),'FAIL');checks++;
 const cancelled=setup();cancelled.context.confirm=()=>false;await cancelled.click('testChestBtn');assert.equal(cancelled.calls.length,0);checks++;
 const missing=setup();missing.context.$('testPlayer').value='';await missing.click('testHandoffBtn');assert.equal(missing.calls.length,0);assert.equal(missing.status(),'BLOCKED');checks++;
 const busy=setup();let release;busy.context.handoffTest=()=>new Promise(resolve=>{release=resolve;});const first=busy.click('testHandoffBtn');
 for(const id of ['testSafeBtn','testFullBtn','testHandoffBtn','testChestBtn','testGrowthBtn','testStopBtn','testPlayer','testDest','testSource','testDestructive'])assert.ok(busy.context.$(id).disabled,id);
 await busy.click('testChestBtn');await busy.click('testSafeBtn');assert.equal(busy.calls.length,0);assert.ok(busy.events.every(x=>x.includes('already running')));
 busy.context.renderTestLabOptions(); // Polling must leave captured selections alone while running.
 release('completed');await first;assert.equal(busy.status(),'PASS');assert.ok(!busy.context.$('testSafeBtn').disabled);checks++;
 const safe=setup();let finish;safe.context.webSafeSuite=()=>new Promise(resolve=>finish=resolve);const pending=safe.click('testSafeBtn');await safe.click('testHandoffBtn');assert.equal(safe.calls.length,0);finish(true);await pending;assert.equal(safe.status(),'PASS');checks++;
 const inventory=setup();let marker='';inventory.context.api=async(url,opts)=>{const command=JSON.parse(opts.body).command;if(command.startsWith('say '))marker=command.slice(4);return {};};
 inventory.context.consoleTail=async()=>`[OLD] Tester has the following entity data: [{id:"minecraft:diamond"}]\n[INFO] ${marker}\n[INFO] Tester has the following entity data: []`;
 assert.equal(await inventory.context.inventorySnapshot('Tester','worker-001'),'[]');checks++;
 const stale=setup();stale.context.api=async()=>({});let polls=0;stale.context.consoleTail=async()=>{if(++polls>1)throw Error('only stale output');return '[OLD] Tester has the following entity data: []';};
 await assert.rejects(()=>stale.context.inventorySnapshot('Tester','worker-001'),/only stale output/);checks++;
 console.log(`Test Lab button checks: PASS (${checks} cases)`);
})().catch(e=>{console.error(e);process.exitCode=1;});
