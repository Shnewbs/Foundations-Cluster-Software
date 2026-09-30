"""Compile against a provisioned, mapped NeoForge dependency tree for offline validation.
Normal users should use minecraft-agent/build_mod.bat (Gradle).
"""
import argparse,os,pathlib,subprocess,zipfile,re
p=argparse.ArgumentParser();p.add_argument('--deps',required=True);p.add_argument('--javac',required=True);a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];deps=pathlib.Path(a.deps)
jars=[deps/'minecraft-agent/build/moddev/artifacts/neoforge-21.1.250.jar']+list((deps/'server-template/libraries').rglob('*.jar'))
jars=[x for x in jars if not any(t in x.name for t in ['-server.jar','-srg.jar','installertools','-sources.jar'])]
out=r/'minecraft-agent/build/r19-classes';out.mkdir(parents=True,exist_ok=True)
src=list((r/'minecraft-agent/src/main/java').rglob('*.java'))+list((r/'protocol/src/main/java').rglob('*.java'))
subprocess.run([a.javac,'--release','21','-proc:none','-cp',os.pathsep.join(map(str,jars)),'-d',str(out),*map(str,src)],check=True)
version=re.search(r'^mod_version=(.+)$',(r/'minecraft-agent/gradle.properties').read_text(),re.M).group(1).strip()
jar=r/f'minecraft-agent/build/libs/foundations_cluster_node-{version}.jar';jar.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
 for base in [out,r/'minecraft-agent/src/main/resources']:
  for f in base.rglob('*'):
   if f.is_file():z.write(f,f.relative_to(base))
print(jar)
