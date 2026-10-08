"""Exercise real Windows gateway compilation/bootstrap with fixture downloads.

Uses the real pinned Conduit JAR and JDK 25, but never starts a live server.
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
parser.add_argument('--jdk', required=True, type=Path)
parser.add_argument('--powershell', default=shutil.which('powershell') or shutil.which('pwsh'))
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
assert args.powershell, 'PowerShell is required'
core = args.conduit_jar.resolve()
checks = 0
prefix = 'com/velocitypowered/proxy/connection/backend/'
bridge_rel = 'velocity-gateway/build/libs/foundations_conduit_bridge-0.0.6a.R19.jar'
plugin_rel = 'velocity-gateway/build/libs/foundations_velocity_gateway-0.0.6a.R19.jar'
installed_rel = 'velocity/plugins/foundations_velocity_gateway-0.0.6a.R19.jar'
sources = ['SeamlessConfigSessionHandler.java', 'FoundationsHandshakeCapture.java',
           'FoundationsTagConfiguration.java', 'FoundationsNeoForgeConfiguration.java']


def check(ok, message):
    global checks
    assert ok, message
    checks += 1


def digest(path):
    return hashlib.sha256(path.read_bytes()).digest()


with tempfile.TemporaryDirectory(prefix='Foundations bootstrap test ') as td:
    fixture = Path(td) / 'Cluster with spaces'

    def put(rel, data):
        dst = fixture / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(data)

    for rel in ['tools/setup_velocity.ps1', 'tools/build_gateway.ps1', bridge_rel, plugin_rel,
                'velocity-gateway/source/FoundationsVelocityGateway.java',
                'velocity-gateway/resources/velocity-plugin.json',
                'tools/conduit-r15-patch/GPL-3.0.txt',
                *['tools/conduit-r15-patch/' + s for s in sources]]:
        put(rel, (root / rel).read_bytes())
    for rel, data in {'config/cluster.properties': b'gateway.seamless.enabled=false\n',
                      'velocity/runtime/java25/bin/java.exe': b'fixture metadata only',
                      'velocity/runtime/java25/release': b'JAVA_VERSION="25.0.1"\n',
                      'workers/worker-001/server.properties': b'online-mode=true\n',
                      'workers/worker-001/world/sentinel': b'KEEP WORLD'}.items():
        put(rel, data)
    forwarding = Path(td) / 'pcf-fixture.jar'
    with zipfile.ZipFile(forwarding, 'w') as z:
        z.writestr('META-INF/neoforge.mods.toml', 'modId = "pcf"\nversion = "1.2.2"\n')
    good_forwarding = forwarding.read_bytes()
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
        return subprocess.run([args.powershell, '-NoLogo', '-NoProfile', '-NonInteractive',
                               '-ExecutionPolicy', 'Bypass', '-File', str(wrapper)],
                              env=dict(os.environ, FIXTURE_ROOT=str(fixture), FIXTURE_CORE=str(core), FIXTURE_PCF=str(forwarding)),
                              capture_output=True, text=True, timeout=180)

    def verified(scenario, expect_build=False):
        result = run()
        check(result.returncode == 0, scenario + '\n' + result.stdout + result.stderr)
        check(('Compiling R19 bridge' in result.stdout) == expect_build, 'Unexpected build path: ' + scenario)
        check(digest(fixture / installed_rel) == digest(fixture / plugin_rel), 'Wrong plugin installed')
        with zipfile.ZipFile(fixture / 'velocity/velocity.jar') as proxy, zipfile.ZipFile(fixture / bridge_rel) as bridge:
            for name in bridge.namelist():
                if name.endswith('.class'):
                    check(proxy.read(name) == bridge.read(name), 'Overlay mismatch: ' + name)
            check(prefix+'FoundationsTagConfiguration.class' in bridge.namelist(), 'Tag helper missing')
            check(prefix+'FoundationsNeoForgeConfiguration.class' in bridge.namelist(), 'NeoForge helper missing')
        check('seamless-server-switches = false' in (fixture / 'velocity/conduit.toml').read_text(), 'Normal mode not applied')
        check(digest(fixture / 'server-template/mods/proxy-compatible-forge-1.2.2.jar') == digest(forwarding), 'Forwarding dependency not repaired')
        return result

    for scenario in ['fresh core with JRE and bundled updater artifacts', 'cached core', 'corrupt installed plugin', 'corrupt forwarding mod']:
        if scenario == 'corrupt installed plugin':
            put(installed_rel, b'broken')
        if scenario == 'corrupt forwarding mod':
            put('server-template/mods/proxy-compatible-forge-1.2.2.jar', b'partial download')
        verified(scenario)
    check(not (fixture / 'velocity/runtime/java25/bin/javac.exe').exists(), 'Bundled updater unexpectedly needed a JDK')
    secret = (fixture / 'velocity/forwarding.secret').read_bytes()
    check((fixture / 'server-template/forwarding.secret').read_bytes() == secret, 'Template secret mismatch')
    check((fixture / 'workers/worker-001/forwarding.secret').read_bytes() == secret, 'Worker secret mismatch')
    check((fixture / 'workers/worker-001/world/sentinel').read_bytes() == b'KEEP WORLD', 'World changed')

    # Failed download leaves no partial forwarding mod installed.
    (fixture / 'server-template/mods/proxy-compatible-forge-1.2.2.jar').unlink()
    forwarding.write_bytes(b'failed remote download')
    result = run()
    check(result.returncode != 0, 'Corrupt dependency download accepted')
    check(not (fixture / 'server-template/mods/proxy-compatible-forge-1.2.2.jar').exists(), 'Partial dependency installed')
    check(not (fixture / 'server-template/mods/proxy-compatible-forge-1.2.2.jar.download').exists(), 'Staging file leaked')
    forwarding.write_bytes(good_forwarding)

    # Reproduce the user's clean source ZIP: no compiled bridge or plugin.
    shutil.copytree(args.jdk, fixture / 'velocity/runtime/java25', dirs_exist_ok=True)
    for rel in [bridge_rel, plugin_rel, installed_rel]:
        (fixture / rel).unlink()
    check(not list((fixture / 'tools/conduit-r15-patch').glob('*.class')), 'Fixture accidentally supplied loose classes')
    verified('source-only bootstrap', expect_build=True)
    verified('source bootstrap cached artifacts')
    put(bridge_rel, b'corrupt bundle')
    verified('rebuild corrupt bridge bundle', expect_build=True)
    check((fixture / 'velocity/forwarding.secret').read_bytes() == secret, 'Source repair rotated forwarding secret')

    # Missing source or compilation failure must leave the proxy core intact.
    before = digest(fixture / 'velocity/velocity.jar')
    (fixture / bridge_rel).unlink()
    source = fixture / 'tools/conduit-r15-patch/SeamlessConfigSessionHandler.java'
    good_source = source.read_bytes()
    source.unlink()
    result = run()
    check(result.returncode != 0, 'Missing bridge source accepted')
    check(digest(fixture / 'velocity/velocity.jar') == before, 'Missing source damaged the core')
    source.write_bytes(b'not valid Java')
    result = run()
    check(result.returncode != 0, 'Invalid bridge source accepted')
    check(digest(fixture / 'velocity/velocity.jar') == before, 'Failed compilation damaged the core')
    check(not (fixture / bridge_rel).exists(), 'Failed compilation published a partial bundle')
    check(not list((fixture / 'velocity-gateway/build').glob('gateway-*')), 'Build staging directory leaked')
    source.write_bytes(good_source)
print(f'Gateway bootstrap: PASS ({checks} assertions; fixture downloads, real Conduit/JDK)')
