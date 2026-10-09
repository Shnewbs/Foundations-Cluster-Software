param([Parameter(Mandatory=$true)][string]$ClusterRoot, [string]$Rollback)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem
# Reviewed HF15 control-plane, gateway, Node build and Test Lab payload; operator data is excluded.
$allowed = @('tools/start_foundations.ps1','cluster-manager/lib/bcprov.jar','cluster-manager/build/FoundationsClusterManager.jar','tools/setup_velocity.ps1','tools/build_gateway.ps1','velocity-gateway/build/libs/foundations_conduit_bridge-0.0.6a.R19.jar','velocity-gateway/build/libs/foundations_velocity_gateway-0.0.6a.R19.jar','tools/conduit-r15-patch/SeamlessConfigSessionHandler.java','tools/conduit-r15-patch/FoundationsHandshakeCapture.java','tools/conduit-r15-patch/FoundationsTagConfiguration.java','tools/conduit-r15-patch/FoundationsNeoForgeConfiguration.java','tools/conduit-r15-patch/GPL-3.0.txt','tools/conduit-r15-patch/README.txt','velocity-gateway/source/FoundationsVelocityGateway.java','velocity-gateway/resources/velocity-plugin.json','web/app.js','web/index.html','minecraft-agent/build/libs/foundations_cluster_node-0.0.6a.R14.jar')
$ClusterRoot = [IO.Path]::GetFullPath($ClusterRoot).TrimEnd('\','/')
function Safe-Path([string]$base, [string]$relative) {
    if (!$relative -or $relative -notmatch '^[A-Za-z0-9_./-]+$' -or $relative.StartsWith('/') -or $relative -match '(^|/)(\.|\.\.)(/|$)' -or $relative.Contains('//')) { throw "Unsafe path: $relative" }
    $base = [IO.Path]::GetFullPath($base).TrimEnd('\','/')
    $path = [IO.Path]::GetFullPath((Join-Path $base $relative))
    if (!$path.StartsWith($base+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Path escaped root' }
    $check = $path
    while ($check) {
        if ((Test-Path -LiteralPath $check) -and ((Get-Item -LiteralPath $check -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Reparse point refused: $check" }
        $check = Split-Path -Parent $check
    }
    return $path
}
function Destination([string]$relative) {
    if ($relative -cnotin $allowed) { throw "Payload path is not allowed: $relative" }
    return Safe-Path $ClusterRoot $relative
}
function Replace-File([string]$source,[string]$destination) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    $stage = $destination+'.'+[Guid]::NewGuid().ToString('N')+'.staging'
    try {
        # Flush staged bytes before the atomic rename.
        $input = [IO.File]::OpenRead($source)
        try {
            $output = [IO.File]::Open($stage,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
            try { $input.CopyTo($output); $output.Flush($true) } finally { $output.Dispose() }
        } finally { $input.Dispose() }
        if (Test-Path -LiteralPath $destination) { [IO.File]::Replace($stage,$destination,[NullString]::Value) }
        else { [IO.File]::Move($stage,$destination) }
    } finally { if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Force } }
}
function Save-Json([string]$path,$value) {
    $bytes=[Text.UTF8Encoding]::new($false).GetBytes((ConvertTo-Json -InputObject $value -Depth 6))
    $out=[IO.File]::Open($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $out.Write($bytes,0,$bytes.Length);$out.Flush($true) } finally { $out.Dispose() }
}
function Restore([string]$folder) {
    $folder=[IO.Path]::GetFullPath($folder).TrimEnd('\','/')
    $base=Safe-Path $ClusterRoot 'update-backups'
    if (!$folder.StartsWith($base+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Rollback must use this installation update-backups folder' }
    $recordPath=Safe-Path $folder 'rollback.json'
    $record=Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
    if ($record.schema -ne 1 -or $record.root -ne $ClusterRoot) { throw 'Rollback belongs to another installation' }
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    if (@($record.files).Count -ne $allowed.Count) { throw 'Incomplete rollback manifest' }
    foreach ($item in $record.files) {
        $null=Destination $item.path
        if (!$seen.Add($item.path)) { throw 'Duplicate rollback path' }
        if ($item.existed) {
            $source=Safe-Path $folder $item.path
            if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $item.sha256) { throw "Rollback checksum failed: $($item.path)" }
        }
    }
    if (!(Test-Path -LiteralPath $marker)) { Save-Json $marker @{version="rollback";backup=$folder} }
    # Restore launcher last, leaving its interruption guard active until payload restoration finishes.
    $items=@($record.files);[Array]::Reverse($items)
    foreach ($item in $items) {
        $dest=Destination $item.path
        if ($item.existed) { Replace-File (Safe-Path $folder $item.path) $dest }
        elseif (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Force }
    }
}
$null=Safe-Path $ClusterRoot 'config/cluster.properties'
if (!(Test-Path -LiteralPath (Join-Path $ClusterRoot 'config/cluster.properties') -PathType Leaf)) { throw "Missing config/cluster.properties in '$ClusterRoot'. Select your existing stopped Cluster folder. For a fresh source download, run start_foundations_server.bat once before applying an updater." }
$running=@(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object { $_.Name -in @('java.exe','javaw.exe') -and $_.CommandLine -and $_.CommandLine.IndexOf($ClusterRoot,[StringComparison]::OrdinalIgnoreCase) -ge 0 })
if ($running.Count) { throw 'Stop Manager, gateway and workers before updating. No processes were terminated.' }
$lockPath=Safe-Path $ClusterRoot 'runtime/cluster-manager.lock'
New-Item -ItemType Directory -Path (Split-Path -Parent $lockPath) -Force | Out-Null
$lock=[IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
$marker=Safe-Path $ClusterRoot 'runtime/update-in-progress.json'
$backup=$null;$started=$false
try {
    if ($Rollback) { Restore $Rollback; if(Test-Path -LiteralPath $marker){Remove-Item -LiteralPath $marker}; Write-Host 'Rollback complete.'; return }
    if (Test-Path -LiteralPath $marker) { throw 'Previous update interrupted. Use -Rollback with the backup named in runtime/update-in-progress.json.' }
    $package=Split-Path -Parent $PSScriptRoot
    $manifest=Get-Content -LiteralPath (Safe-Path $package 'UPDATE_MANIFEST.json') -Raw | ConvertFrom-Json
    if ($manifest.schema -ne 1 -or $manifest.component -ne 'manager-gateway-node' -or $manifest.version -ne '0.0.6a.R19-HF15') { throw 'Unsupported updater manifest' }
    $jar=Destination 'cluster-manager/build/FoundationsClusterManager.jar'
    $zip=[IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $entry=$zip.GetEntry('com/foundations/cluster/manager/ClusterVersion.class')
        if (!$entry) { throw 'Manager version class missing' }
        $reader=[IO.StreamReader]::new($entry.Open(),[Text.Encoding]::ASCII)
        try { $versionBytes=$reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($versionBytes -notmatch '0\.0\.6a\.R19-HF(?:11|12|13|14|15)(?![0-9])') { throw 'This updater requires the HF11/Node R13 baseline, HF12, HF13, HF14 or HF15. Install the matching baseline first.' }
    } finally { $zip.Dispose() }
    $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    if (@($manifest.files).Count -ne $allowed.Count) { throw 'Incomplete updater manifest' }
    foreach ($item in $manifest.files) {
        $dest=Destination $item.path
        if (!$seen.Add($item.path) -or $item.sha256 -notmatch '^[0-9a-fA-F]{64}$') { throw 'Duplicate path or invalid checksum' }
        if ((Test-Path -LiteralPath $dest) -and !(Test-Path -LiteralPath $dest -PathType Leaf)) { throw "Destination is not a file: $dest" }
        $source=Safe-Path $package ('payload/'+$item.path)
        if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $item.sha256) { throw "Payload checksum failed: $($item.path)" }
    }
    $relative='update-backups/HF15-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N')
    $backup=Safe-Path $ClusterRoot $relative;New-Item -ItemType Directory -Path $backup | Out-Null
    $records=@()
    # Complete and flush the whole backup before modifying a single installed file.
    foreach ($path in $allowed) {
        $dest=Destination $path;$existed=Test-Path -LiteralPath $dest -PathType Leaf;$hash=$null
        if ($existed) {
            $copy=Safe-Path $backup $path;Replace-File $dest $copy
            $hash=(Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash
            if ((Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash -ne $hash) { throw 'Installed file changed during backup' }
        }
        $records+=@{path=$path;existed=$existed;sha256=$hash}
    }
    Save-Json (Join-Path $backup 'rollback.json') @{schema=1;root=$ClusterRoot;files=$records}
    Save-Json $marker @{version=$manifest.version;backup=$backup};$started=$true
    foreach ($path in $allowed) { Replace-File (Safe-Path $package ('payload/'+$path)) (Destination $path) }
    foreach ($item in $manifest.files) {
        if ((Get-FileHash -LiteralPath (Destination $item.path) -Algorithm SHA256).Hash -ne $item.sha256) { throw 'Installed checksum mismatch' }
    }
    Remove-Item -LiteralPath $marker
    Write-Host "Installed $($manifest.version). Backup: $backup" -ForegroundColor Green
    Write-Host 'HF15 installed; Node R14 is staged. Start the cluster to upgrade the template and stopped workers. Worlds, credentials and configuration were preserved. Remote Survival editing remains disabled.'
} catch {
    $failure=$_
    if ($started) {
        try { Restore $backup;Remove-Item -LiteralPath $marker;Write-Warning 'Update failed; original application files restored.' }
        catch { Write-Warning "Rollback incomplete. Keep cluster stopped. Backup: $backup. $($_.Exception.Message)" }
    }
    throw $failure
} finally { $lock.Dispose() }
