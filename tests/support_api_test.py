"""Real authenticated HTTP support captures, exports and opt-in automation."""
import argparse, http.cookiejar, io, json, os, pathlib, shutil, subprocess, tempfile, time, urllib.request, urllib.error, zipfile
p=argparse.ArgumentParser();p.add_argument('--java',required=True);args=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];home=pathlib.Path(tempfile.mkdtemp(prefix='foundations-support-api-'));(home/'config').mkdir()
(home/'config/cluster.properties').write_text('web.bind=127.0.0.1\nweb.port=18774\nstartup.autoStartWorkers=false\ntemplate.autoBootstrap=false\nnodeAgent.required=false\ngateway.enabled=false\nworld.seed=1234\nlayer.autoSplit=false\nlayer.autoMerge=false\nregion.autoFailover=false\n')
(home/'logs').mkdir();(home/'logs/fixture.log').write_text('Support regression marker\npassword=fixture-must-be-redacted\n')
cp=os.pathsep.join(str(root/x) for x in ['cluster-manager/build/FoundationsClusterManager.jar','cluster-manager/lib/bcprov.jar'])
log=(home/'manager-output.log').open('w');process=subprocess.Popen([args.java,'-cp',cp,'com.foundations.cluster.manager.Main'],cwd=home,env={**os.environ,'FOUNDATIONS_CLUSTER_HOME':str(home)},stdout=log,stderr=subprocess.STDOUT)
client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));checks=[]
def req(path,data=None,method=None,raw=False):
 r=urllib.request.Request('http://127.0.0.1:18774'+path,data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json'},method=method)
 try:
  with client.open(r,timeout=10) as response:
   body=response.read();return response.status,body if raw else json.loads(body)
 except urllib.error.HTTPError as e:return e.code,json.loads(e.read())
def check(ok,label):assert ok,label;checks.append(label)
def wait(fn,label):
 end=time.monotonic()+15
 while time.monotonic()<end:
  try:
   if value:=fn():return value
  except urllib.error.URLError:pass
  time.sleep(.1)
 raise AssertionError(label)
try:
 wait(lambda:req('/api/health')[0]==200,'Manager not ready')
 check(req('/api/support')[0]==401,'Archive list requires admin')
 check(req('/api/support',{})[0]==401,'Capture requires admin')
 check(req('/api/support/download?name=anything')[0]==401,'Download requires admin')
 check(req('/api/auth/setup',{'username':'fixture','password':'Support-fixture-disposable-only','remember':False})[0]==201,'Administrator created')
 check(req('/api/support',{'reason':'test-failure'})==(200,{'accepted':False}),'Failure automation disabled by default')
 check(req('/api/support',{'reason':'anything'})[0]==409,'Invalid capture reason rejected')
 check(req('/api/support/download?name=../config/cluster.properties')[0]==400,'Download traversal blocked')
 check(req('/api/config',{'support.keepCount':'0'},'PUT')[0]==400,'Unsafe retention count rejected')
 check(req('/api/config',{'support.keepCount':'1','support.onTestFailure':'true'},'PUT')[0]==200,'Preferences saved')
 check(req('/api/support',{'reason':'manual','report':'FAIL region RECOVERING'})[0]==202,'Manual capture accepted')
 state=wait(lambda:(s if not (s:=req('/api/support')[1])['busy'] else None),'Capture did not complete')
 check(not state['lastError'] and len(state['archives'])==1,'Capture listed without errors')
 status,data=req('/api/support/download?name='+state['lastCreated'],raw=True);check(status==200,'ZIP downloaded')
 with zipfile.ZipFile(io.BytesIO(data)) as z:
  manifest=json.loads(z.read('manifest.json'));check(manifest['package']=='0.0.6a.R19-HF14','Archive identifies hotfix')
  check(b'Support regression marker' in z.read('logs/fixture.log') and b'fixture-must-be-redacted' not in z.read('logs/fixture.log'),'Logs included and credential label redacted')
  check(b'FAIL region RECOVERING' in z.read('test-report.txt'),'Failure report included')
  check(all(not n.startswith(('runtime/security','config/','workers/')) for n in z.namelist()),'Excluded files absent from real export')
  check('runtime/persistent-blocks.tsv' in z.namelist() and 'runtime/persistent-block-entities.tsv' in z.namelist(),'Both journals included')
 check(req('/api/support',{'reason':'test-failure','report':'FAIL tree'})==(202,{'accepted':True}),'Enabled failure capture accepted')
 new=wait(lambda:(s if not (s:=req('/api/support')[1])['busy'] else None),'Failure capture did not complete')
 check(new['lastCreated']!=state['lastCreated'] and len(new['archives'])==1,'New capture retained with configured limit')
 check(req('/api/support/download?name='+new['lastCreated'],raw=True)[0]==200,'Most recent same-second ZIP survives retention')
 print('Support HTTP integration: PASS ('+str(len(checks))+' checks)')
finally:
 process.terminate()
 try:process.wait(timeout=15)
 except subprocess.TimeoutExpired:process.kill();process.wait()
 log.close();shutil.rmtree(home)
