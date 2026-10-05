"""Real HTTP regression: reconnecting must not reset administrator login throttling."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

p=argparse.ArgumentParser();p.add_argument('--java',required=True);args=p.parse_args()
root=Path(__file__).resolve().parents[1];home=Path(tempfile.mkdtemp(prefix='foundations-security-http-'))
(home/'config').mkdir()
(home/'config/cluster.properties').write_text('web.bind=127.0.0.1\nweb.port=18778\nstartup.autoStartWorkers=false\ntemplate.autoBootstrap=false\nnodeAgent.required=false\ngateway.enabled=false\nworld.seed=1234\nworld.environment.enabled=false\n',encoding='utf-8')
cp=os.pathsep.join(str(root/x) for x in ['cluster-manager/build/FoundationsClusterManager.jar','cluster-manager/lib/bcprov.jar'])
log=(home/'http-test.log').open('w');env={**os.environ,'FOUNDATIONS_CLUSTER_HOME':str(home)}
proc=subprocess.Popen([args.java,'-cp',cp,'com.foundations.cluster.manager.Main'],cwd=home,env=env,stdout=log,stderr=subprocess.STDOUT)
checks=0

def req(path,body=None,raw=None):
    data=raw if raw is not None else None if body is None else json.dumps(body).encode()
    request=urllib.request.Request('http://127.0.0.1:18778'+path,data=data,headers={'Content-Type':'application/json','Connection':'close'})
    try:
        with urllib.request.urlopen(request,timeout=10) as r:return r.code,json.loads(r.read()),r.headers
    except urllib.error.HTTPError as e:return e.code,json.loads(e.read()),e.headers

def check(ok,why):
    global checks
    assert ok,why;checks+=1
try:
    deadline=time.monotonic()+25
    while True:
        try:
            if req('/api/auth/status')[0]==200:break
        except urllib.error.URLError:pass
        if proc.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Manager did not start; '+(home/'http-test.log').read_text())
        time.sleep(.1)
    check(req('/api/auth/setup',raw=b'{"username":"operator"')[0]==400,'Malformed JSON accepted')
    check(req('/api/auth/setup',raw=b'{"username":"operator","password":"'+b'x'*9000+b'"}')[0]==400,'Oversize credential request accepted')
    check(req('/api/auth/status')[1]['setupRequired'],'Rejected requests changed setup state')
    login={'username':'operator','password':'security-test-only-password','remember':True}
    status,_,headers=req('/api/auth/setup',login);check(status==201,'Setup failed')
    check('HttpOnly' in headers['Set-Cookie'] and 'SameSite=Strict' in headers['Set-Cookie'],'Missing cookie protections')
    check(req('/api/agent/heartbeat',{})[0]==401,'Unauthenticated worker accepted')
    for _ in range(5):check(req('/api/auth/login',{**login,'password':'wrong-password'})[0]==401,'Bad password accepted')
    status,body,_=req('/api/auth/login',login)
    check(status==401 and 'Too many' in body.get('error',''),'New TCP connection bypassed login lockout')
    proc.terminate();proc.wait(timeout=30)
    (home/'runtime/update-in-progress.json').write_text('{"backup":"fixture"}',encoding='utf-8')
    blocked=subprocess.run([args.java,'-cp',cp,'com.foundations.cluster.manager.Main'],cwd=home,env=env,capture_output=True,text=True,timeout=15)
    check(blocked.returncode!=0 and 'update was interrupted' in blocked.stderr,'Interrupted update did not block startup')
    print(f'Security HTTP: PASS ({checks} checks)')
finally:
    if proc.poll() is None:
        proc.terminate()
        try:proc.wait(timeout=30)
        except subprocess.TimeoutExpired:proc.kill();proc.wait()
    log.close();shutil.rmtree(home)
