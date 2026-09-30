"""Real Manager HTTP contract test with disposable simulated R10 worker reports (no Minecraft client)."""
import argparse, base64, http.cookiejar, json, os, pathlib, shutil, subprocess, tempfile, time, urllib.request, urllib.error
p=argparse.ArgumentParser();p.add_argument('--java',required=True);args=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];home=pathlib.Path(tempfile.mkdtemp(prefix='foundations-hf7-api-'))
(home/'config').mkdir();(home/'config/cluster.properties').write_text('web.bind=127.0.0.1\nweb.port=18772\nstartup.autoStartWorkers=false\ntemplate.autoBootstrap=false\nnodeAgent.required=false\ngateway.enabled=false\nworld.seed=1234\nworld.identity.enforce=false\nworld.environment.enabled=false\nlayer.autoSplit=false\nlayer.autoMerge=false\nregion.autoFailover=false\nregion.recoverySeconds=0\n')
cp=os.pathsep.join(str(root/x) for x in ['cluster-manager/build/test-classes','cluster-manager/build/FoundationsClusterManager.jar','cluster-manager/lib/bcprov.jar']);credentials=home/'credentials.json'
subprocess.run([args.java,'-cp',cp,'com.foundations.cluster.manager.QaCredentials',str(home),str(credentials)],check=True,capture_output=True)
agent=json.loads(credentials.read_text())['agent'];credentials.unlink()
log=(home/'manager-output.log').open('w');process=subprocess.Popen([args.java,'-cp',cp,'com.foundations.cluster.manager.Main'],cwd=home,env={**os.environ,'FOUNDATIONS_CLUSTER_HOME':str(home)},stdout=log,stderr=subprocess.STDOUT)
client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));checks=[]
def request(path,data=None,worker=False):
 headers={'Content-Type':'application/json'}
 if worker:headers['X-Foundations-Agent-Token']=agent
 req=urllib.request.Request('http://127.0.0.1:18772'+path,data=None if data is None else json.dumps(data).encode(),headers=headers)
 try:
  with client.open(req,timeout=5) as response:return response.status,json.loads(response.read())
 except urllib.error.HTTPError as error:return error.code,json.loads(error.read())
def wait(test,reason):
 end=time.monotonic()+15
 while time.monotonic()<end:
  try:
   if test():return
  except urllib.error.URLError:pass
  time.sleep(.15)
 raise AssertionError(reason)
def check(ok,label):
 assert ok,label;checks.append(label)
try:
 wait(lambda:request('/api/health')[0]==200,'Manager not ready')
 assert request('/api/auth/setup',{'username':'fixture','password':'HF7-disposable-fixture-password','remember':False})[0]==201
 base={'workerId':'worker-001','port':25596,'ready':True,'worldSeed':1234,'nodeVersion':'0.0.6a.R11','persistenceInstance':'hf7-process-a','pendingWrites':0,'failedWrites':0,'lastDurableWrite':0}
 status,body=request('/api/agent/register',base,True);check(status==200,'R10 registration accepted')
 region='foundations/minecraft:overworld/r.0.0'
 status,body=request('/api/regions/ensure',{'worldId':'foundations','dimensionId':'minecraft:overworld','regionX':0,'regionZ':0,'workerId':'worker-001'});check(status==201,'Region created: '+str(status)+' '+str(body))
 def record():return next(r for r in request('/api/regions')[1]['regions'] if r['id']==region)
 r=record();check(r['state']=='RECOVERING','New destination not active before restore')
 token=base64.urlsafe_b64encode(region.encode()).decode().rstrip('=')+'|'+str(r['epoch'])
 status,body=request('/api/agent/persistence/replay',{'workerId':'worker-001','persistenceInstance':'hf7-process-a','regions':token},True)
 check(status==200 and body['regions']==token,'Replay echoes exact requested epochs')
 check(all(k in body for k in ['bundle','blockEntityBundle','revision','blocks','blockEntities','protocol']),'Replay retains old and new response fields')
 check(request('/api/agent/persistence/replay',{'workerId':'worker-001','persistenceInstance':'old-process','regions':token},True)[0]==409,'Replay rejects stale process')
 check(request('/api/agent/persistence/replay',{'workerId':'worker-001','persistenceInstance':'hf7-process-a','regions':token+'0'},True)[0]==409,'Replay rejects stale epoch')
 status,body=request('/api/agent/heartbeat',{**base,'hydratedRegions':token},True);check(status==200,'Restoration report accepted')
 wait(lambda:record()['state']=='ACTIVE','Region never became active');check(True,'Region activates after matching process/epoch report')
 status,body=request('/api/agent/heartbeat',{**base,'persistenceInstance':'hf7-process-b','hydratedRegions':''},True);check(status==200,'Restarted worker report accepted')
 wait(lambda:record()['state']=='RECOVERING','Restart bypassed destination restoration');check(True,'Restarted process loses previous readiness')
 check(request('/api/agent/persistence/replay',{'workerId':'worker-001','persistenceInstance':'hf7-process-a','regions':token},True)[0]==409,'Old in-flight replay rejected after restart')
 check(request('/api/agent/persistence/replay',{'workerId':'worker-001'},True)[0]==200,'Legacy replay response still available')
 print('Manager hydration HTTP contract: PASS ('+str(len(checks))+' checks)')
finally:
 process.terminate()
 try:process.wait(timeout=15)
 except subprocess.TimeoutExpired:process.kill();process.wait()
 log.close();shutil.rmtree(home)
