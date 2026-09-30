#!/usr/bin/env python3
"""Consistent offline Foundations backups. Standard-library only; never follows links."""
import argparse, contextlib, datetime, hashlib, json, os, pathlib, subprocess, sys, zipfile
ROOTS = ('config', 'runtime', 'workers', 'server-template', 'velocity', 'logs')
EXCLUDED = ('runtime/tools/', 'runtime/manager.pid', 'runtime/cluster-manager.lock', 'velocity/runtime/', 'velocity/runtime-backup/', 'server-template/libraries/', 'server-template/.bootstrap/')

def digest(stream):
    h=hashlib.sha256()
    for block in iter(lambda: stream.read(1024*1024), b''): h.update(block)
    return h.hexdigest()

def active_java(root):
    if os.name == 'nt':
        script="$r=$env:FOUNDATIONS_BACKUP_ROOT; $p=@(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {$_.Name -in @('java.exe','javaw.exe') -and $_.CommandLine -and $_.CommandLine.IndexOf($r,[StringComparison]::OrdinalIgnoreCase) -ge 0}); if($p.Count -gt 0){exit 2}"
        env=dict(os.environ,FOUNDATIONS_BACKUP_ROOT=str(root))
        result=subprocess.run(['powershell','-NoProfile','-Command',script],env=env,capture_output=True)
        if result.returncode not in (0,2): raise RuntimeError('Cannot verify Java process state; backup refused')
        return result.returncode==2
    for proc in pathlib.Path('/proc').glob('[0-9]*'):
        try:
            cmd=(proc/'cmdline').read_bytes().replace(b'\0',b' ').decode(errors='replace')
            if 'java' not in cmd: continue
            if str(root) in cmd or (proc/'cwd').resolve().is_relative_to(root): return True
        except (PermissionError,FileNotFoundError,ProcessLookupError): continue
    return False

@contextlib.contextmanager
def stopped_lock(root):
    lock=root/'runtime/cluster-manager.lock';lock.parent.mkdir(parents=True,exist_ok=True)
    with lock.open('a+b') as handle:
        try:
            if os.name=='nt':
                import msvcrt
                handle.seek(0);msvcrt.locking(handle.fileno(),msvcrt.LK_NBLCK,1)
            else:
                import fcntl
                fcntl.lockf(handle,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except OSError as exc: raise RuntimeError('Cluster Manager is running. Shut it down before backup.') from exc
        if active_java(root): raise RuntimeError('Java processes still use this cluster. Stop workers and gateway before backup.')
        yield

def backup(root,output):
    root=root.resolve(strict=True);output=output.resolve()
    if output.is_relative_to(root): raise ValueError('Backup output must be outside the Cluster directory')
    if not (root/'config/cluster.properties').is_file(): raise ValueError('Not a Cluster root')
    if output.exists(): raise FileExistsError('Refusing to overwrite an existing backup')
    output.parent.mkdir(parents=True,exist_ok=True);partial=output.with_suffix(output.suffix+'.partial')
    if partial.exists(): raise FileExistsError('A partial backup already exists at '+str(partial))
    records=[]
    try:
        with stopped_lock(root), zipfile.ZipFile(partial,'x',zipfile.ZIP_DEFLATED,compresslevel=3,allowZip64=True) as archive:
            for area in ROOTS:
                base=root/area
                if base.is_symlink(): raise ValueError('Backup root is a symbolic link: '+area)
                if not base.exists(): continue
                for directory,dirs,files in os.walk(base,followlinks=False):
                    folder=pathlib.Path(directory)
                    for child in list(dirs):
                        p=folder/child;rel=p.relative_to(root).as_posix()+'/'
                        if p.is_symlink(): raise ValueError('Symbolic link found: '+rel)
                        if any(rel.startswith(prefix) for prefix in EXCLUDED): dirs.remove(child)
                    for filename in sorted(files):
                        p=folder/filename;rel=p.relative_to(root).as_posix()
                        if any(rel.startswith(prefix) for prefix in EXCLUDED): continue
                        if p.is_symlink(): raise ValueError('Symbolic link found: '+rel)
                        before=p.stat();h=hashlib.sha256()
                        with p.open('rb') as source,archive.open(rel,'w',force_zip64=True) as target:
                            for block in iter(lambda:source.read(1024*1024),b''): h.update(block);target.write(block)
                        after=p.stat()
                        if (before.st_size,before.st_mtime_ns)!=(after.st_size,after.st_mtime_ns):raise RuntimeError('File changed during backup: '+rel)
                        records.append(dict(path=rel,size=after.st_size,sha256=h.hexdigest()))
            archive.writestr('FOUNDATIONS_BACKUP_MANIFEST.json',json.dumps(dict(schema=1,version='0.0.6a.R19',package='0.0.6a.R19-HF9',created=datetime.datetime.now(datetime.timezone.utc).isoformat(),files=records),indent=2))
        verify(partial)
        os.replace(partial,output)
        print(f'Backup verified: {output} ({len(records)} files)')
    except BaseException:
        partial.unlink(missing_ok=True);raise

def verify(archive_path):
    with zipfile.ZipFile(archive_path) as archive:
        names=archive.namelist()
        if len(names)!=len(set(names)):raise ValueError('Duplicate archive entries')
        manifest=json.loads(archive.read('FOUNDATIONS_BACKUP_MANIFEST.json'))
        records=manifest['files'];expected={r['path'] for r in records}
        if len(expected)!=len(records):raise ValueError('Duplicate manifest paths')
        if expected|{'FOUNDATIONS_BACKUP_MANIFEST.json'}!=set(names):raise ValueError('Manifest/file mismatch')
        for r in records:
            p=pathlib.PurePosixPath(r['path'])
            if p.is_absolute() or '..' in p.parts or '\\' in r['path'] or ':' in r['path'] or p.parts[0] not in ROOTS:raise ValueError('Unsafe archive path')
            info=archive.getinfo(r['path'])
            if info.file_size!=r['size']:raise ValueError('Size mismatch: '+r['path'])
            with archive.open(info) as stream:
                if digest(stream)!=r['sha256']:raise ValueError('Checksum mismatch: '+r['path'])
    return True

def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('backup');p.add_argument('--root',type=pathlib.Path);p.add_argument('--output',type=pathlib.Path)
    p=sub.add_parser('verify');p.add_argument('archive',type=pathlib.Path)
    args=parser.parse_args()
    if args.command=='verify':verify(args.archive);print('Backup verification: PASS');return
    root=args.root
    if root is None:
        try:
            import tkinter as tk
            from tkinter import filedialog
            window=tk.Tk();window.withdraw();selected=filedialog.askdirectory(title='Select your stopped Foundations Cluster folder');window.destroy()
            root=pathlib.Path(selected) if selected else None
        except Exception:pass
    if root is None:root=pathlib.Path(input('Full path to the stopped Cluster directory: ').strip().strip('"'))
    output=args.output or root.resolve().parent/('Foundations-Backup-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'.zip')
    backup(root,output)
if __name__=='__main__':
    try:main()
    except Exception as exc:print('Backup failed: '+str(exc),file=sys.stderr);sys.exit(1)
