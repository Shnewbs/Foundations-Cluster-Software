#!/usr/bin/env python3
"""Package the reviewed control-plane and gateway files; never glob an installation."""
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT=Path(__file__).resolve().parents[1]
version=re.search(r'PACKAGE_REVISION = "([^"]+)"',(ROOT/'cluster-manager/src/main/java/com/foundations/cluster/manager/ClusterVersion.java').read_text()).group(1)
assert version=='0.0.6a.R19-HF14','Review updater compatibility before changing the package version'
paths=["tools/start_foundations.ps1","cluster-manager/lib/bcprov.jar","cluster-manager/build/FoundationsClusterManager.jar","tools/setup_velocity.ps1","tools/build_gateway.ps1","velocity-gateway/build/libs/foundations_conduit_bridge-0.0.6a.R19.jar","velocity-gateway/build/libs/foundations_velocity_gateway-0.0.6a.R19.jar","tools/conduit-r15-patch/SeamlessConfigSessionHandler.java","tools/conduit-r15-patch/FoundationsHandshakeCapture.java","tools/conduit-r15-patch/FoundationsTagConfiguration.java","tools/conduit-r15-patch/FoundationsNeoForgeConfiguration.java","tools/conduit-r15-patch/GPL-3.0.txt","tools/conduit-r15-patch/README.txt","velocity-gateway/source/FoundationsVelocityGateway.java","velocity-gateway/resources/velocity-plugin.json","web/app.js","web/index.html"]
manager=ROOT/'cluster-manager/build/FoundationsClusterManager.jar'
with zipfile.ZipFile(manager) as jar:
    data=jar.read('com/foundations/cluster/manager/ClusterVersion.class')
    assert data[:4]==bytes.fromhex('cafebabe') and version.encode() in data,'Build the matching Manager first'
    assert int.from_bytes(data[6:8],'big')==65,'Manager must target Java 21'
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
files=[{'path':path,'sha256':hashlib.sha256((ROOT/path).read_bytes()).hexdigest()} for path in paths]
manifest={'schema':1,'component':'manager-gateway','version':version,'requires':'0.0.6a.R19-HF11 or HF12 or HF13 or HF14 / Node R13','files':files}
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
    z.write(ROOT/'tools/apply_update.ps1','tools/apply_update.ps1')
    for path in paths:z.write(ROOT/path,'payload/'+path)
digest=hashlib.sha256(archive.read_bytes()).hexdigest()
(archive.with_suffix('.zip.sha256')).write_text(digest+'  '+archive.name+'\n',encoding='ascii')
print(archive)
