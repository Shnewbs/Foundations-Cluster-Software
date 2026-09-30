"""Exercise the real gateway setup script offline, with fixture network responses.

Requires PowerShell and an existing Conduit 1.8.0 JAR, not a running server.
Native Windows Java download/extraction is outside this fixture's scope.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--conduit-jar', required=True, type=Path)
parser.add_argument('--powershell', default=shutil.which('pwsh') or shutil.which('powershell'))
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
assert args.powershell, 'PowerShell is required'
core = args.conduit_jar.resolve()
checks = 0


def check(ok, message):
    global checks
    assert ok, message
    checks += 1


def digest(path):
    return hashlib.sha256(path.read_bytes()).digest()


with tempfile.TemporaryDirectory(prefix='Foundations bootstrap test ') as td:
    fixture = Path(td) / 'Cluster with spaces'
    for rel in ['tools/setup_velocity.ps1', 'tools/conduit-r15-patch/SeamlessConfigSessionHandler.class',
                'velocity-gateway/build/libs/foundations_velocity_gateway-0.0.6a.R19.jar',
                'velocity-gateway/source/FoundationsVelocityGateway.java', 'velocity-gateway/resources/velocity-plugin.json']:
        dst = fixture / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / rel, dst)
    for extra in (root/'tools/conduit-r15-patch').glob('FoundationsHandshakeCapture*.class'):
        shutil.copy2(extra,fixture/'tools/conduit-r15-patch'/extra.name)
    for rel, data in {'config/cluster.properties': b'gateway.seamless.enabled=false\n',
                      'velocity/runtime/java25/bin/java.exe': b'fixture metadata only',
                      'velocity/runtime/java25/release': b'JAVA_VERSION="25.0.1"\n',
                      'workers/worker-001/server.properties': b'online-mode=true\n',
                      'workers/worker-001/world/sentinel': b'KEEP WORLD'}.items():
        dst = fixture / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(data)
    forwarding = Path(td) / 'pcf-fixture.jar'
    with zipfile.ZipFile(forwarding, 'w') as z:
        z.writestr('META-INF/neoforge.mods.toml', 'modId = "pcf"\nversion = "1.2.2"\n')
    wrapper = Path(td) / 'run.ps1'
    wrapper.write_text('''$ErrorActionPreference='Stop'
function Invoke-RestMethod {
  param($Headers,$Uri,$TimeoutSec)
  if($Uri -notlike 'https://api.github.com/repos/tame-gg/conduit/releases/tags/*'){throw "Unexpected network metadata request: $Uri"}
  return @{assets=@(@{name='conduit-1.8.0.jar';size=50000000;browser_download_url='https://fixture.invalid/conduit.jar'})}
}
function Invoke-WebRequest {
  param([switch]$UseBasicParsing,$Headers,$Uri,$OutFile,$TimeoutSec)
  if($Uri -eq 'https://fixture.invalid/conduit.jar'){Copy-Item -LiteralPath $env:FIXTURE_CORE -Destination $OutFile;return}
  if($Uri -eq 'https://cdn.modrinth.com/data/vDyrHl8l/versions/mNsCcGDZ/proxy-compatible-forge-1.2.2.jar'){Copy-Item -LiteralPath $env:FIXTURE_PCF -Destination $OutFile;return}
  throw "Unexpected network download: $Uri"
}
& (Join-Path $env:FIXTURE_ROOT 'tools/setup_velocity.ps1')
''')

    def run():
        return subprocess.run([args.powershell, '-NoLogo', '-NoProfile', '-File', str(wrapper)],
                              env=dict(os.environ, FIXTURE_ROOT=str(fixture), FIXTURE_CORE=str(core), FIXTURE_PCF=str(forwarding)),
                              capture_output=True, text=True, timeout=90)

    for scenario in ['fresh core', 'cached core', 'corrupt installed plugin', 'corrupt forwarding mod']:
        if scenario == 'corrupt installed plugin':
            (fixture / 'velocity/plugins/foundations_velocity_gateway-0.0.6a.R19.jar').write_bytes(b'broken')
        if scenario == 'corrupt forwarding mod':
            (fixture / 'server-template/mods/proxy-compatible-forge-1.2.2.jar').write_bytes(b'partial download')
        result = run()
        check(result.returncode == 0, scenario + '\n' + result.stdout + result.stderr)
        check('Building Foundations Velocity' not in result.stdout, 'Bundled plugin was ignored')
        target = fixture / 'velocity/plugins/foundations_velocity_gateway-0.0.6a.R19.jar'
        check(digest(target) == digest(root / 'velocity-gateway/build/libs' / target.name), 'Wrong plugin installed')
        with zipfile.ZipFile(fixture / 'velocity/velocity.jar') as z:
            check(z.read('com/velocitypowered/proxy/connection/backend/SeamlessConfigSessionHandler.class') ==
                  (root / 'tools/conduit-r15-patch/SeamlessConfigSessionHandler.class').read_bytes(), 'Overlay mismatch '+scenario+' '+result.stdout+result.stderr)
        with zipfile.ZipFile(fixture/'velocity/velocity.jar') as z:
            for extra in (root/'tools/conduit-r15-patch').glob('FoundationsHandshakeCapture*.class'):
                check(z.read('com/velocitypowered/proxy/connection/backend/'+extra.name)==extra.read_bytes(),'Capture class missing or corrupt: '+extra.name)
        check('seamless-server-switches = false'  in (fixture / 'velocity/conduit.toml').read_text(), 'Normal mode not applied')
        check(digest(fixture / 'server-template/mods/proxy-compatible-forge-1.2.2.jar') == digest(forwarding), 'Forwarding dependency not installed or repaired')
    # Failed dependency download must not leave a partial .jar that later looks installed.
    (fixture / 'server-template/mods/proxy-compatible-forge-1.2.2.jar').unlink()
    forwarding.write_bytes(b'failed remote download')
    result = run()
    check(result.returncode != 0, 'Corrupt dependency download accepted')
    check(not (fixture / 'server-template/mods/proxy-compatible-forge-1.2.2.jar').exists(), 'Partial dependency installed')
    check(not (fixture / 'server-template/mods/proxy-compatible-forge-1.2.2.jar.download').exists(), 'Staging file leaked')
    secret = (fixture / 'velocity/forwarding.secret').read_bytes()
    check((fixture / 'server-template/forwarding.secret').read_bytes() == secret, 'Template secret mismatch')
    check((fixture / 'workers/worker-001/forwarding.secret').read_bytes() == secret, 'Worker secret mismatch')
    check((fixture / 'workers/worker-001/world/sentinel').read_bytes() == b'KEEP WORLD', 'World changed')
    before = digest(fixture / 'velocity/velocity.jar')
    (fixture / 'tools/conduit-r15-patch/SeamlessConfigSessionHandler.class').unlink()
    result = run()
    check(result.returncode != 0, 'Missing patch class accepted')
    check(digest(fixture / 'velocity/velocity.jar') == before, 'Failed setup damaged the core')
print(f'Gateway bootstrap: PASS ({checks} assertions)')
