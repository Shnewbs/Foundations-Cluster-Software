param([Parameter(Mandatory=$true)][string]$Archive,[string]$LegacyArchive)
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
    $protected=@(@('runtime/paired-entity-views.bin','fixture pair metadata'),@('workers/worker-001/world/playerdata/player.dat','fixture player save'),@('runtime/operations.journal','fixture journal'))
    foreach($item in $protected){Put $item[0] $item[1]}
    Put 'config/cluster.properties' 'operator config'
    Put 'runtime/security/keep.key' 'fixture secret'
    Put 'workers/worker-001/world/level.dat' 'fixture world'
    Put 'cluster-manager/lib/bcprov.jar' 'previous dependency'
    Put 'tools/start_foundations.ps1' 'previous launcher'
    Put 'tools/setup_velocity.ps1' 'previous gateway setup'
    Put 'web/app.js' 'previous dashboard'
    Put 'web/index.html' 'previous page'
    Put 'web/operations.js' 'previous workspace'
    Put 'web/operations.css' 'previous workspace styles'
    # A realistic legacy app includes divergent R13 source/build scripts. These
    # must upgrade in a SINGLE pass, without installing HF13/HF14/etc first.
    $legacySource=@{
      'minecraft-agent/gradle.properties' = 'minecraft old Node R13 settings'
      'minecraft-agent/build.gradle' = 'minecraft old NeoForge Gradle build'
      'minecraft-agent/build_mod.bat' = 'minecraft old Node build helper'
      'tools/ensure_jdk21.ps1' = 'legacy JDK installer source'
      'tools/ensure_gradle.ps1' = 'legacy Gradle helper'
      'cluster-manager/src/main/java/com/foundations/cluster/manager/ClusterVersion.java' = 'legacy Manager and Node version constants'
      'web/features.json' = 'legacy dashboard capabilities'
    }
    foreach($relative in $legacySource.Keys){Put $relative $legacySource[$relative]}
    # Unknown operator extensions are never treated as stock application code.
    Put 'tools/operator-custom.ps1' 'keep my local script'

    $jar=Join-Path $install 'cluster-manager/build/FoundationsClusterManager.jar'
    New-Item -ItemType Directory -Path (Split-Path -Parent $jar) -Force | Out-Null
    if ($LegacyArchive) {
        $expectedSha='ab01849326008dc967a3954d637be5ad4251abd1cf6d48b8fb6ba194762022a1'
        Check ((Get-FileHash -LiteralPath $LegacyArchive -Algorithm SHA256).Hash -eq $expectedSha) 'Legacy HF12 release archive checksum mismatch'
        $legacy=Join-Path $root 'actual HF12 updater release'
        Expand-Archive -LiteralPath $LegacyArchive -DestinationPath $legacy
        $hf12=Join-Path $legacy 'payload/cluster-manager/build/FoundationsClusterManager.jar'
        Copy-Item -LiteralPath $hf12 -Destination $jar
        $entryZip=[IO.Compression.ZipFile]::OpenRead($jar)
        try { Check ($entryZip.GetEntry('com/foundations/cluster/manager/ClusterVersion.class') -ne $null) 'Actual HF12 version class missing' }finally{$entryZip.Dispose()}
    }else{
        $zip=[IO.Compression.ZipFile]::Open($jar,[IO.Compression.ZipArchiveMode]::Create)
        try {$entry=$zip.CreateEntry('com/foundations/cluster/manager/ClusterVersion.class');$writer=[IO.StreamWriter]::new($entry.Open());try{$writer.Write('metadata fixture 0.0.6a.R19-HF12')}finally{$writer.Dispose()}}finally{$zip.Dispose()}
    }
    $original=(Get-FileHash $jar).Hash
    Run-Update
    $manifest=Get-Content (Join-Path $package 'UPDATE_MANIFEST.json') -Raw | ConvertFrom-Json
    Check ($manifest.version -eq '0.0.7a-dev.2.R6') 'Wrong release package'
    Check ($manifest.upgradeMode -eq 'cumulative' -and $manifest.files.Count -gt 300) 'Incomplete cumulative updater payload'
    Check (((Get-Content (Join-Path $install 'tools/start_foundations.ps1') -Raw) -match '0.0.7a-dev.2.R6')) 'Startup launcher did not advance to R6'
    foreach($sourceFile in $legacySource.Keys){
        Check ($sourceFile -cin @($manifest.files.path)) "Missing cumulative source file $sourceFile"
    }
    foreach($item in $manifest.files){Check ((Get-FileHash (Join-Path $install $item.path)).Hash -eq $item.sha256) 'Installed hash mismatch'}
    foreach($path in $legacySource.Keys){
        Check ((Get-Content -LiteralPath (Join-Path $install $path) -Raw) -ne $legacySource[$path]) "Legacy source not upgraded: $path"
    }
    Check ((Get-Content (Join-Path $install 'tools/operator-custom.ps1') -Raw) -eq 'keep my local script') 'Unlisted user script modified'
    foreach($path in @('web/operations.js','web/operations.css','web/worker-controls.js','web/worker-controls.css')){
        Check ($path -cin @($manifest.files.path)) 'Missing worker-controls payload'
    }
    foreach($pair in @(@('config/cluster.properties','operator config'),@('runtime/security/keep.key','fixture secret'),@('workers/worker-001/world/level.dat','fixture world'))){Check ((Get-Content (Join-Path $install $pair[0]) -Raw) -eq $pair[1]) 'Operator data changed'}
    foreach($item in $protected){Check ((Get-Content (Join-Path $install $item[0]) -Raw) -eq $item[1]) 'Update changed protected entity/player/journal data'}
    $backup=(Get-ChildItem (Join-Path $install 'update-backups') -Directory | Select-Object -First 1).FullName
    Run-Update # Reapplying the same development version is explicitly supported.
    Check (!(Test-Path (Join-Path $install 'runtime/update-in-progress.json'))) 'Reinstall left an update marker'
    Run-Update -backup $backup
    Check ((Get-FileHash $jar).Hash -eq $original) 'Rollback did not restore Manager'
    Check ((Get-Content (Join-Path $install 'tools/start_foundations.ps1') -Raw) -eq 'previous launcher') 'Rollback did not restore launcher'
    Check (!(Test-Path (Join-Path $install 'runtime/update-in-progress.json'))) 'Marker remained after rollback'
    Check ((Get-Content (Join-Path $install 'tools/setup_velocity.ps1') -Raw) -eq 'previous gateway setup') 'Rollback did not restore gateway setup'
    Check ((Get-Content (Join-Path $install 'web/app.js') -Raw) -eq 'previous dashboard') 'Rollback did not restore dashboard'
    Check ((Get-Content (Join-Path $install 'web/index.html') -Raw) -eq 'previous page') 'Rollback did not restore page'
    Check ((Get-Content (Join-Path $install 'web/operations.js') -Raw) -eq 'previous workspace') 'Rollback did not restore workspace'
    Check ((Get-Content (Join-Path $install 'web/operations.css') -Raw) -eq 'previous workspace styles') 'Rollback did not restore workspace styles'
    foreach($path in $legacySource.Keys){
        Check ((Get-Content -LiteralPath (Join-Path $install $path) -Raw) -eq $legacySource[$path]) "Rollback did not restore legacy source $path"
    }
    Check ((Get-Content (Join-Path $install 'tools/operator-custom.ps1') -Raw) -eq 'keep my local script') 'Rollback modified custom script'
    foreach($path in @('velocity-gateway/build/libs/foundations_conduit_bridge-0.0.6a.R19.jar','velocity-gateway/build/libs/foundations_velocity_gateway-0.0.6a.R19.jar','tools/build_gateway.ps1','minecraft-agent/build/libs/foundations_cluster_node-0.0.6a.R19.jar','web/worker-controls.js','web/worker-controls.css','web/styles.css','web/icons.svg','web/features.json')){
        Check (!(Test-Path -LiteralPath (Join-Path $install $path))) 'Rollback left a newly added application artifact'
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
    foreach($item in $protected){Check ((Get-Content (Join-Path $install $item[0]) -Raw) -eq $item[1]) 'Rollback changed protected entity/player/journal data'}
    # Validate the original dev.2 upgrade boundary, plus this direct HF12 install and reinstall.
    $savedManager=[IO.File]::ReadAllBytes($jar)
    foreach($label in @('0.0.7a-dev.2.R50','0.0.7a-dev.20','0.0.7a-dev.2')) {
        Remove-Item -LiteralPath $jar
        $testJar=[IO.Compression.ZipFile]::Open($jar,[IO.Compression.ZipArchiveMode]::Create)
        try {$entry=$testJar.CreateEntry('com/foundations/cluster/manager/ClusterVersion.class');$writer=[IO.StreamWriter]::new($entry.Open());try{$writer.Write('metadata fixture '+$label)}finally{$writer.Dispose()}}finally{$testJar.Dispose()}
        if($label -eq '0.0.7a-dev.2'){Run-Update;Check ((Get-FileHash $jar).Hash -eq ($manifest.files|Where-Object path -eq 'cluster-manager/build/FoundationsClusterManager.jar').sha256) 'Original dev.2 did not upgrade'}
        else {Run-Update -Fail}
    }
    [IO.File]::WriteAllBytes($jar,$savedManager)
    Write-Host "Cumulative updater transactions: PASS ($checks checks; direct HF12-to-R6 plus reinstall/rollback; no live installation)"
} finally {if(Test-Path -LiteralPath $root){Remove-Item -LiteralPath $root -Recurse -Force}}
