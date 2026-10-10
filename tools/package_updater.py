#!/usr/bin/env python3
"""Package reviewed application files only; never glob an operator installation."""
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT=Path(__file__).resolve().parents[1]
version=re.search(r'PACKAGE_REVISION = "([^"]+)"',(ROOT/'cluster-manager/src/main/java/com/foundations/cluster/manager/ClusterVersion.java').read_text()).group(1)
assert version=='0.0.7a-dev.2.R6','Review updater compatibility before changing the package version'
# Cumulative application overlay: bring every supported old source/build-script
# baseline forward in one step. Never package operator state, world data, server
# templates or runtime. Take the inventory from the exact Git checkout in CI.
import subprocess
SAFE_TOP={
    'tools','cluster-manager','minecraft-agent','web','velocity-gateway','protocol',
    'playtest','node-simulator','reference','desktop','docs','licenses','release',
    'release_notes','tests',
}
BUILT={
    'cluster-manager/build/FoundationsClusterManager.jar',
    'velocity-gateway/build/libs/foundations_conduit_bridge-0.0.6a.R19.jar',
    'velocity-gateway/build/libs/foundations_velocity_gateway-0.0.6a.R19.jar',
    'minecraft-agent/build/libs/foundations_cluster_node-0.0.6a.R19.jar',
}
PROTECTED_TOP={'runtime','workers','server-template','logs','update-backups','config','velocity'}
def approved_source(path):
    if not path or path.startswith('.') or '..' in Path(path).parts or any(c in path for c in ('\\','//',':')):
        return False
    bits=path.split('/')
    if bits[0] in PROTECTED_TOP or path in BUILT or 'build' in bits or 'dist' in bits:
        return False
    if bits[0] in SAFE_TOP:
        return True
    return len(bits)==1 and (path.endswith(('.bat','.sh','.md','.txt')))
try:
    # Github Actions includes the checkout metadata. Fail closed if missing:
    # users should use the prebuilt updater, not package arbitrary local files.
    raw=subprocess.check_output(['git','-C',str(ROOT),'ls-files','-z'],stderr=subprocess.DEVNULL)
    tracked=[p.decode('utf-8') for p in raw.split(b'\x00') if p]
except (OSError,subprocess.CalledProcessError):
    raise SystemExit('Build the cumulative installer from a clean Git checkout; ordinary users should use the release updater.')
app_sources=sorted(p for p in tracked if approved_source(p))
paths=sorted(set(app_sources)|BUILT)
assert len(paths)>=300,'Incomplete cumulative source inventory'
assert len(paths)==len(set(paths)) and all(p.isascii() and (ROOT/p).is_file() for p in paths), 'Missing or unsafe app files'
for must in ('tools/ensure_jdk21.ps1','tools/setup_velocity.ps1','minecraft-agent/gradle.properties',
             'minecraft-agent/build.gradle','minecraft-agent/build_mod.bat',
             'minecraft-agent/src/main/templates/FoundationsClusterNode.java.template',
             'cluster-manager/src/main/java/com/foundations/cluster/manager/ClusterVersion.java'):
    assert must in paths, 'Cumulative upgrade missing required source/build file: '+must

# Ship an *exact* hardcoded allowlist in the applicator. A tampered manifest
# cannot request changing config/, runtime/, worlds, arbitrary source paths etc.
applicator_template=(ROOT/'tools/apply_update.ps1').read_text()
token="'__PACKAGE_ALLOWLIST__'"
assert applicator_template.count(token)==1, 'Upgrader allowlist template invalid'
allowlist=',\n'.join("'"+p+"'" for p in paths)
applicator=applicator_template.replace(token,allowlist).encode('utf-8')

def payload_bytes(path):
    return applicator if path=='tools/apply_update.ps1' else (ROOT/path).read_bytes()
# Fail packaging if a clean checkout did not actually compile the gateway.
prefix='com/velocitypowered/proxy/connection/backend/'
required=['SeamlessConfigSessionHandler.class','FoundationsHandshakeCapture.class',
          'FoundationsHandshakeCapture$Transcript.class','FoundationsHandshakeCapture$Cursor.class',
          'FoundationsHandshakeCapture$1.class','FoundationsTagConfiguration.class','FoundationsNeoForgeConfiguration.class']
with zipfile.ZipFile(ROOT/'velocity-gateway/build/libs/foundations_conduit_bridge-0.0.6a.R19.jar') as bridge:
    assert all(prefix+n in bridge.namelist() for n in required),'Build the complete R19 bridge first'
    for name in bridge.namelist():
        if name.endswith('.class'):
            assert re.fullmatch(re.escape(prefix)+r'(?:SeamlessConfigSessionHandler|FoundationsHandshakeCapture|FoundationsTagConfiguration|FoundationsNeoForgeConfiguration)(?:\$[^/]+)?\.class',name),'Unexpected bridge class'
            assert bridge.read(name)[:8]==bytes.fromhex('cafebabe00000045'),'Bridge must target Java 25'
