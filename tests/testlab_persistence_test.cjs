const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const app=fs.readFileSync(path.join(__dirname,'../web/app.js'),'utf8');
const code=app.slice(app.indexOf('async function requireLivePair('),app.indexOf('async function gracefulStopTest('));
let checks=0;
function setup(options={}){
 let time=0,marker='',owner='worker-001',response='',created=false,transfers=0,polls=0;
 const commands=[],lines=[],tickets=new Set(options.preloaded||[]),states=new Map();
 class Clock extends Date{static now(){return time;}}
 const context={Date:Clock,Math,console,jsonOpts:(method,body)=>({method,body:JSON.stringify(body)}),testSleep:async(ms)=>{time+=ms;},testLine:s=>lines.push(s)};
 context.api=async(url,opts)=>{
  if(url==='/api/workers')return {workers:[1,2].map(n=>({id:`worker-00${n}`,state:'READY',managerControlled:true}))};
  if(url==='/api/regions')return {regions:[{id:'foundations/minecraft:overworld/r.0.0',state:options.recovering&&polls++===0?'RECOVERING':'ACTIVE',ownerWorkerId:owner}]};
  const body=opts&&JSON.parse(opts.body);
  if(url==='/api/regions/ensure')return {};
  if(url.endsWith('/transfer')){const previous=owner;owner=body.workerId;transfers++;states.set(owner,states.get(previous));return {};}
  if(url==='/api/console/execute'){
   const {target,command}=body;commands.push([target,command]);
   if(command.startsWith('say ')){marker=command.slice(4);response='';return {};}
   if(command.startsWith('foundations_region_ready '))response='FOUNDATIONS_REGION_READY true region=foundations/minecraft:overworld/r.0.0';
   else if(command.includes('forceload query'))response=`Chunk at [0, 0] in minecraft:overworld is ${tickets.has(target)?'':'not '}marked for force loading`;
   else if(command.includes('forceload add')){tickets.add(target);response='Marked chunk [0, 0] in minecraft:overworld to be force loaded';}
   else if(command.includes('forceload remove')){tickets.delete(target);response='Unmarked chunk';}
   else if(command.includes('setblock 8 300 8 minecraft:chest')){
    if(options.occupied){response='Could not set the block';return {};}
    if(options.notLoaded){response='That position is not loaded';return {};}
    created=true;states.set(target,17);response='Changed the block at 8, 300, 8';
   }else if(command.includes('data get block')){
    if(options.missingChest&&transfers===1){response='The target block is not a block entity';return {};}
    response=`8, 300, 8 has the following block data: [{count: ${options.wrongCount&&transfers===1?16:states.get(target)}, Slot: 0b, id: "minecraft:diamond"}]`;
   }else if(command.includes('if data block'))response=!(options.wrongCount&&transfers===1)&&states.get(target)===17?command.slice(command.indexOf('run say ')+8):'';
   else if(command.includes('foundations_region_probe'))response=`FOUNDATIONS_REGION_PROBE sha256=${(options.treeMismatch&&transfers===1?'b':'a').repeat(64)} blocks=${created?35:options.occupied?1:0} logs=${created?5:0} leaves=${created?29:0}`;
   else if(command.includes('setblock 8 282 8')){created=true;response='Changed the block at 8, 282, 8';}
   else if(command.includes('place feature'))response='Placed "minecraft:oak" at 8, 283, 8';
   else response='cleanup';
   return {};
  }
  if(url.startsWith('/api/console/output'))return {lines:['[OLD] minecraft:diamond count: 17',marker&&`[INFO] ${marker}`,response&&`[INFO] ${response}`].filter(Boolean)};
  throw Error(url);
 };
 vm.createContext(context);vm.runInContext(code,context);
 return {context,commands,lines,tickets,transfers:()=>transfers};
}
(async()=>{
 let c=setup({preloaded:['worker-001']});assert.match(await c.context.chestTest('worker-001','worker-002'),/x17/);checks++;
 assert.equal(c.transfers(),2);checks++;
 assert.ok(c.commands.some(([,cmd])=>cmd.includes('minecraft:chest{Items:')&&cmd.endsWith(' keep')));checks++;
 assert.equal(c.commands.filter(([,cmd])=>cmd.includes('if data block')).length,3);checks++;
 assert.ok(c.commands.filter(([,cmd])=>cmd.includes('if data block')).every(([,cmd])=>cmd.includes('Slot:0b')&&cmd.includes('count:17')));checks++;
 assert.deepEqual([...c.tickets],['worker-001']);checks++;
 assert.ok(!c.commands.some(([worker,cmd])=>worker==='worker-001'&&cmd.includes('forceload remove')));checks++;
 assert.equal(c.commands.filter(([,cmd])=>cmd==='execute in minecraft:overworld run setblock 8 300 8 air replace').length,1);checks++;
 c=setup({wrongCount:true});await assert.rejects(()=>c.context.chestTest('worker-001','worker-002'),/destination contents after restore.*Expected 17 diamonds.*count: 16/);checks++;
 c=setup({missingChest:true});await assert.rejects(()=>c.context.chestTest('worker-001','worker-002'),/destination contents after restore.*worker-002.*not a block entity/);checks++;
 c=setup({occupied:true});await assert.rejects(()=>c.context.chestTest('worker-001','worker-002'),/create source chest.*Could not set the block/);checks++;
 assert.ok(!c.commands.some(([,cmd])=>cmd.includes('air replace')));checks++;assert.equal(c.tickets.size,0);checks++;
 c=setup({notLoaded:true});await assert.rejects(()=>c.context.chestTest('worker-001','worker-002'),/worker-001.*That position is not loaded/);checks++;
 c=setup({recovering:true});await c.context.moveRegion('foundations/minecraft:overworld/r.0.0','worker-001');assert.equal(c.transfers(),0);checks++;
 c=setup();assert.match(await c.context.growthTest('worker-001','worker-002'),/all 6144 block states matched/);checks++;
 assert.equal(c.transfers(),2);checks++;assert.equal(c.commands.filter(([,cmd])=>cmd.includes('fill 0 280 0')).length,1);checks++;
 c=setup({occupied:true});await assert.rejects(()=>c.context.growthTest('worker-001','worker-002'),/occupied; nothing was replaced/);checks++;
 assert.ok(!c.commands.some(([,cmd])=>/run (setblock|fill|place)/.test(cmd)));checks++;
 c=setup({treeMismatch:true});await assert.rejects(()=>c.context.growthTest('worker-001','worker-002'),/verify destination tree.*Tree block states differ/);checks++;
 assert.equal(c.transfers(),1);checks++;
 console.log(`Test Lab persistence: PASS (${checks} cases)`);
})().catch(e=>{console.error(e);process.exitCode=1;});
