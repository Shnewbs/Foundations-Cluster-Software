"""Download the pinned official Conduit core, build and test the gateway on Windows."""
import hashlib
import os
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
jdk = Path(os.environ['JAVA_HOME'])
core = ROOT/'velocity/velocity.jar'
core.parent.mkdir(exist_ok=True)
url = 'https://github.com/tame-gg/conduit/releases/download/v1.8.0/conduit-1.8.0.jar'
expected = '60b676015e0a0d7917611f5564c05927f933eecc12c2b424784ce25738b5f7d6'
with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent':'FoundationsCluster-CI'}), timeout=120) as response:
    data = response.read()
assert hashlib.sha256(data).hexdigest() == expected, 'Conduit release checksum mismatch'
core.write_bytes(data)

def run(*cmd):
    print('+ ' + ' '.join(map(str, cmd)), flush=True)
    subprocess.run(list(map(str,cmd)),cwd=ROOT,check=True,timeout=300)

run('powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass',
    '-File',ROOT/'tools/build_gateway.ps1','-ConduitJar',core,'-JavaHome',jdk)
bridge = ROOT/'velocity-gateway/build/libs/foundations_conduit_bridge-0.0.6a.R19.jar'
tests = ROOT/'velocity-gateway/build/test-classes'
tests.mkdir(parents=True,exist_ok=True)
names = ['HandshakeReplaySelfTest','HandshakePacketIntegritySelfTest','TagReplaySelfTest','NeoForgeDeclarationSelfTest']
cp = os.pathsep.join(map(str,[bridge,core]))
run(jdk/'bin/javac.exe','--release','25','-proc:none','-encoding','UTF-8','-cp',cp,'-d',tests,
    *[ROOT/'tests'/f'{name}.java' for name in names])
for name in names:
    run(jdk/'bin/java.exe','-Xverify:all','-cp',str(tests)+os.pathsep+cp,
        'com.velocitypowered.proxy.connection.backend.'+name)
run('python',ROOT/'tests/gateway_bootstrap_test.py','--conduit-jar',core,'--jdk',jdk)
