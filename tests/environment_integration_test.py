"""Two real local NeoForge workers: clock/weather propagation, owner loss, Manager restart.
Requires a provisioned server template with an already accepted eula.txt, and JDK 21.
Runs only disposable localhost fixtures; never opens a client or edits the supplied template.
"""
import argparse, json, os, pathlib, shutil, subprocess, tempfile, time
import urllib.request, urllib.error, http.cookiejar

class Response:
 def __init__(self,response):self.status=response.code;self.body=response.read();self.ok=200<=self.status<300
 def json(self):return json.loads(self.body)
 def raise_for_status(self):
  if not self.ok:raise RuntimeError('HTTP '+str(self.status))
class Session:
 def __init__(self):self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def get(self,url,timeout):return Response(self.opener.open(url,timeout=timeout))
 def post(self,url,json,timeout):
  request=urllib.request.Request(url,data=__import__('json').dumps(json).encode(),headers={'Content-Type':'application/json'},method='POST')
  return Response(self.opener.open(request,timeout=timeout))

p=argparse.ArgumentParser();p.add_argument('--template',required=True);p.add_argument('--java',required=True);p.add_argument('--output',required=True);args=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];template=pathlib.Path(args.template).resolve();out=pathlib.Path(args.output).resolve();out.mkdir(parents=True,exist_ok=True)
if 'eula=true' not in (template/'eula.txt').read_text().lower():raise RuntimeError('Template EULA has not been accepted')
home=pathlib.Path(tempfile.mkdtemp(prefix='foundations-env-r18-'));(home/'config').mkdir();base='http://127.0.0.1:18768'
(home/'config/cluster.properties').write_text('web.bind=127.0.0.1\nweb.port=18768\nstartup.autoStartWorkers=false\ntemplate.autoBootstrap=false\nnodeAgent.required=false\ngateway.enabled=false\nworld.seed=1234\nworld.identity.enforce=false\nworld.environment.enabled=true\nlayer.autoSplit=false\nlayer.autoMerge=false\nregion.autoFailover=false\n')
cp=os.pathsep.join(str(root/x) for x in ['cluster-manager/build/test-classes','cluster-manager/build/FoundationsClusterManager.jar','cluster-manager/lib/bcprov.jar'])
credentials=home/'fixture-credentials.json';subprocess.run([args.java,'-cp',cp,'com.foundations.cluster.manager.QaCredentials',str(home),str(credentials)],check=True,capture_output=True)
tokens=json.loads(credentials.read_text());credentials.unlink();processes=[];files=[];session=Session();password='R18-environment-fixture-local-only';checks=[]
def wait(predicate,message,timeout=120):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  try:
   result=predicate()
   if result:return result
  except (urllib.error.URLError,TimeoutError,KeyError,ValueError):pass
  time.sleep(.5)
 raise AssertionError(message)
def start_manager():
 f=(out/'manager.log').open('a');files.append(f)
 proc=subprocess.Popen([args.java,'-cp',cp,'com.foundations.cluster.manager.Main'],cwd=home,env={**os.environ,'FOUNDATIONS_CLUSTER_HOME':str(home)},stdout=f,stderr=subprocess.STDOUT);processes.append(proc)
 wait(lambda:session.get(base+'/api/health',timeout=2).ok,'Manager did not start',30)
 auth=session.get(base+'/api/auth/status',timeout=2).json()
 response=session.post(base+('/api/auth/setup' if auth.get('setupRequired') else '/api/auth/login'),json={'username':'fixture','password':password,'remember':False},timeout=10);response.raise_for_status()
 return proc
def status():
 r=session.get(base+'/api/world/environment',timeout=3);r.raise_for_status();return r.json()
def synchronized(count):
 e=status();return e if e['fresh'] and len([w for w in e['workers'] if w['synchronized']])>=count else None
def command(proc,text):proc.stdin.write(text+'\n');proc.stdin.flush()
def check(message):checks.append(message);print('PASS',message,flush=True)
try:
 manager=start_manager();workers={}
 for n in [1,2]:
  wid=f'worker-00{n}';folder=home/wid;folder.mkdir();(folder/'mods').mkdir()
  (folder/'libraries').symlink_to(template/'libraries',target_is_directory=True);shutil.copyfile(template/'eula.txt',folder/'eula.txt')
  shutil.copyfile(root/'minecraft-agent/build/libs/foundations_cluster_node-0.0.6a.R11.jar',folder/'mods/foundations_cluster_node-0.0.6a.R11.jar')
  (folder/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={28865+n}\nonline-mode=false\nenforce-secure-profile=false\nlevel-seed=1234\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nmax-players=4\n')
  f=(out/f'{wid}.log').open('w');files.append(f)
  env={**os.environ,'FOUNDATIONS_CLUSTER_MANAGER_URL':base,'FOUNDATIONS_CLUSTER_AGENT_TOKEN':tokens['agent'],'FOUNDATIONS_CLUSTER_WORKER_ID':wid,'FOUNDATIONS_CLUSTER_WORKER_PORT':str(28865+n),'FOUNDATIONS_PERSISTENCE_ENABLED':'false','FOUNDATIONS_DATAPLANE_ENABLED':'false'}
  proc=subprocess.Popen([args.java,'-Xms128M','-Xmx1024M','@libraries/net/neoforged/neoforge/21.1.250/unix_args.txt','nogui'],cwd=folder,env=env,stdin=subprocess.PIPE,stdout=f,stderr=subprocess.STDOUT,text=True);processes.append(proc);workers[wid]=proc
 e=wait(lambda:synchronized(2),'Workers did not synchronize',150);owner=e['ownerWorkerId'];follower=next(w for w in workers if w!=owner);check('Two real NeoForge workers acknowledged one shared environment')
 for c in ['gamerule doDaylightCycle false','time set 12000','weather thunder 600','gamerule doWeatherCycle false']:command(workers[owner],c)
 def matching():
  e=synchronized(2)
  return e if e and e['dayTime']==12000 and e['raining'] and e['thundering'] and all(w['dayTime']==12000 and w['raining'] for w in e['workers'] if w['synchronized']) else None
 wait(matching,'Clock/thunder command did not propagate',25);check('Owner time/daylight/weather commands reached both workers')
 command(workers[owner],'stop');workers[owner].wait(timeout=30)
 wait(lambda:(e:=synchronized(1)) and e['ownerWorkerId']==follower and e['dayTime']==12000 and e['raining'] and e['thundering'],'Owner failover lost time/weather',30);check('Owner shutdown retained frozen clock and thunder on the survivor')
 manager.terminate();manager.wait(timeout=30);manager=start_manager()
 wait(lambda:(e:=synchronized(1)) and e['ownerWorkerId']==follower and e['dayTime']==12000 and e['raining'] and e['thundering'],'Manager restart lost environment checkpoint',30);check('Manager restart restored environment without importing stale worker state')
 (out/'environment-results.json').write_text(json.dumps({'passed':len(checks),'checks':checks},indent=2))
 print('Environment integration: PASS',len(checks),'checks',flush=True)
finally:
 for proc in reversed(processes):
  if proc.poll() is None:
   try:
    if proc.stdin:command(proc,'stop');proc.wait(timeout=20)
    else:proc.terminate();proc.wait(timeout=20)
   except (OSError,subprocess.TimeoutExpired):proc.kill();proc.wait()
 for f in files:f.close()
 shutil.rmtree(home,ignore_errors=True)
