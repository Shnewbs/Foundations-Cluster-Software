#!/usr/bin/env python3
"""Build Node and run real NeoForge Spike A/C tests in a disposable build directory."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '8.14.3'
SHA256 = 'bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531'
toolchain = ROOT / 'runtime/tools'
toolchain.mkdir(parents=True, exist_ok=True)
archive = toolchain / f'gradle-{VERSION}-bin.zip'
if not archive.is_file() or hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
    with urllib.request.urlopen(f'https://services.gradle.org/distributions/gradle-{VERSION}-bin.zip', timeout=120) as r:
        archive.write_bytes(r.read())
if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
    raise SystemExit('Gradle distribution checksum mismatch')
with zipfile.ZipFile(archive) as z:
    z.extractall(toolchain)
gradle = toolchain / f'gradle-{VERSION}/bin' / ('gradle.bat' if os.name == 'nt' else 'gradle')
if os.name != 'nt':
    gradle.chmod(0o755)
output = ROOT / 'minecraft-agent/build/reports/survival-spike'
subprocess.run([str(gradle), '--no-daemon', '--max-workers=2', 'clean'], cwd=ROOT/'minecraft-agent', check=True, timeout=180)
output.mkdir(parents=True, exist_ok=True)
log = output/'neoforge-gametests.log'
environment = dict(os.environ, FOUNDATIONS_CLUSTER_AGENT_TOKEN='')
with log.open('w', encoding='utf-8') as stream:
    run = subprocess.run([str(gradle), '--no-daemon', '--max-workers=2', 'build', 'runSurvivalSpike'],
        cwd=ROOT/'minecraft-agent', env=environment, stdout=stream, stderr=subprocess.STDOUT, timeout=1500)
text = log.read_text(encoding='utf-8')
print(text, flush=True)
if run.returncode:
    raise SystemExit(run.returncode)
expected = sum(len(re.findall(r'@GameTest\(', p.read_text())) for p in (ROOT/'minecraft-agent/src/spike/java').rglob('*.java'))
if not expected or not re.search(rf'All {expected} required tests passed', text):
    raise SystemExit('Game test pass/count evidence missing; refusing to package')
version = re.search(r'^mod_version=(.+)$', (ROOT/'minecraft-agent/gradle.properties').read_text(), re.M).group(1)
generated = ROOT / 'minecraft-agent/build/generated/sources/nodeVersion/java/com/foundations/cluster/node/FoundationsClusterNode.java'
assert f'public static final String VERSION = "{version}";' in generated.read_text(encoding='utf-8'), 'Generated Node source version mismatch'
jar = ROOT/f'minecraft-agent/build/libs/foundations_cluster_node-{version}.jar'
with zipfile.ZipFile(jar) as z:
    assert f'version="{version}"' in z.read('META-INF/neoforge.mods.toml').decode()
    assert z.read('com/foundations/cluster/node/interaction/AuthorityBreakExecutor.class')[:8] == bytes.fromhex('cafebabe00000041')
    assert z.read('com/foundations/cluster/playtest/FoundationsPlaytest.class')[:8] == bytes.fromhex('cafebabe00000041')
    assert not any('/spike/' in n or 'foundations_cluster_spike' in n for n in z.namelist()), 'Test fixtures leaked into runtime Node'
# Exercise Manager upgrade selection with the actual tested JAR, not a metadata fixture.
classpath = os.pathsep.join(str(ROOT/p) for p in [
    'cluster-manager/build/test-classes', 'cluster-manager/build/FoundationsClusterManager.jar',
    'cluster-manager/lib/bcprov.jar'])
subprocess.run(['java', '-cp', classpath, 'com.foundations.cluster.manager.NodeUpgradeSelfTest', str(ROOT)],
    cwd=ROOT, check=True, timeout=120)
(output/'RESULT.txt').write_text(
    f'Node {version}; Minecraft 1.21.1 / NeoForge 21.1.250\n'
    f'{expected} real NeoForge GameTests passed.\nNode SHA256: {hashlib.sha256(jar.read_bytes()).hexdigest()}\n'
    'Single-process disposable fixture only; live Survival routing, process-kill recovery, protection-mod compatibility and V0-SEAM remain gated.\n', encoding='utf-8')
print(f'Survival Spike A/C: PASS ({expected} real NeoForge GameTests); runtime JAR excludes test mod.')
