"""Two real local NeoForge workers: chest/tree persistence, clock/weather, owner loss and restart.
Requires a provisioned server template with an already accepted eula.txt, and JDK 21.
Runs only disposable localhost fixtures; never opens a client or edits the supplied template.
"""
import argparse, base64, json, os, pathlib, shutil, subprocess, tempfile, time
import urllib.request, urllib.error, urllib.parse, http.cookiejar

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
home=pathlib.Path(tempfile.mkdtemp(prefix='foundations-persist-r19-'));(home/'config').mkdir();base='http://127.0.0.1:18768'
(home/'config/cluster.properties').write_text('web.bind=127.0.0.1\nweb.port=18768\nstartup.autoStartWorkers=false\ntemplate.autoBootstrap=false\nnodeAgent.required=false\ngateway.enabled=false\nworld.seed=1234\nworld.identity.enforce=false\nworld.environment.enabled=true\nlayer.autoSplit=false\nlayer.autoMerge=false\nregion.autoFailover=true\n')
# Regression fixture from the failed restore: unsupported saved fire must not
# invoke placement gameplay while a region snapshot is only partly restored.
(home/'runtime').mkdir(exist_ok=True)
b64=lambda value:base64.urlsafe_b64encode(value.encode()).decode().rstrip('=')
fire='minecraft:fire;age=10;east=false;north=false;south=false;up=false;west=false'
(home/'runtime/persistent-blocks.tsv').write_text('\t'.join([b64('foundations'),b64('minecraft:overworld'),b64('foundations/minecraft:overworld/r.0.0'),'1','65','15','43',b64(fire),'1','1'])+'\n')
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
  shutil.copyfile(root/'minecraft-agent/build/libs/foundations_cluster_node-0.0.6a.R13.jar',folder/'mods/foundations_cluster_node-0.0.6a.R13.jar')
  (folder/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={28865+n}\nonline-mode=false\nenforce-secure-profile=false\nlevel-seed=1234\nlevel-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nmax-players=4\n')
  f=(out/f'{wid}.log').open('w');files.append(f)
  env={**os.environ,'FOUNDATIONS_CLUSTER_MANAGER_URL':base,'FOUNDATIONS_CLUSTER_AGENT_TOKEN':tokens['agent'],'FOUNDATIONS_CLUSTER_WORKER_ID':wid,'FOUNDATIONS_CLUSTER_WORKER_PORT':str(28865+n),'FOUNDATIONS_PERSISTENCE_ENABLED':'true','FOUNDATIONS_DATAPLANE_ENABLED':'true'}
  proc=subprocess.Popen([args.java,'-Xms128M','-Xmx1024M','@libraries/net/neoforged/neoforge/21.1.250/unix_args.txt','nogui'],cwd=folder,env=env,stdin=subprocess.PIPE,stdout=f,stderr=subprocess.STDOUT,text=True);processes.append(proc);workers[wid]=proc
 e=wait(lambda:synchronized(2),'Workers did not synchronize',150);owner=e['ownerWorkerId'];follower=next(w for w in workers if w!=owner);check('Two real NeoForge workers acknowledged one shared environment')
 for c in ['gamerule doDaylightCycle false','time set 12000','weather thunder 600','gamerule doWeatherCycle false']:command(workers[owner],c)
 def matching():
  e=synchronized(2)
  return e if e and e['dayTime']==12000 and e['raining'] and e['thundering'] and all(w['dayTime']==12000 and w['raining'] for w in e['workers'] if w['synchronized']) else None
 wait(matching,'Clock/thunder command did not propagate',25);check('Owner time/daylight/weather commands reached both workers')
 # Confirm mutation capture + save barrier on two real servers.
 def request(path,body):return session.post(base+path,json=body,timeout=35).json()
 region='foundations/minecraft:overworld/r.0.0'
 encoded=urllib.parse.quote(region,safe='')
 request('/api/regions/ensure',{'worldId':'foundations','dimensionId':'minecraft:overworld','regionX':0,'regionZ':0,'workerId':owner})
 for w in workers:command(workers[w],'forceload add 0 0')
 def recent_log(w):return (out/(w+'.log')).read_text(errors='replace')
 wait(lambda:any(r['id']==region and r['state']=='ACTIVE' and r['ownerWorkerId']==owner for r in session.get(base+'/api/regions',timeout=5).json()['regions']),'Source did not acknowledge restored authority',20)
 check('Saved unsupported fire restored without blocking region hydration')
 time.sleep(3) # let the ACTIVE control response reach the Node before creating fixtures
 command(workers[owner],'setblock 8 100 8 minecraft:chest')
 command(workers[owner],'item replace block 8 100 8 container.0 with minecraft:diamond 17')
 command(workers[owner],'data get block 8 100 8 Items')
 wait(lambda:'count: 17' in recent_log(owner),'Source chest missing',10)
 request('/api/regions/'+encoded+'/transfer',{'workerId':follower})
 def chest_arrived():
  command(workers[follower],'data get block 8 100 8 Items')
  return 'count: 17' in recent_log(follower)
 wait(chest_arrived,'Chest failed confirmed-save transfer',30)
 check('Fresh chest with 17 diamonds survived immediate authority transfer')
 wait(lambda:any(v['id']==region and v['state']=='ACTIVE' for v in session.get(base+'/api/regions',timeout=5).json()['regions']),'Destination not active',15)
 request('/api/regions/'+encoded+'/transfer',{'workerId':owner})
 # Wait for the worker's control response as well as Manager state. A fixed delay
 # could send /setblock while the region was still sealed (the HF7 test race).
 before_ready=len(recent_log(owner))
 def source_ready():
  command(workers[owner],'foundations_region_ready "'+region+'"')
  return 'FOUNDATIONS_REGION_READY true' in recent_log(owner)[before_ready:]
 wait(source_ready,'Source still sealed before tree fixture',30)
 # Use the tree feature generator, then compare every block state in a bounded volume.
 # This checks authoritative tree persistence, not independent replica random ticking.
 def fresh_console(w,cmd,token,timeout=15):
  before=len(recent_log(w));command(workers[w],cmd)
  return wait(lambda:(tail if token in (tail:=recent_log(w)[before:]) else None),'Fresh response missing: '+w+' '+cmd,timeout)
 def tree_probe(w):
  import re
  text=fresh_console(w,'foundations_region_probe 0 280 0 15 303 15','FOUNDATIONS_REGION_PROBE')
  match=re.search(r'FOUNDATIONS_REGION_PROBE sha256=([a-f0-9]{64}) blocks=(\d+) logs=(\d+) leaves=(\d+)',text)
  assert match,'Malformed tree probe response'
  return match.groups()
 command(workers[owner],'setblock 8 282 8 minecraft:dirt')
 fresh_console(owner,'place feature minecraft:oak 8 283 8','Placed "minecraft:oak"')
 time.sleep(2);tree_before=tree_probe(owner)
 assert int(tree_before[2])>0 and int(tree_before[3])>0,'Tree fixture needs logs and leaves'
 for destination in [follower,owner]:
  request('/api/regions/'+encoded+'/transfer',{'workerId':destination})
  wait(lambda:any(r['id']==region and r['ownerWorkerId']==destination and r['state']=='ACTIVE' for r in session.get(base+'/api/regions',timeout=5).json()['regions']),'Tree destination not hydrated',30)
  assert tree_probe(destination)==tree_before,'Generated tree block states changed across transfer'
 check('Generated oak: all 6144 block states survived acknowledged authority round-trip')
 time.sleep(3)
 # A real creeper destroys a small stone platform; the center must stay air after SIGKILL.
 command(workers[owner],'fill 4 90 4 12 96 12 minecraft:stone')
 command(workers[owner],'summon minecraft:creeper 8 97 8 {ignited:1b,Fuse:5s,ExplosionRadius:3b}')
 def crater():
  command(workers[owner],'execute if block 8 96 8 minecraft:air run say R19_CRATER_SOURCE')
  return 'R19_CRATER_SOURCE' in recent_log(owner)
 wait(crater,'Creeper did not destroy center block',20)
 def durable():
  data=session.get(base+'/api/workers',timeout=5).json()
  w=next(w for w in data['workers'] if w['id']==owner)
  return w['pendingWrites']==0 and w['failedWrites']==0 and w['lastDurableWrite']>0
 wait(durable,'Explosion writes did not settle',30)
 time.sleep(3) # require another heartbeat beyond the observed blast
 wait(durable,'Explosion durability not acknowledged',10)
 check('Real creeper blast captured and durable queue acknowledged')
 workers[owner].kill();workers[owner].wait(timeout=10)
 wait(lambda:(e:=synchronized(1)) and e['ownerWorkerId']==follower,'Environment did not fail over',30)
 def recovered_crater():
  command(workers[follower],'execute if block 8 96 8 minecraft:air run say R19_CRATER_RECOVERED')
  regs=session.get(base+'/api/regions',timeout=5).json()['regions']
  owned=any(v['id']==region and v['ownerWorkerId']==follower and v['state']=='ACTIVE' for v in regs)
  return owned and 'R19_CRATER_RECOVERED' in recent_log(follower)
 wait(recovered_crater,'Creeper terrain damage lost after hard kill',40)
 check('Hard-killed owner: survivor replayed the crater instead of original terrain')
 wait(lambda:(e:=synchronized(1)) and e['ownerWorkerId']==follower and e['dayTime']==12000 and e['raining'] and e['thundering'],'Owner failover lost time/weather',30);check('Owner shutdown retained frozen clock and thunder on the survivor')
 manager.terminate();manager.wait(timeout=30);manager=start_manager()
 wait(lambda:(e:=synchronized(1)) and e['ownerWorkerId']==follower and e['dayTime']==12000 and e['raining'] and e['thundering'],'Manager restart lost environment checkpoint',30);check('Manager restart restored environment without importing stale worker state')
 (out/'persistence-results.json').write_text(json.dumps({'passed':len(checks),'checks':checks},indent=2))
 print('Persistence integration: PASS',len(checks),'checks',flush=True)
finally:
 for proc in reversed(processes):
  if proc.poll() is None:
   try:
    if proc.stdin:command(proc,'stop');proc.wait(timeout=20)
    else:proc.terminate();proc.wait(timeout=20)
   except (OSError,subprocess.TimeoutExpired):proc.kill();proc.wait()
 for f in files:f.close()
 shutil.rmtree(home,ignore_errors=True)
