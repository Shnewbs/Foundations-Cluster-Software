param([string]$ClusterRoot,[string]$Rollback)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Choose-Root {
  try {Add-Type -AssemblyName System.Windows.Forms;$picker=New-Object System.Windows.Forms.FolderBrowserDialog;$picker.Description='Select your stopped Foundations Cluster folder';if($picker.ShowDialog() -eq 'OK'){$picked=$picker.SelectedPath};$picker.Dispose();if($picked){return $picked}}catch{}
  return (Read-Host 'Full path to the stopped Cluster folder').Trim('"')
}
if(!$ClusterRoot){$ClusterRoot=Choose-Root}
$ClusterRoot=[IO.Path]::GetFullPath($ClusterRoot).TrimEnd('\','/')
if(!(Test-Path -LiteralPath (Join-Path $ClusterRoot 'config\cluster.properties'))){throw 'Select the Cluster root containing config/cluster.properties'}
function Target([string]$relative){
  if([IO.Path]::IsPathRooted($relative) -or $relative -match '(^|[\\/])\.\.([\\/]|$)' -or $relative.Contains(':')){throw "Unsafe relative path: $relative"}
  $p=[IO.Path]::GetFullPath((Join-Path $ClusterRoot $relative))
  if(!$p.StartsWith($ClusterRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Path escapes Cluster root'}
  $parent=$p
  while($parent -and $parent.Length -ge $ClusterRoot.Length){if((Test-Path -LiteralPath $parent) -and ((Get-Item -LiteralPath $parent -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw "Reparse point refused: $parent"};$parent=Split-Path -Parent $parent}
  return $p
}
$running=@(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {$_.Name -in @('java.exe','javaw.exe') -and $_.CommandLine -and $_.CommandLine.IndexOf($ClusterRoot,[StringComparison]::OrdinalIgnoreCase) -ge 0})
if($running.Count){throw 'Stop the Manager, gateway and all workers before updating. No processes were terminated.'}
$lockPath=Target 'runtime\cluster-manager.lock';New-Item -ItemType Directory -Path (Split-Path -Parent $lockPath) -Force | Out-Null
try{$lock=[IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}catch{throw 'Cluster Manager lock is in use. Stop the Cluster before updating.'}
$backup=$null;$records=New-Object 'System.Collections.Generic.List[object]';$seen=New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
function Record-Backup([string]$relative){
  if(!$seen.Add($relative)){return}
  $path=Target $relative;$existed=Test-Path -LiteralPath $path -PathType Leaf
  if($existed){$dest=Join-Path $backup $relative;New-Item -ItemType Directory -Path (Split-Path -Parent $dest) -Force | Out-Null;Copy-Item -LiteralPath $path -Destination $dest}
  $records.Add([pscustomobject]@{path=$relative;existed=$existed})
  [IO.File]::WriteAllText((Join-Path $backup 'rollback.json'),(ConvertTo-Json -InputObject @($records.ToArray()) -Depth 4),[Text.UTF8Encoding]::new($false))
}
function Install-File([string]$source,[string]$relative){
  Record-Backup $relative;$dest=Target $relative;New-Item -ItemType Directory -Path (Split-Path -Parent $dest) -Force | Out-Null
  $stage=$dest+'.r16-staging';Copy-Item -LiteralPath $source -Destination $stage -Force
  try{if(Test-Path -LiteralPath $dest){[IO.File]::Replace($stage,$dest,[NullString]::Value)}else{[IO.File]::Move($stage,$dest)}}finally{if(Test-Path -LiteralPath $stage){Remove-Item -LiteralPath $stage -Force}}
}
function Restore-Backup([string]$folder){
  $items=@(Get-Content -LiteralPath (Join-Path $folder 'rollback.json') -Raw | ConvertFrom-Json)
  [Array]::Reverse($items)
  foreach($item in $items){$dest=Target $item.path;if($item.existed){$src=Join-Path $folder $item.path;if(!(Test-Path -LiteralPath $src)){throw "Missing rollback file: $($item.path)"};New-Item -ItemType Directory -Path (Split-Path -Parent $dest) -Force|Out-Null;Copy-Item -LiteralPath $src -Destination $dest -Force}else{Remove-Item -LiteralPath $dest -Force -ErrorAction SilentlyContinue}}
}
function Manager-Version([string]$jar){
  $zip=[IO.Compression.ZipFile]::OpenRead($jar)
  try{$entry=$zip.GetEntry('com/foundations/cluster/manager/ClusterVersion.class');if(!$entry){throw 'Manager version class missing'};$ms=New-Object IO.MemoryStream;$stream=$entry.Open();try{$stream.CopyTo($ms)}finally{$stream.Dispose()};$text=[Text.Encoding]::ASCII.GetString($ms.ToArray());$ms.Dispose();if($text -match '0\.0\.6a\.R\d+'){return $Matches[0]};throw 'Manager version not recognized'}finally{$zip.Dispose()}
}
try {
  if($Rollback){Restore-Backup ([IO.Path]::GetFullPath($Rollback));Write-Host 'Rollback completed.';exit 0}
  $package=Split-Path -Parent $PSScriptRoot;$payload=Join-Path $package 'payload';$manifest=Get-Content -LiteralPath (Join-Path $package 'R16_MANIFEST.json') -Raw|ConvertFrom-Json
  $managerPath=Target 'cluster-manager\build\FoundationsClusterManager.jar';$version=Manager-Version $managerPath
  if($version -notin @('0.0.6a.R15','0.0.6a.R16')){throw "Expected R15 or R16 Manager, found $version. Use a matching baseline."}
  foreach($item in $manifest.files){
    if($item.path -match '^(runtime|workers|logs|server-template|velocity)[\\/]' -or $item.path -eq 'config/cluster.properties'){throw "Protected payload path: $($item.path)"}
    $null=Target $item.path;$src=Join-Path $payload $item.path
    if((Get-FileHash -LiteralPath $src -Algorithm SHA256).Hash -ne $item.sha256){throw "Payload checksum failed: $($item.path)"}
  }
  $backup=Join-Path $ClusterRoot ('update-backups\R16-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6));New-Item -ItemType Directory -Path $backup | Out-Null
  foreach($item in $manifest.files){Install-File (Join-Path $payload $item.path) $item.path}
  foreach($relative in @($manifest.remove)){
    if($relative -ne 'reference/src/test/java/com/foundations/cluster/persistence/RegionTransferCoordinatorSelfTest.java'){throw 'Unexpected removal in update manifest'}
    if(Test-Path -LiteralPath (Target $relative)){Record-Backup $relative;Remove-Item -LiteralPath (Target $relative)}
  }
  # Normalize only Foundations binaries; every replaced/removed binary is backed up.
  $gatewayName='foundations_velocity_gateway-0.0.6a.R16.jar';$nodeName='foundations_cluster_node-0.0.6a.R7.jar'
  foreach($old in @(Get-ChildItem -LiteralPath (Target 'velocity/plugins') -Filter 'foundations_velocity_gateway-*.jar' -ErrorAction SilentlyContinue)){$rel='velocity/plugins/'+$old.Name;Record-Backup $rel;Remove-Item -LiteralPath $old.FullName}
  Install-File (Target ('velocity-gateway/build/libs/'+$gatewayName)) ('velocity/plugins/'+$gatewayName)
  $modDirs=@('server-template/mods')
  foreach($worker in @(Get-ChildItem -LiteralPath (Target 'workers') -Directory -Filter 'worker-*' -ErrorAction SilentlyContinue)){$modDirs+=('workers/'+$worker.Name+'/mods')}
  foreach($mods in $modDirs){foreach($old in @(Get-ChildItem -LiteralPath (Target $mods) -Filter 'foundations_cluster_node-*.jar' -ErrorAction SilentlyContinue)){$rel=$mods+'/'+$old.Name;Record-Backup $rel;Remove-Item -LiteralPath $old.FullName};Install-File (Target ('minecraft-agent/build/libs/'+$nodeName)) ($mods+'/'+$nodeName)}
  # Patch a staging copy of the existing Conduit core; do not bootstrap unrelated services here.
  $core=Target 'velocity/velocity.jar';if(Test-Path -LiteralPath $core){
    Record-Backup 'velocity/velocity.jar';$stage=$core+'.r16-staging';Copy-Item -LiteralPath $core -Destination $stage
    try {
    $zip=[IO.Compression.ZipFile]::Open($stage,[IO.Compression.ZipArchiveMode]::Update)
    try{if(!$zip.GetEntry('com/velocitypowered/proxy/conduit/Conduit.class')){throw 'Proxy is not the expected Conduit runtime'};$name='com/velocitypowered/proxy/connection/backend/SeamlessConfigSessionHandler.class';$old=$zip.GetEntry($name);if($old){$old.Delete()};$entry=$zip.CreateEntry($name);$stream=$entry.Open();try{$data=[IO.File]::ReadAllBytes((Target 'tools/conduit-r15-patch/SeamlessConfigSessionHandler.class'));$stream.Write($data,0,$data.Length)}finally{$stream.Dispose()}}finally{$zip.Dispose()}
    [IO.File]::Replace($stage,$core,[NullString]::Value)
    } finally {if(Test-Path -LiteralPath $stage){Remove-Item -LiteralPath $stage -Force}}
  }
  if((Manager-Version $managerPath) -ne '0.0.6a.R16'){throw 'Installed Manager version validation failed'}
  foreach($item in $manifest.files){if((Get-FileHash -LiteralPath (Target $item.path) -Algorithm SHA256).Hash -ne $item.sha256){throw "Installed payload checksum failed: $($item.path)"}}
  Write-Host 'Foundations R16 installed. Worlds, profiles, security and active config preserved.' -ForegroundColor Green
  Write-Host "Backup: $backup"
  Write-Host 'Start start_foundations_server.bat, then run WebUI Test Lab. Seamless mode remains experimental.'
} catch {
  $failure=$_
  if($backup){try{Restore-Backup $backup;Write-Warning "Update failed; changed files restored. Backup: $backup"}catch{Write-Warning "Automatic rollback incomplete: $($_.Exception.Message). Use -Rollback with backup $backup before starting."}}
  throw $failure
} finally {$lock.Dispose()}
