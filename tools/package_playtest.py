#!/usr/bin/env python3
"""Package exact development source, read-only report tools and executed evidence."""
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '0.0.7a-dev.2'
OUT = ROOT / 'development-dist'
OUT.mkdir(exist_ok=True)
report = ROOT / 'minecraft-agent/build/reports/survival-spike'
expected = sum(len(re.findall(r'@GameTest\(', p.read_text())) for p in (ROOT / 'minecraft-agent/src/spike/java').rglob('*.java'))
log = (report / 'neoforge-gametests.log').read_text(encoding='utf-8')
if expected != 45 or not re.search(rf'All {expected} required tests passed', log):
    raise SystemExit('Exact game-test evidence missing; no test tools packaged')
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
source = subprocess.check_output(['git', 'archive', '--format=zip', '--prefix=FoundationsCluster-source/', 'HEAD'], cwd=ROOT)
classes = ROOT / 'playtest/build/test-classes'
jar_bytes = io.BytesIO()
with zipfile.ZipFile(jar_bytes, 'w', zipfile.ZIP_DEFLATED) as jar:
    jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nMain-Class: com.foundations.cluster.playtest.PlaytestTraceSelfTest\r\n\r\n')
    for file in sorted(classes.rglob('*.class')):
        data = file.read_bytes()
        if data[:8] != bytes.fromhex('cafebabe00000041'):
            raise SystemExit('Playtest trace classes must target Java 21')
        jar.writestr(file.relative_to(classes).as_posix(), data)
    if 'com/foundations/cluster/playtest/PlaytestTraceSelfTest.class' not in jar.namelist():
        raise SystemExit('Compiled trace tests missing')
jar_path = OUT / 'PlaytestTraceTests.jar'
jar_path.write_bytes(jar_bytes.getvalue())
trace = subprocess.run(['java', '-jar', str(jar_path)], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120, check=True)
if 'Playtest trace: PASS' not in trace.stdout:
    raise SystemExit('Packaged trace test JAR did not pass')
parser = subprocess.run(['python', str(ROOT / 'tests/playtest_report_test.py')], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120, check=True)
files = {
    'README.txt': (f'Foundations Cluster {VERSION} — test tools and source.\n'
        'Install the separately named UPDATER ZIP into a stopped backup/copy of HF16.\n'
        'This tools archive does not install a server mod or enable remote Survival.\n'
        'PLAYTEST_GUIDE.md explains the opt-in connected-player test.\n'
        'Reports stay local. No raw player saves or credentials are needed.\n').encode(),
    'SOURCE.zip': source,
    'PLAYTEST_GUIDE.md': (ROOT / 'docs/PLAYTEST_DEV2_GUIDE.md').read_bytes(),
    'summarize_playtest.py': (ROOT / 'tools/summarize_playtest.py').read_bytes(),
    'bin/PlaytestTraceTests.jar': jar_bytes.getvalue(),
    'evidence/trace-tests.log': trace.stdout.encode(),
    'evidence/report-tests.log': parser.stdout.encode(),
    'evidence/neoforge-gametests.log': log.encode(),
    'evidence/RESULT.txt': (report / 'RESULT.txt').read_bytes(),
    'BUILD.json': (json.dumps(dict(developmentVersion=VERSION, commit=commit, nodeVersion='0.0.6a.R15',
        gameTests=expected, kind='test-tools-and-source', remoteSurvivalEnabled=False,
        connectedClientAcceptance='requires-user-test', inventoryAuthority='unchanged'), indent=2) + '\n').encode(),
}
files['SHA256SUMS.txt'] = ''.join(hashlib.sha256(data).hexdigest() + '  ' + name + '\n' for name, data in sorted(files.items())).encode()
archive = OUT / f'FoundationsCluster-{VERSION}-TEST-TOOLS.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as z:
    for name, data in sorted(files.items()):
        z.writestr(name, data)
archive.with_suffix('.zip.sha256').write_text(hashlib.sha256(archive.read_bytes()).hexdigest() + '  ' + archive.name + '\n', encoding='ascii')
print(archive)
