#!/usr/bin/env python3
"""Package a tested development experiment, never an operator updater or world."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.0.7a-dev.1'
OUT = ROOT / 'development-dist'
OUT.mkdir(exist_ok=True)
classes = ROOT / 'protocol/build/test-classes'
report = ROOT / 'minecraft-agent/build/reports/survival-spike'
expected = sum(len(re.findall(r'@GameTest\(', p.read_text())) for p in (ROOT/'minecraft-agent/src/spike/java').rglob('*.java'))
log = (report/'neoforge-gametests.log').read_text(encoding='utf-8')
if not expected or not re.search(rf'All {expected} required tests passed', log):
    raise SystemExit('Actual game-test evidence missing; no development bundle produced')
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
jar_bytes = io.BytesIO()
with zipfile.ZipFile(jar_bytes, 'w', zipfile.ZIP_DEFLATED) as jar:
    jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nMain-Class: com.foundations.cluster.protocol.PlayerInventoryJournalSelfTest\r\n\r\n')
    for file in sorted(classes.rglob('*.class')):
        data = file.read_bytes()
        if data[:8] != bytes.fromhex('cafebabe00000041'):
            raise SystemExit('Protocol test classes must target Java 21')
        jar.writestr(file.relative_to(classes).as_posix(), data)
    if 'com/foundations/cluster/protocol/PlayerInventoryJournalSelfTest.class' not in jar.namelist():
        raise SystemExit('Compiled inventory tests missing')
jar_path = OUT/'InventoryJournalTests.jar'
jar_path.write_bytes(jar_bytes.getvalue())
java_home = os.environ.get('JAVA_HOME_21_X64')
java = str(Path(java_home)/'bin/java.exe') if os.name == 'nt' and java_home else 'java'
run = subprocess.run([java, '-jar', str(jar_path)], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240, check=True)
if 'Atomic player inventory journal: PASS' not in run.stdout:
    raise SystemExit('Packaged JAR did not produce passing evidence')
source = subprocess.check_output(['git', 'archive', '--format=zip', '--prefix=FoundationsCluster-source/', 'HEAD'], cwd=ROOT)
readme = ('Foundations Cluster '+VERSION+' — DEVELOPMENT EXPERIMENTS\n\n'
    'NOT the completed 0.0.7a. NOT an installer, updater or playable Survival feature.\n'
    'Keep HF16 installed. No replacement Node JAR is included.\n\n'
    'Run: java -jar bin/InventoryJournalTests.jar (Java 21 or later).\n'
    'The tests use temporary fixture files and terminate four child test JVMs, never\n'
    'a Minecraft server. Full game test sources and actual CI logs are included.\n'
    'See docs/status/0_0_7A_DEVELOPMENT_1.md in SOURCE.zip for exact scope and limits.\n')
files = {'README.txt':readme.encode('utf-8'), 'SOURCE.zip':source,
    'bin/InventoryJournalTests.jar':jar_bytes.getvalue(),
    'evidence/inventory-journal-tests.log':run.stdout.encode('utf-8'),
    'evidence/neoforge-gametests.log':log.encode('utf-8'),
    'evidence/RESULT.txt':(report/'RESULT.txt').read_bytes(),
    'BUILD.json':(json.dumps({'developmentVersion':VERSION,'commit':commit,'kind':'development-experiments-not-updater','gameTests':expected,'remoteSurvivalEnabled':False},indent=2)+'\n').encode('utf-8')}
files['SHA256SUMS.txt'] = ''.join(hashlib.sha256(data).hexdigest()+'  '+name+'\n' for name,data in sorted(files.items())).encode('ascii')
archive = OUT/f'FoundationsCluster-{VERSION}-EXPERIMENTS.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
    for name,data in sorted(files.items()): z.writestr(name,data)
archive.with_suffix('.zip.sha256').write_text(hashlib.sha256(archive.read_bytes()).hexdigest()+'  '+archive.name+'\n',encoding='ascii')
print(archive)
