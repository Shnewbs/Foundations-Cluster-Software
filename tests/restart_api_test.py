"""Real Manager + managed Java fixture processes: lifecycle policy, no game simulation."""
import argparse,http.cookiejar,json,os,pathlib,shutil,subprocess,tempfile,time,urllib.request,urllib.error
p=argparse.ArgumentParser();p.add_argument('--java',required=True);args=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];home=pathlib.Path(tempfile.mkdtemp(prefix='foundations-hf9-restart-'))
(home/'config').mkdir();(home/'config/cluster.properties').write_text('web.bind=127.0.0.1\nweb.port=18776\nstartup.autoStartWorkers=false\ntemplate.autoBootstrap=false\nnodeAgent.required=false\ngateway.enabled=false\nworld.seed=1234\nworld.identity.enforce=false\nworld.environment.enabled=false\nregion.autoFailover=false\nlayer.autoSplit=false\nlayer.autoMerge=false\nworker.stopGraceSeconds=2\n')
cp=os.pathsep.join(str(root/f) for f in ['cluster-manager/build/test-classes','cluster-manager/build/FoundationsClusterManager.jar','cluster-manager/lib/bcprov.jar'])
folder=home/'workers/worker-001';libs=folder/'libraries/net/neoforged/neoforge/fixture';libs.mkdir(parents=True)
for name in ['unix_args.txt','win_args.txt']:(libs/name).write_text('-cp\n"'+cp.replace('\\','\\\\')+'"\ncom.foundations.cluster.manager.LifecycleFixtureAgent\n')
(folder/'server.properties').write_text('server-port=28976\n');(folder/'user_jvm_args.txt').write_text('-Xms16M\n-Xmx64M\n')
log=(home/'manager.log').open('w');proc=subprocess.Popen([args.java,'-cp',cp,'com.foundations.cluster.manager.Main'],cwd=home,env={**os.environ,'FOUNDATIONS_CLUSTER_HOME':str(home)},stdout=log,stderr=subprocess.STDOUT)
client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));checks=[]
def request(path,data=None):
 r=urllib.request.Request('http://127.0.0.1:18776'+path,data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json'})
 try:
  with client.open(r,timeout=5) as x:return x.status,json.loads(x.read())
 except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
def wait(fn,label):
 end=time.monotonic()+20
 while time.monotonic()<end:
  try:
   if x:=fn():return x
  except urllib.error.URLError:pass
  time.sleep(.1)
 raise AssertionError(label)
def worker():return request('/api/workers')[1]['workers'][0]
def check(ok,label):assert ok,label;checks.append(label)
def action(name):
 status,value=request('/api/workers/worker-001/'+name,{})
 assert status in (200,202),(name,status,value)
def state_is(name):
 w=worker();return w if w['state']==name and w.get('processAlive') else None
try:
 wait(lambda:request('/api/health')[0]==200,'Manager unavailable')
 assert request('/api/auth/setup',{'username':'fixture','password':'HF9-test-only-lifecycle-password','remember':False})[0]==201
 action('restart');first=wait(lambda:state_is('READY'),'Initial start not ready');check(first['managerControlled'],'Real worker process owned by Manager')
 for n in range(2):
  pid=worker()['pid'];action('stop');wait(lambda:(w if (w:=worker()) and not w['processAlive'] and not w['ready'] else None),'Stopped worker must exit and become unroutable')
  check(True,'Stopped worker must not be ready')
  action('restart');w=wait(lambda:state_is('READY'),'Separate stop/restart stayed drained')
  check(not w['draining'] and w['pid']!=pid,'Separate stop/restart restores routing and changes PID')
 action('drain');action('stop');wait(lambda:not worker()['processAlive'],'Manual drain stop did not exit');action('restart')
 w=wait(lambda:state_is('DRAINED'),'Manual drain not preserved');check(w['draining'],'Explicit drain survives stop/restart')
 action('undrain');wait(lambda:state_is('READY'),'Undrain failed')
 action('maintenance');action('stop');wait(lambda:not worker()['processAlive'],'Maintenance stop did not exit');action('restart')
 w=wait(lambda:state_is('MAINTENANCE'),'Maintenance not preserved');check(w['maintenance'] and not w['draining'],'Maintenance survives without adding operator drain')
 action('unmaintenance');wait(lambda:state_is('READY'),'Maintenance release failed')
 pid=worker()['pid'];action('restart');wait(lambda:(w if (w:=state_is('READY')) and w['pid']!=pid else None),'Online graceful restart failed');check(True,'Online graceful restart restores availability')
 action('force-stop');wait(lambda:not worker()['processAlive'],'Force stop did not exit');action('restart');wait(lambda:state_is('READY'),'Restart after force stop stayed drained');check(True,'Force-stop temporary drain does not become operator policy')
 print('Managed restart HTTP lifecycle: PASS ('+str(len(checks))+' checks)')
finally:
 try:request('/api/system/shutdown',{})
 except Exception:proc.terminate()
 try:proc.wait(timeout=15)
 except subprocess.TimeoutExpired:proc.kill();proc.wait()
 log.close()
 if proc.returncode or len(checks)<9:print('Fixture logs:',home)
 else:shutil.rmtree(home)
