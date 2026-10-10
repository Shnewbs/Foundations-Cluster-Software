"""Actual Manager HTTP service with synthetic agent reports, not joined Minecraft workers."""
import argparse
import base64
import http.cookiejar
import io
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java', required=True)
args = parser.parse_args()
ROOT = Path(__file__).resolve().parents[1]
HOME = Path(tempfile.mkdtemp(prefix='foundations-entity-api-'))
NODE = re.search(r'NODE_VERSION = "([^"]+)"', (ROOT/'cluster-manager/src/main/java/com/foundations/cluster/manager/ClusterVersion.java').read_text()).group(1)
with socket.socket() as port_socket:
    port_socket.bind(('127.0.0.1', 0))
    PORT = port_socket.getsockname()[1]
(HOME/'config').mkdir()
(HOME/'config/cluster.properties').write_text(f'''web.bind=127.0.0.1
web.port={PORT}
startup.autoStartWorkers=false
template.autoBootstrap=false
nodeAgent.required=false
gateway.enabled=false
world.seed=1234
world.identity.enforce=false
world.environment.enabled=false
layer.autoSplit=false
layer.autoMerge=false
region.autoFailover=false
region.recoverySeconds=0
''')
cp = os.pathsep.join(str(ROOT/p) for p in ['cluster-manager/build/test-classes','cluster-manager/build/FoundationsClusterManager.jar','cluster-manager/lib/bcprov.jar'])
credentials = HOME/'credentials.json'
subprocess.run([args.java,'-cp',cp,'com.foundations.cluster.manager.QaCredentials',str(HOME),str(credentials)],check=True,capture_output=True)
agent = json.loads(credentials.read_text())['agent']
credentials.unlink()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
log = (HOME/'manager.log').open('w')
process = subprocess.Popen([args.java,'-cp',cp,'com.foundations.cluster.manager.Main'],cwd=HOME,env={**os.environ,'FOUNDATIONS_CLUSTER_HOME':str(HOME)},stdout=log,stderr=subprocess.STDOUT)
checks = 0
success = False

def check(ok, label):
    global checks
    assert ok, label
    checks += 1

def request(path, data=None, authenticated_agent=False, method=None):
    headers = {'Content-Type':'application/json'}
    if authenticated_agent:
        headers['X-Foundations-Agent-Token'] = agent
    req = urllib.request.Request(f'http://127.0.0.1:{PORT}'+path, data=None if data is None else json.dumps(data).encode(), headers=headers, method=method)
    try:
        with client.open(req,timeout=5) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())

def wait(predicate, label):
    deadline = time.monotonic()+20
    while time.monotonic()<deadline:
        try:
            result = predicate()
            if result:
                return result
        except urllib.error.URLError:
            pass
        time.sleep(.1)
    raise AssertionError(label)

def utf(value):
    data = value.encode('ascii')  # All fixture strings are ASCII, also valid Java modified UTF.
    return struct.pack('>H',len(data))+data

def b64(data):
    return base64.urlsafe_b64encode(data).decode().rstrip('=')

def encode_state(binding, entity, x=2):
    return (binding.bytes+entity.bytes+b'\0'+utf('minecraft:overworld')+
            struct.pack('>dddfffddd?I',x,65.,2.,45.,10.,60.,.1,0.,0.,True,2)+b'\0\xff')

def bundle(sequence, states=(), players=(), automatic=False):
    data = struct.pack('>IBBqI',0x46434556,2,1,sequence,len(states))+b''.join(states)+struct.pack('>I',len(players))
    for ident,name,dim,x in players:
        data += ident.bytes+utf(name)+utf(dim)+struct.pack('>ddd',x,65.,2.)
    data += struct.pack('>?',automatic)
    return b64(data)

class Decoder:
    def __init__(self, text):
        self.stream=io.BytesIO(base64.urlsafe_b64decode(text+'='*((-len(text))%4)))
    def raw(self,n):
        data=self.stream.read(n)
        assert len(data)==n, 'Truncated fixture response'
        return data
    def read(self,fmt):
        return struct.unpack('>'+fmt,self.raw(struct.calcsize('>'+fmt)))[0]
    def uid(self):
        return uuid.UUID(bytes=self.raw(16))
    def text(self):
        return self.raw(self.read('H')).decode('ascii')
    def pair(self):
        return dict(binding=self.uid(),entity=self.uid(),worker=self.text(),identity=self.text(),seed=self.read('q'),dimension=self.text(),kind=self.read('B'),interest=self.read('?'))
    def response(self):
        assert (self.read('I'),self.read('B'),self.read('B'))==(0x46434556,2,2)
        result=dict(sequence=self.read('q'),boot=self.uid())
        result['owned']=[self.pair() for _ in range(self.read('I'))]
        viewers=[]
        for _ in range(self.read('I')):
            v=dict(uuid=self.uid(),samples=[])
            for _ in range(self.read('I')):
                pair=self.pair()
                state=dict(binding=self.uid(),entity=self.uid(),kind=self.read('B'),dimension=self.text())
                state.update(zip(['x','y','z','yaw','pitch','headYaw','vx','vy','vz','onGround'],struct.unpack('>dddfffddd?',self.raw(61))))
                state['metadata']=self.raw(self.read('I'))
                v['samples'].append(dict(pair=pair,state=state,instance=self.uid(),sequence=self.read('q'),remaining=self.read('q')))
            viewers.append(v)
        result['viewers']=viewers
        result['interests']=[dict(uuid=self.uid(),name=self.text(),dimension=self.text(),x=self.read('d'),y=self.read('d'),z=self.read('d')) for _ in range(self.read('I'))]
        assert self.stream.read()==b'', 'Unexpected trailing response data'
        return result

