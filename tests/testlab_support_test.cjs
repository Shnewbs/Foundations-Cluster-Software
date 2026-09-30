const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const code=fs.readFileSync(require('node:path').join(__dirname,'../web/app.js'),'utf8');
const slice=code.slice(code.indexOf('let lastSupportFailureCapture='),code.indexOf('async function webSafeSuite('));
let checks=0;
function fixture(enabled,fail=false){const calls=[],lines=[];const c={state:{config:{'support.onTestFailure':String(enabled)}},Date,performance:{now:()=>0},$:()=>({textContent:'FAIL tree fixture'}),testLine:s=>lines.push(s),jsonOpts:(method,body)=>({method,body}),api:async(url,opts)=>{calls.push({url,...opts});if(fail)throw new Error('disk unavailable');return {accepted:true};}};vm.createContext(c);vm.runInContext(slice,c);return {c,calls,lines};}
(async()=>{
 const off=fixture(false);assert.equal(await off.c.testRun('fixture',async()=>{throw Error('restore fenced');}),false);assert.equal(off.calls.length,0);checks++;
 const on=fixture(true);assert.equal(await on.c.testRun('fixture',async()=>{throw Error('restore fenced');}),false);assert.equal(on.calls.length,1);assert.equal(on.calls[0].body.reason,'test-failure');assert.match(on.calls[0].body.report,/FAIL tree/);checks++;
 await on.c.captureTestFailure();assert.equal(on.calls.length,1);checks++;
 const broken=fixture(true,true);assert.equal(await broken.c.testRun('fixture',async()=>{throw Error('original failure');}),false);assert.ok(broken.lines.some(l=>l.includes('original failure'))&&broken.lines.some(l=>l.includes('disk unavailable')));checks++;
 const success=fixture(true);assert.equal(await success.c.testRun('fixture',async()=>'ok'),true);assert.equal(success.calls.length,0);checks++;
 console.log(`Test Lab support capture: PASS (${checks} checks)`);
})().catch(e=>{console.error(e);process.exitCode=1;});