with zipfile.ZipFile(ROOT/'velocity-gateway/build/libs/foundations_velocity_gateway-0.0.6a.R19.jar') as plugin:
    meta=json.loads(plugin.read('velocity-plugin.json'))
    assert meta['id']=='foundations_gateway' and meta['version']=='0.0.6a.R19','Wrong gateway metadata'
    assert plugin.read('com/foundations/cluster/velocity/FoundationsVelocityGateway.class')[:8]==bytes.fromhex('cafebabe00000041'),'Gateway plugin must target Java 21'
# Node is compiled and exercised on both CI platforms before packaging.
with zipfile.ZipFile(ROOT/'minecraft-agent/build/libs/foundations_cluster_node-0.0.6a.R19.jar') as node:
    assert 'version="0.0.6a.R19"' in node.read('META-INF/neoforge.mods.toml').decode()
    assert node.read('com/foundations/cluster/node/FoundationsClusterNode.class')[:8]==bytes.fromhex('cafebabe00000041')
    assert 'com/foundations/cluster/node/interaction/AuthorityBreakExecutor.class' in node.namelist()
    assert node.read('com/foundations/cluster/playtest/FoundationsPlaytest.class')[:8]==bytes.fromhex('cafebabe00000041'), 'Live recorder missing'
    for name in ['entity/AutomaticEntityDiscovery','entity/EntityViewPackets','entity/EntityViewRenderer','entity/PairedEntityViews','entity/EntityViewsBootstrap','entity/EntityContinuationCodec','mixin/EntityDataSnapshotAccessor','mixin/EntityIdAllocatorAccessor']:
        assert node.read('com/foundations/cluster/node/'+name+'.class')[:8]==bytes.fromhex('cafebabe00000041'), 'Paired entity runtime class missing'
    for name in ['com/foundations/cluster/node/HandoffChunkPreloader.class',
                 'com/foundations/cluster/node/entity/EntityContinuationCodec.class']:
        assert node.read(name)[:8]==bytes.fromhex('cafebabe00000041'), 'R5 adapter not compiled into Node'
    mixins=json.loads(node.read('foundations_cluster_node.mixins.json'))['mixins']
    assert 'EntityDataSnapshotAccessor' in mixins and 'EntityIdAllocatorAccessor' in mixins
    assert not any('/spike/' in n or 'foundations_cluster_spike' in n for n in node.namelist()), 'Test mod must not ship to operator worlds'
files=[{'path':path,'sha256':hashlib.sha256(payload_bytes(path)).hexdigest()} for path in paths]
manifest={'schema':1,'component':'manager-gateway-node','version':version,'requires':'Direct from R19-HF11 through HF16 or 7a-dev.2/R2-R6; all cumulative app files and prebuilt R19 Node, Manager, gateway' ,'upgradeMode':'cumulative','files':files}
output=ROOT/'dist';output.mkdir(exist_ok=True)
archive=output/f'FoundationsCluster-{version}-UPDATER.zip'
launcher='''@echo off
setlocal
set /p "CLUSTER_INSTALL=Full path to your stopped Cluster folder: "
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\\apply_update.ps1" -ClusterRoot "%CLUSTER_INSTALL%"
if errorlevel 1 echo Update failed. Read the message above before starting Cluster.
pause
'''
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('UPDATE_MANIFEST.json',json.dumps(manifest,indent=2)+'\n')
    z.writestr('APPLY_UPDATE.bat',launcher.replace('\n','\r\n'))
    z.write(ROOT/'release/UPDATE_README.txt','README.txt')
    z.write(ROOT/'docs/PLAYTEST_DEV2_GUIDE.md','PLAYTEST_GUIDE.md')
    z.write(ROOT/'docs/ENTITY_AUTO_DISCOVERY_R3_GUIDE.md','ENTITY_VIEWS_GUIDE.md')
    z.write(ROOT/'docs/ENTITY_TRANSFER_R4_GATES.md','ENTITY_TRANSFER_R4_GATES.md')
    z.write(ROOT/'docs/HANDOFF_AND_ENTITY_STATE_R5_GUIDE.md','R5_GUIDE.md')
    z.write(ROOT/'docs/status/0_0_7A_DEV2_R6.md','DIRECT_UPGRADE_R6.md')
    z.write(ROOT/'tools/summarize_playtest.py','summarize_playtest.py')
    z.writestr('tools/apply_update.ps1',applicator)
    for path in paths:z.writestr('payload/'+path,payload_bytes(path))
digest=hashlib.sha256(archive.read_bytes()).hexdigest()
(archive.with_suffix('.zip.sha256')).write_text(digest+'  '+archive.name+'\n',encoding='ascii')
print(archive)
