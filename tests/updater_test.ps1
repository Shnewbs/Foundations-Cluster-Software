param([Parameter(Mandatory=$true)][string]$Archive)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root=Join-Path ([IO.Path]::GetTempPath()) ('Foundations updater test '+[Guid]::NewGuid().ToString('N'))
$package=Join-Path $root 'extracted package';$install=Join-Path $root 'existing cluster'
$checks=0
function Check($ok,[string]$message) {if(!$ok){throw $message};$script:checks++}
function Put([string]$relative,[string]$data) {$file=Join-Path $install $relative;New-Item -ItemType Directory -Path (Split-Path -Parent $file) -Force|Out-Null;[IO.File]::WriteAllText($file,$data)}
function Run-Update([string]$backup='',[switch]$Fail) {
    $options=@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',(Join-Path $package 'tools/apply_update.ps1'),'-ClusterRoot',$install)
    if($backup){$options+=@('-Rollback',$backup)}
    $ErrorActionPreference="Continue"
    try { $output=& powershell.exe @options 2>&1; $code=$LASTEXITCODE } finally { $ErrorActionPreference="Stop" }
    if($Fail){Check ($code -ne 0) 'Unsafe update succeeded'}else{if($code -ne 0){throw ($output|Out-String)};$script:checks++}
}
try {
    New-Item -ItemType Directory -Path $root | Out-Null
    Expand-Archive -LiteralPath $Archive -DestinationPath $package
    Put 'config/cluster.properties' 'operator config'
    Put 'runtime/security/keep.key' 'fixture secret'
    Put 'workers/worker-001/world/level.dat' 'fixture world'
    Put 'cluster-manager/lib/bcprov.jar' 'previous dependency'
    Put 'tools/start_foundations.ps1' 'previous launcher'
    Put 'tools/setup_velocity.ps1' 'previous gateway setup'
    $jar=Join-Path $install 'cluster-manager/build/FoundationsClusterManager.jar'
    New-Item -ItemType Directory -Path (Split-Path -Parent $jar) -Force | Out-Null
    $zip=[IO.Compression.ZipFile]::Open($jar,[IO.Compression.ZipArchiveMode]::Create)
    try {$entry=$zip.CreateEntry('com/foundations/cluster/manager/ClusterVersion.class');$writer=[IO.StreamWriter]::new($entry.Open());try{$writer.Write('metadata fixture 0.0.6a.R19-HF11')}finally{$writer.Dispose()}}finally{$zip.Dispose()}
    $original=(Get-FileHash $jar).Hash
    Run-Update
    $manifest=Get-Content (Join-Path $package 'UPDATE_MANIFEST.json') -Raw | ConvertFrom-Json
    foreach($item in $manifest.files){Check ((Get-FileHash (Join-Path $install $item.path)).Hash -eq $item.sha256) 'Installed hash mismatch'}
    foreach($pair in @(@('config/cluster.properties','operator config'),@('runtime/security/keep.key','fixture secret'),@('workers/worker-001/world/level.dat','fixture world'))){Check ((Get-Content (Join-Path $install $pair[0]) -Raw) -eq $pair[1]) 'Operator data changed'}
    $backup=(Get-ChildItem (Join-Path $install 'update-backups') -Directory | Select-Object -First 1).FullName
    Run-Update -backup $backup
    Check ((Get-FileHash $jar).Hash -eq $original) 'Rollback did not restore Manager'
    Check ((Get-Content (Join-Path $install 'tools/start_foundations.ps1') -Raw) -eq 'previous launcher') 'Rollback did not restore launcher'
    Check (!(Test-Path (Join-Path $install 'runtime/update-in-progress.json'))) 'Marker remained after rollback'
    Check ((Get-Content (Join-Path $install 'tools/setup_velocity.ps1') -Raw) -eq 'previous gateway setup') 'Rollback did not restore gateway setup'
    foreach($path in @('velocity-gateway/build/libs/foundations_conduit_bridge-0.0.6a.R19.jar','velocity-gateway/build/libs/foundations_velocity_gateway-0.0.6a.R19.jar','tools/build_gateway.ps1')){
        Check (!(Test-Path -LiteralPath (Join-Path $install $path))) 'Rollback left a newly added gateway artifact'
    }
    $payload=Join-Path $package 'payload/cluster-manager/lib/bcprov.jar';$good=[IO.File]::ReadAllBytes($payload)
    [IO.File]::WriteAllText($payload,'tampered');Run-Update -Fail;[IO.File]::WriteAllBytes($payload,$good)
    Check ((Get-FileHash $jar).Hash -eq $original) 'Tampered payload changed installation'
    $manifestFile=Join-Path $package 'UPDATE_MANIFEST.json';$goodManifest=[IO.File]::ReadAllText($manifestFile)
    foreach($unsafe in @('runtime/security/keep.key','../escape','cluster-manager/lib/../escape','C:/escape')) {
        $m=$goodManifest|ConvertFrom-Json;$m.files[0].path=$unsafe;[IO.File]::WriteAllText($manifestFile,($m|ConvertTo-Json -Depth 5));Run-Update -Fail
    }
    [IO.File]::WriteAllText($manifestFile,$goodManifest)
    $lock=[IO.File]::Open((Join-Path $install 'runtime/cluster-manager.lock'),[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    try{Run-Update -Fail}finally{$lock.Dispose()}
    Put 'runtime/update-in-progress.json' '{"backup":"fixture"}';Run-Update -Fail;Run-Update -backup $backup
    # Hold a destination open to force an error after the first file is replaced.
    $held=[IO.File]::Open((Join-Path $install 'cluster-manager/lib/bcprov.jar'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    try{Run-Update -Fail}finally{$held.Dispose()}
    Check (Test-Path (Join-Path $install 'runtime/update-in-progress.json')) 'Incomplete rollback did not retain recovery marker'
    $pending=Get-Content (Join-Path $install 'runtime/update-in-progress.json') -Raw|ConvertFrom-Json
    Run-Update -backup $pending.backup
    Check ((Get-FileHash $jar).Hash -eq $original) 'Recovery did not restore Manager'
    Write-Host "Updater transactions: PASS ($checks checks; metadata fixtures, no live installation)"
} finally {if(Test-Path -LiteralPath $root){Remove-Item -LiteralPath $root -Recurse -Force}}