instances={w:str(uuid.uuid4()) for w in ['worker-001','worker-002']}
reports={w:dict(workerId=w,port=25595+i,ready=True,worldSeed=1234,worldIdentity='fixture-world-identity',worldIdentitySchema=2,nodeVersion=NODE,minecraftVersion='1.21.1',persistenceInstance=instances[w],pendingWrites=0,failedWrites=0,lastDurableWrite=0,players=1) for i,w in enumerate(instances)}

def heartbeat(w, **overrides):
    return request('/api/agent/heartbeat',{**reports[w],**overrides},True)

def action(w, name, **extra):
    return request('/api/agent/entity-views',dict(workerId=w,instance=instances[w],action=name,**extra),True)

def exchange(w, seq, states=(), players=(), automatic=False):
    status,body=action(w,'exchange',bundle=bundle(seq,states,players,automatic))
    assert status==200,(status,body)
    return Decoder(body['bundle']).response()

try:
    wait(lambda: request('/api/health')[0]==200,'Manager unavailable')
    check(request('/api/auth/setup',dict(username='fixture',password='Rev2-disposable-test-password',remember=False))[0]==201,'Set up fixture admin')
    for w in instances:
        code,body=request('/api/agent/register',reports[w],True)
        check(code==200,'Register agent '+str(body))
    for w,name in [('worker-001','Source'),('worker-002','Viewer')]:
        code,layer=request('/api/layers/create',dict(workerId=w,worldId='foundations',dimensionId='minecraft:overworld',cellX=0,cellZ=0))
        check(code==201,'Create source-specific layer '+str(layer))
        code,placement=request('/api/sessions/place',dict(playerId=name,sessionId=name,targetLayerId=layer['id'],worldId='foundations',dimensionId='minecraft:overworld',cellX=0,cellZ=0))
        check(code==200 and placement['anchorWorkerId']==w,'Anchor fixture session '+str(placement))
    region='foundations/minecraft:overworld/r.0.0'
    def region_record():
        return next(r for r in request('/api/regions')[1]['regions'] if r['id']==region)
    rec=region_record()
    check(rec['ownerWorkerId']=='worker-001','Source owns entity region')
    hydration=b64(region.encode())+'|'+str(rec['epoch'])
    reports['worker-001']['hydratedRegions']=hydration
    heartbeat('worker-001')
    wait(lambda:region_record()['state']=='ACTIVE','Source region not active')
    entity=uuid.uuid4(); viewer=uuid.uuid4()
    pos=(viewer,'Viewer','minecraft:overworld',3.)
    initial=bundle(1,[encode_state(uuid.UUID(int=0),entity)])
    check(request('/api/agent/entity-views',dict(action='pair',bundle=initial))[0] in (401,403),'Admin cookie does not replace worker authentication')
    check(request('/api/agent/entity-views',None,True)[0]==405,'GET cannot mutate entity view service')
    check(action('worker-002','pair',operatorName='Source',bundle=initial)[0]==403,'Cannot claim operator on another anchor')
    check(action('worker-002','pair',operatorName='Viewer',bundle=initial)[0]==409,'Replica cannot pair source-owned region')
    code,pair=action('worker-001','pair',operatorName='Source',bundle=initial)
    check(code==200,'Pair source entity '+str(pair)); binding=uuid.UUID(pair['binding'])
    check(action('worker-001','pair',operatorName='Source',bundle=initial)[1]['binding']==str(binding),'Pair retry retains binding')
    frame=encode_state(binding,entity)
    exchange('worker-001',1,[frame])
    check(exchange('worker-002',1,players=[pos])['viewers']==[],'Presentation requires viewer opt-in')
    check(action('worker-002','view',operatorName='Viewer',playerUuid=str(viewer),enabled='true')[0]==200,'Opt in viewer')
    exchange('worker-001',2,[frame])
    response=exchange('worker-002',2,players=[pos]); sample=response['viewers'][0]['samples'][0]
    check(sample['state']['entity']==entity and sample['state']['x']==2.,'Binary relay preserves exact UUID and coordinates')
    check(sample['instance']==uuid.UUID(instances['worker-001']),'Response identifies source process')
    check(exchange('worker-001',3,[frame])['owned'][0]['interest'],'Viewer demand returns source ticket interest')
    check(action('worker-001','exchange',bundle=bundle(3,[encode_state(binding,entity,900)]))[0]==409,'Old frame rejected')
    check(action('worker-002','exchange',bundle=bundle(3,[frame]))[0]==403,'Wrong source rejected')
    check(exchange('worker-002',3,players=[pos])['viewers'][0]['samples'][0]['state']['x']==2.,'Rejected frame did not modify location')
    check(exchange('worker-002',4,players=[(viewer,'Unanchored','minecraft:overworld',3.)])['viewers']==[],'Unanchored player observation ignored')
    for seq,dim,x in [(5,'minecraft:the_nether',3.),(6,'minecraft:overworld',200.)]:
        check(exchange('worker-002',seq,players=[(viewer,'Viewer',dim,x)])['viewers'][0]['samples']==[],'Wrong dimension/distance hides preview')
    check(request('/api/agent/entity-views',dict(workerId='worker-001',instance=str(uuid.uuid4()),action='list',operatorName='Source'),True)[0]==403,'Stale process refused')
    check(action('worker-001','exchange',bundle='bad!!!')[0]==409,'Invalid binary body refused')
    heartbeat('worker-001',nodeVersion='0.0.6a.R14')
    check(action('worker-001','list',operatorName='Source')[0]==403,'Older Node cannot send paired views')
    check(exchange('worker-002',7,players=[pos])['viewers'][0]['samples']==[],'Unhealthy source clears cached presentation')
    heartbeat('worker-001'); exchange('worker-001',4,[frame])
    check(action('worker-002','view',operatorName='Viewer',playerUuid=str(viewer),enabled='false')[0]==200,'Turn view off')
    check(exchange('worker-002',8,players=[pos])['viewers']==[],'Disabled viewer receives no preview')
    check(action('worker-002','unpair',operatorName='Viewer',entity=str(entity))[1]['removed'],'Unpair metadata explicitly')
    exchange('worker-001',5,[frame])
    status=request('/api/entity-views/status')[1]
    check(status['paired']==0 and status['fresh']==0,'Old in-flight frame cannot resurrect unpaired view')
    check(status['entityOwnershipTransfer'] is False and status['remotePickup'] is False,'Status never claims gameplay ownership or pickup')
    # Automatic discovery uses one worker-mode flag, never manual per-entity pairing.
    check(action('worker-002','view',operatorName='Viewer',playerUuid=str(viewer),enabled='true')[0]==200,'Opt in to automatic views')
    exchange('worker-002',9,players=[pos],automatic=True)
    interests=exchange('worker-001',6,automatic=True)['interests']
    check(len(interests)==1 and interests[0]['uuid']==viewer,'Manager offers a remote interest location for automatic source discovery')
    new_uuid=uuid.uuid4()
    auto_name=('foundations:entity-preview:auto-v1:worker-001:'+str(new_uuid)).encode()
    raw=bytearray(hashlib.md5(auto_name).digest());raw[6]=(raw[6]&15)|48;raw[8]=(raw[8]&63)|128
    auto_binding=uuid.UUID(bytes=bytes(raw))
    auto_state=encode_state(auto_binding,new_uuid,3.)
    check(action('worker-002','exchange',bundle=bundle(10,[encode_state(uuid.uuid4(),new_uuid)],[],True))[0] in (403,409),'Non-owner cannot publish automatically')
    check(action('worker-001','exchange',bundle=bundle(7,[encode_state(uuid.uuid4(),new_uuid)],[],True))[0] in (403,409),'Fake automatic binding refused')
    response=exchange('worker-001',7,[auto_state],automatic=True)
    check(response['owned'][0]['entity']==new_uuid and response['owned'][0]['interest'],'Source auto record is ephemeral and wanted by remote viewer')
    viewer_reply=exchange('worker-002',10,players=[pos],automatic=True)
    check(viewer_reply['viewers'][0]['samples'][0]['state']['entity']==new_uuid,'Automatic UUID appears on destination without manual pair')
    state=request('/api/entity-views/status')[1]
    check(state['paired']==0 and state['automaticFresh']==1,'Auto view never creates durable pairing metadata')
    exchange('worker-001',8,automatic=True)
    check(exchange('worker-002',11,players=[pos],automatic=True)['viewers'][0]['samples']==[],'Absent entity removed from remote presentation')
    success=True
    print(f'Entity-view Manager HTTP contract: PASS ({checks} checks; synthetic agent reports, no Minecraft entity transfer)')
finally:
    process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill();process.wait()
    log.close()
    if success:
        shutil.rmtree(HOME)
    else:
        print('Fixture logs retained:',HOME)
