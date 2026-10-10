#!/usr/bin/env python3
"""Portable Java 21 regression entry point; no live worlds or game downloads required."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--java', default='java')
p.add_argument('--javac', default='javac')
p.add_argument('--ecj', type=Path, help='Optional Eclipse compiler for a Java 21 runtime without javac')
p.add_argument('--skip-http', action='store_true')
a = p.parse_args()

def run(*cmd):
    print('+ ' + ' '.join(map(str, cmd)), flush=True)
    subprocess.run(list(map(str, cmd)), cwd=ROOT, check=True, timeout=240)

def compile_to(destination, sources, cp=None):
    destination = ROOT / destination
    shutil.rmtree(destination, ignore_errors=True)
    destination.mkdir(parents=True, exist_ok=True)
    argfile = destination.parent / (destination.name + '-sources.txt')
    argfile.write_text('\n'.join('"' + str(s.resolve()).replace('\\', '/') + '"' for s in sorted(sources)), encoding='utf-8')
    compiler = [a.java, '-jar', a.ecj, '-21', '-nowarn'] if a.ecj else [a.javac, '--release', '21']
    run(*compiler, '-encoding', 'UTF-8', *(['-cp', cp] if cp else []), '-d', destination, '@' + str(argfile))
    return destination

def jar(classes, target, main):
    target = ROOT / target
    target.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('META-INF/MANIFEST.MF', f'Manifest-Version: 1.0\r\nMain-Class: {main}\r\n\r\n')
        for file in sorted(classes.rglob('*')):
            if file.is_file(): archive.write(file, file.relative_to(classes).as_posix())
    return target

run(a.java, '-version')
bc = ROOT / 'cluster-manager/lib/bcprov.jar'
assert hashlib.sha256(bc.read_bytes()).hexdigest() == 'caaf68a5fdf4a8e039b7e2623aa8370038614b14fbcd20ab4835db2f1c7e823d', 'Missing or changed Argon2 dependency'
manager = compile_to('cluster-manager/build/classes', [*ROOT.glob('protocol/src/main/java/**/*.java'), *ROOT.glob('cluster-manager/src/main/java/**/*.java')], str(bc))
manager_jar = jar(manager, 'cluster-manager/build/FoundationsClusterManager.jar', 'com.foundations.cluster.manager.Main')
cp = os.pathsep.join(map(str, [manager_jar, bc]))
tests = compile_to('cluster-manager/build/test-classes', ROOT.glob('cluster-manager/src/test/java/**/*.java'), cp)
cp = os.pathsep.join([str(tests), cp])
for name in ['R16SelfTest', 'R17SelfTest', 'R18SelfTest', 'R19SelfTest', 'NodeUpgradeSelfTest', 'NodeStartupSelfTest', 'R19HydrationSelfTest', 'SupportArchivesSelfTest', 'RestartAvailabilitySelfTest', 'HardeningSelfTest', 'EntityViewRegistrySelfTest', 'AutoEntityRegistrySelfTest']:
    run(a.java, '-cp', cp, 'com.foundations.cluster.manager.' + name, ROOT, *(['--fixture'] if name == 'NodeUpgradeSelfTest' else []))
protocol = compile_to('protocol/build/test-classes', ROOT.glob('protocol/src/**/*.java'))
run(a.java, '-cp', protocol, 'com.foundations.cluster.protocol.PlayerInventoryJournalSelfTest')
run(a.java, '-cp', protocol, 'com.foundations.cluster.protocol.EntityViewsWireSelfTest')
run(a.java, '-cp', protocol, 'com.foundations.cluster.protocol.EntityOwnershipLedgerSelfTest')
run(a.java, '-cp', protocol, 'com.foundations.cluster.protocol.PickupTransferLedgerSelfTest')
reference = compile_to('reference/build/classes', ROOT.glob('reference/src/**/*.java'))
for name in ['transfer.RegionTransferCoordinatorSelfTest', 'persistence.PersistentRegionTransferSelfTest', 'replication.ReplicationProtocolSelfTest', 'replication.AuthorityResultJournalSelfTest']:
    run(a.java, '-cp', reference, 'com.foundations.cluster.' + name)
node = compile_to('minecraft-agent/build/persistence-test-classes', [ROOT / f'minecraft-agent/src/main/java/com/foundations/cluster/node/{n}.java' for n in ['AuthorityHydration', 'RemoteTickLeases']] + [ROOT / f'tests/{n}SelfTest.java' for n in ['AuthorityHydration', 'RemoteTickLeases']])
for name in ['AuthorityHydration', 'RemoteTickLeases']:
    run(a.java, '-cp', node, 'com.foundations.cluster.node.' + name + 'SelfTest')
simulator = compile_to('node-simulator/build/classes', ROOT.glob('node-simulator/src/main/java/**/*.java'))
jar(simulator, 'node-simulator/build/FoundationsClusterNodeSimulator.jar', 'com.foundations.cluster.simulator.Main')
for test in sorted((ROOT / 'tests').glob('testlab_*_test.cjs')):
    run('node', test)
if not a.skip_http:
    for name in ['hydration_api_test.py', 'support_api_test.py', 'restart_api_test.py', 'security_api_test.py', 'entity_views_api_test.py']:
        run(sys.executable, ROOT / 'tests' / name, '--java', a.java)
playtest = compile_to('playtest/build/test-classes', [ROOT / 'minecraft-agent/src/main/java/com/foundations/cluster/playtest/PlaytestTrace.java', ROOT / 'tests/PlaytestTraceSelfTest.java'])
run(a.java, '-cp', playtest, 'com.foundations.cluster.playtest.PlaytestTraceSelfTest')
run(sys.executable, ROOT / 'tests/playtest_report_test.py')
print('Portable validation PASS. Live NeoForge/Conduit acceptance is a separate gate.', flush=True)
