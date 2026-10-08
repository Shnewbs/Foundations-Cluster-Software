$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$VelocityDir = Join-Path $Root "velocity"
$PluginsDir = Join-Path $VelocityDir "plugins"
$TemplateMods = Join-Path $Root "server-template\mods"
$ConfigFile = Join-Path $Root "config\cluster.properties"
$GatewayVersion = "0.0.6a.R19" # Guarded handshake capture and replay.
New-Item -ItemType Directory -Force -Path $VelocityDir,$PluginsDir,$TemplateMods,(Join-Path $Root "logs") | Out-Null

function Get-ConfigValue([string]$Key,[string]$Default) {
  if (!(Test-Path $ConfigFile)) { return $Default }
  $escaped=[regex]::Escape($Key)
  foreach($line in Get-Content $ConfigFile){ if($line -match "^\s*$escaped\s*=\s*(.*?)\s*$"){ return $Matches[1] } }
  return $Default
}

function Test-VelocityPluginJar([string]$Path) {
  if (!(Test-Path $Path)) { return $false }
  try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip=[IO.Compression.ZipFile]::OpenRead($Path)
    try {
      $names=@($zip.Entries | ForEach-Object { $_.FullName })
      if(-not ($names -contains "velocity-plugin.json")){ return $false }
      if(-not ($names -contains "com/foundations/cluster/velocity/FoundationsVelocityGateway.class")){ return $false }
      $entry=$zip.GetEntry("velocity-plugin.json")
      if($null -eq $entry){ return $false }
      $reader=New-Object IO.StreamReader($entry.Open(),[Text.Encoding]::UTF8)
      try { $meta=($reader.ReadToEnd() | ConvertFrom-Json) } finally { $reader.Dispose() }
      return ($meta.id -eq "foundations_gateway" -and $meta.main -eq "com.foundations.cluster.velocity.FoundationsVelocityGateway" -and $meta.version -eq $GatewayVersion)
    } finally { $zip.Dispose() }
  } catch { return $false }
}

$UserAgent = "FoundationsClusterRuntime/0.0.6a.R19 (local server administrator)"
$ConduitVersion = "1.8.0"
$VelocityJar = Join-Path $VelocityDir "velocity.jar"
$ProxyMarker = Join-Path $VelocityDir ".foundations-proxy-runtime"

function Test-ConduitJar([string]$Path) {
  if (!(Test-Path $Path)) { return $false }
  try {
    $item=Get-Item $Path -ErrorAction Stop
    if($item.Length -lt 1048576){ return $false }
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip=[IO.Compression.ZipFile]::OpenRead($Path)
    try {
      return ($null -ne $zip.GetEntry("com/velocitypowered/proxy/conduit/Conduit.class"))
    } finally { $zip.Dispose() }
  } catch { return $false }
}


# Conduit 1.3.5+ follows its upstream proxy toolchain and requires Java 25.
# The Minecraft workers and Cluster Manager intentionally stay on Java 21;
# only the gateway gets this isolated runtime.
$GatewayJavaHome = Join-Path $VelocityDir "runtime\java25"
$GatewayJavaExe = Join-Path $GatewayJavaHome "bin\java.exe"

function Get-JavaMajor([string]$JavaExe) {
  if(!(Test-Path -LiteralPath $JavaExe)){ return 0 }

  # First use the JDK/JRE release metadata. This is deterministic and avoids
  # PowerShell's native stderr redirection quirks with `java -version`.
  try {
    $javaHome = Split-Path -Parent (Split-Path -Parent $JavaExe)
    $releaseFile = Join-Path $javaHome "release"
    if(Test-Path -LiteralPath $releaseFile){
      foreach($line in Get-Content -LiteralPath $releaseFile){
        if($line -match '^JAVA_VERSION="(?<version>[^"]+)"'){
          $version = [string]$Matches.version
          if($version -match '^(?<major>\d+)'){ return [int]$Matches.major }
        }
      }
    }
  } catch {}

  # Fallback: invoke java.exe through ProcessStartInfo and explicitly capture
  # both stdout and stderr instead of relying on PowerShell's native pipeline.
  try {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $JavaExe
    $psi.Arguments = '-version'
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    if(-not $proc.Start()){ return 0 }
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    $text = "$stdout`n$stderr"
    if($text -match '(?i)version\s+"?(?<major>\d+)'){ return [int]$Matches.major }
    if($text -match '(?i)openjdk\s+(?<major>\d+)'){ return [int]$Matches.major }
  } catch {}
  return 0
}

function Test-Java25([string]$Path) {
  return ((Get-JavaMajor $Path) -ge 25)
}

function Get-GatewayFileHash([string]$Path) {
  $sha=[Security.Cryptography.SHA256]::Create()
  $stream=[IO.File]::OpenRead($Path)
  try { return [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
  finally {$stream.Dispose();$sha.Dispose()}
}

function Install-GatewayJava25([switch]$RequireCompiler) {
  if((Test-Java25 $GatewayJavaExe) -and (!$RequireCompiler -or ((Test-Path -LiteralPath (Join-Path $GatewayJavaHome "bin/javac.exe")) -and (Test-Path -LiteralPath (Join-Path $GatewayJavaHome "bin/jar.exe"))))){
    Write-Host "[Foundations Cluster] Gateway Java 25 validated."
    return
  }

  Write-Host "[Foundations Cluster] Preparing isolated Java 25 runtime for Conduit..."
  $arch = if($env:PROCESSOR_ARCHITECTURE -match 'ARM64'){ 'aarch64' } else { 'x64' }
  $headers=@{ "User-Agent"=$UserAgent; "Accept"="application/json" }
  $asset=$null
  $imageTypes = if($RequireCompiler){ @("jdk") } else { @("jre","jdk") }
  foreach($imageType in $imageTypes){
    try {
      $uri="https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=$arch&image_type=$imageType&os=windows"
      $assets=@(Invoke-RestMethod -Headers $headers -Uri $uri -TimeoutSec 45)
      if($assets.Count -gt 0 -and $assets[0].binary.package.link){
        $asset=$assets[0]
        break
      }
    } catch {}
  }
  if(-not $asset){ throw "Could not resolve an official Temurin Java 25 Windows runtime from Adoptium." }

  $pkg=$asset.binary.package
  $tmpZip=Join-Path $env:TEMP "foundations-gateway-java25-$PID.zip"
  $tmpExtract=Join-Path $env:TEMP "foundations-gateway-java25-$PID"
  Remove-Item -LiteralPath $tmpZip -Force -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath $tmpExtract -Recurse -Force -ErrorAction SilentlyContinue

  try {
    Write-Host "[Foundations Cluster] Downloading $($pkg.name)..."
    Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $pkg.link -OutFile $tmpZip -TimeoutSec 300
    if($pkg.checksum){
      $actual=Get-GatewayFileHash $tmpZip
      $expected=([string]$pkg.checksum).ToLowerInvariant()
      if($actual -ne $expected){ throw "Java 25 download checksum mismatch." }
    }

    New-Item -ItemType Directory -Force -Path $tmpExtract | Out-Null
    Expand-Archive -LiteralPath $tmpZip -DestinationPath $tmpExtract -Force
    $java=Get-ChildItem -LiteralPath $tmpExtract -Recurse -File -Filter "java.exe" |
      Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
      Select-Object -First 1
    if(-not $java){ throw "Downloaded Java 25 package did not contain bin\java.exe." }
    $javaHome=Split-Path -Parent (Split-Path -Parent $java.FullName)
    $downloadedMajor = Get-JavaMajor $java.FullName
    if($downloadedMajor -lt 25){ throw "Downloaded gateway Java runtime validation failed (detected major=$downloadedMajor; expected 25+)." }

    Remove-Item -LiteralPath $GatewayJavaHome -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $GatewayJavaHome) | Out-Null
    Copy-Item -LiteralPath $javaHome -Destination $GatewayJavaHome -Recurse -Force

    $installedMajor = Get-JavaMajor $GatewayJavaExe
    if($installedMajor -lt 25){ throw "Installed gateway Java runtime failed validation (detected major=$installedMajor; expected 25+)." }
    Write-Host "[Foundations Cluster] Gateway Java 25 installed at velocity\runtime\java25."
  } finally {
    Remove-Item -LiteralPath $tmpZip -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $tmpExtract -Recurse -Force -ErrorAction SilentlyContinue
  }
}

$BridgeJar = Join-Path $Root "velocity-gateway/build/libs/foundations_conduit_bridge-$GatewayVersion.jar"
$BundledPlugin = Join-Path $Root "velocity-gateway/build/libs/foundations_velocity_gateway-$GatewayVersion.jar"
$GatewayPlugin = Join-Path $PluginsDir "foundations_velocity_gateway-$GatewayVersion.jar"
$BridgePrefix = 'com/velocitypowered/proxy/connection/backend/'
$BridgeRequired = @('SeamlessConfigSessionHandler.class','FoundationsHandshakeCapture.class','FoundationsHandshakeCapture$Transcript.class','FoundationsHandshakeCapture$Cursor.class','FoundationsHandshakeCapture$1.class','FoundationsTagConfiguration.class','FoundationsNeoForgeConfiguration.class')
function Test-ConduitBridgeJar([string]$Path) {
  if(!(Test-Path -LiteralPath $Path -PathType Leaf)){return $false}
  try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $zip=[IO.Compression.ZipFile]::OpenRead($Path)
    try {
      foreach($name in $BridgeRequired){if($null -eq $zip.GetEntry($BridgePrefix+$name)){return $false}}
      $seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
      foreach($entry in $zip.Entries){
        if(!$seen.Add($entry.FullName)){return $false}
        if(!$entry.FullName.EndsWith('.class')){continue}
        if($entry.FullName -notmatch '^com/velocitypowered/proxy/connection/backend/(SeamlessConfigSessionHandler|FoundationsHandshakeCapture|FoundationsTagConfiguration|FoundationsNeoForgeConfiguration)(\$[^/]+)?\.class$'){return $false}
        $stream=$entry.Open()
        try {
          $header=New-Object byte[] 8
          $read=0
          while($read -lt 8){$n=$stream.Read($header,$read,8-$read);if($n -eq 0){return $false};$read+=$n}
          if([BitConverter]::ToString($header) -ne 'CA-FE-BA-BE-00-00-00-45'){return $false}
        } finally {$stream.Dispose()}
      }
      return $true
    } finally {$zip.Dispose()}
  } catch {return $false}
}
$NeedsGatewayBuild = !(Test-ConduitBridgeJar $BridgeJar) -or (!(Test-VelocityPluginJar $GatewayPlugin) -and !(Test-VelocityPluginJar $BundledPlugin))
Install-GatewayJava25 -RequireCompiler:$NeedsGatewayBuild

# R15 keeps Conduit, a Velocity-compatible proxy core, because a normal Velocity
# plugin cannot absorb Minecraft's 1.20.2+ backend Configuration phase. Conduit's
# opt-in seamless switch path keeps the client in PLAY while it configures the
# destination backend. The Foundations plugin still owns placement, snapshots,
# handoff authorization, and commit semantics.
$runtimeOk = (Test-ConduitJar $VelocityJar)
if($runtimeOk){
  # A validated existing core needs no replacement before the overlay stage.
  [IO.File]::WriteAllText($ProxyMarker,"conduit-$ConduitVersion",[Text.UTF8Encoding]::new($false))
} else {
  Write-Host "[Foundations Cluster] Preparing Conduit $ConduitVersion seamless proxy core..."
  $headers=@{ "User-Agent"=$UserAgent; "Accept"="application/vnd.github+json" }
  try {
    $release=$null
    foreach($tag in @("v$ConduitVersion","$ConduitVersion")){
      try {
        $release=Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/tame-gg/conduit/releases/tags/$tag" -TimeoutSec 30
        if($release){ break }
      } catch { $release=$null }
    }
    if(-not $release){ throw "Official GitHub release tag v$ConduitVersion/$ConduitVersion was not found." }
    $asset=@($release.assets) | Where-Object { ([string]$_.name) -match '(?i)^conduit-.*\.jar$' -and ([string]$_.name) -notmatch '(?i)(sources|javadoc|dev|plain)' } | Sort-Object -Property size -Descending | Select-Object -First 1
    if(-not $asset -or -not $asset.browser_download_url){
      throw "GitHub release v$ConduitVersion did not contain a conduit-*.jar asset."
    }

    $tmp=Join-Path $VelocityDir "conduit-$ConduitVersion.download"
    Remove-Item -Force $tmp -ErrorAction SilentlyContinue
    Write-Host "[Foundations Cluster] Downloading $($asset.name)..."
    Invoke-WebRequest -UseBasicParsing -Headers $headers -Uri $asset.browser_download_url -OutFile $tmp -TimeoutSec 180
    if(-not (Test-ConduitJar $tmp)){
      Remove-Item -Force $tmp -ErrorAction SilentlyContinue
      throw "Downloaded Conduit artifact did not pass JAR validation."
    }

    if(Test-Path $VelocityJar){
      $backupDir=Join-Path $VelocityDir "runtime-backup"
      New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
      $backup=Join-Path $backupDir "velocity-pre-r13.jar"
      if(!(Test-Path $backup)){ Copy-Item -Force $VelocityJar $backup }
    }
    if(Test-Path -LiteralPath $VelocityJar){[IO.File]::Replace($tmp,$VelocityJar,[NullString]::Value)}else{[IO.File]::Move($tmp,$VelocityJar)}
    [IO.File]::WriteAllText($ProxyMarker,"conduit-$ConduitVersion",[Text.UTF8Encoding]::new($false))
    Write-Host "[Foundations Cluster] Conduit $ConduitVersion installed as velocity\velocity.jar."
  } catch {
    throw "Unable to prepare Conduit $ConduitVersion. Existing proxy runtime was left intact. $($_.Exception.Message)"
  }
}


# Source archives contain Java source; updaters carry these precompiled artifacts.
# Build against the downloaded Conduit core, avoiding snapshot Maven dependencies.
if($NeedsGatewayBuild){
  & (Join-Path $Root 'tools/build_gateway.ps1') -ConduitJar $VelocityJar -JavaHome $GatewayJavaHome
}
if(!(Test-ConduitBridgeJar $BridgeJar)){throw 'R19 bridge bundle is missing or invalid after gateway build.'}

function Install-FoundationsConduitR15Patch {
  if(!(Test-ConduitJar $VelocityJar)){throw 'Conduit 1.8.0 is unavailable'}
  $backupDir=Join-Path $VelocityDir 'runtime-backup'
  New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
  $backup=Join-Path $backupDir 'conduit-1.8.0-pre-foundations-r19.jar'
  if(!(Test-Path -LiteralPath $backup)){Copy-Item -LiteralPath $VelocityJar -Destination $backup}
  $patchTarget=$VelocityJar+'.r19-staging'
  $bundle=[IO.Compression.ZipFile]::OpenRead($BridgeJar)
  try {
    $classes=@($bundle.Entries | Where-Object {$_.FullName.EndsWith('.class')})
    Copy-Item -LiteralPath $VelocityJar -Destination $patchTarget -Force
    $zip=[IO.Compression.ZipFile]::Open($patchTarget,[IO.Compression.ZipArchiveMode]::Update)
    try {foreach($class in $classes){
      $existing=$zip.GetEntry($class.FullName);if($null -ne $existing){$existing.Delete()}
      $entry=$zip.CreateEntry($class.FullName,[IO.Compression.CompressionLevel]::Optimal)
      $input=$class.Open();$output=$entry.Open()
      try{$input.CopyTo($output)}finally{$input.Dispose();$output.Dispose()}
    }}finally{$zip.Dispose()}
    $zip=[IO.Compression.ZipFile]::OpenRead($patchTarget);$sha=[Security.Cryptography.SHA256]::Create()
    try {foreach($class in $classes){
      $entry=$zip.GetEntry($class.FullName)
      if($null -eq $entry){throw "R19 bridge verification: missing $($class.FullName)"}
      $input=$class.Open();$output=$entry.Open()
      try{
        $want=[BitConverter]::ToString($sha.ComputeHash($input))
        $have=[BitConverter]::ToString($sha.ComputeHash($output))
        if($have -ne $want){throw "R19 bridge verification failed: $($class.FullName)"}
      }finally{$input.Dispose();$output.Dispose()}
    }}finally{$zip.Dispose();$sha.Dispose()}
    [IO.File]::Replace($patchTarget,$VelocityJar,[NullString]::Value)
    Write-Host '[Foundations Cluster] R19 guarded NeoForge capture/replay bridge installed and verified.'
  }finally{
    $bundle.Dispose()
    if(Test-Path -LiteralPath $patchTarget){Remove-Item -LiteralPath $patchTarget -Force}
  }
}
Install-FoundationsConduitR15Patch


# R15 hotfix: Conduit 1.8.0's config migrator reads conduit.toml with Files.readString().
# A corrupt/sparse/accidentally enormous TOML can therefore fail before the proxy binds.
# Keep an operator-readable quarantine copy by MOVE (not copy, which could duplicate a multi-GB file),
# then let the normal key writer create a small sane file that Conduit can self-migrate.
function Ensure-SaneConduitToml([string]$Path) {
  if(!(Test-Path -LiteralPath $Path)){ return }
  try {
    $item=Get-Item -LiteralPath $Path -ErrorAction Stop
    $maxBytes=1048576 # 1 MiB: normal Conduit config is only tens of KiB.
    if($item.PSIsContainer -or $item.Length -gt $maxBytes){
      $quarantineDir=Join-Path $VelocityDir "config-quarantine"
      New-Item -ItemType Directory -Force -Path $quarantineDir | Out-Null
      $stamp=Get-Date -Format "yyyyMMdd-HHmmss"
      $dest=Join-Path $quarantineDir ("conduit.toml.oversize-or-invalid-"+$stamp)
      Move-Item -LiteralPath $Path -Destination $dest -Force
      Write-Warning "[Foundations Cluster] Quarantined invalid Conduit config ($($item.Length) bytes): $dest"
    }
  } catch {
    throw "Unable to validate velocity\\conduit.toml before proxy startup. $($_.Exception.Message)"
  }
}

function Set-TomlSectionKey([string]$Path,[string]$Section,[string]$Key,[string]$Value) {
  $lines = @()
  if(Test-Path $Path){ $lines=@(Get-Content $Path) }
  $sectionHeader="[$Section]"
  $sectionIndex=-1
  $nextSection=$lines.Count
  for($i=0;$i -lt $lines.Count;$i++){
    if($lines[$i].Trim() -eq $sectionHeader){
      $sectionIndex=$i
      for($j=$i+1;$j -lt $lines.Count;$j++){
        if($lines[$j].Trim() -match '^\[.+\]$'){ $nextSection=$j; break }
      }
      break
    }
  }
  if($sectionIndex -lt 0){
    if($lines.Count -gt 0 -and $lines[-1].Trim() -ne ''){ $lines += '' }
    $lines += $sectionHeader
    $lines += "$Key = $Value"
  } else {
    $escaped=[regex]::Escape($Key)
    $found=-1
    for($i=$sectionIndex+1;$i -lt $nextSection;$i++){
      if($lines[$i] -match "^\s*$escaped\s*="){ $found=$i; break }
    }
    if($found -ge 0){
      $lines[$found]="$Key = $Value"
    } else {
      $before=@()
      $after=@()
      if($nextSection -gt 0){ $before=@($lines[0..($nextSection-1)]) }
      if($nextSection -lt $lines.Count){ $after=@($lines[$nextSection..($lines.Count-1)]) }
      $lines=@($before + "$Key = $Value" + $after)
    }
  }
  [IO.File]::WriteAllLines($Path,[string[]]$lines,[Text.UTF8Encoding]::new($false))
}

$ConduitToml=Join-Path $VelocityDir "conduit.toml"
Ensure-SaneConduitToml $ConduitToml
$SeamlessEnabled = Get-ConfigValue "gateway.seamless.enabled" "false"
if($SeamlessEnabled -notin @("true","false")){ throw "gateway.seamless.enabled must be true or false" }
Set-TomlSectionKey $ConduitToml "advanced" "seamless-server-switches" $SeamlessEnabled
Set-TomlSectionKey $ConduitToml "advanced" "seamless-switch-settle-ms" "250"
Set-TomlSectionKey $ConduitToml "advanced" "seamless-switch-sound-enabled" "false"
Set-TomlSectionKey $ConduitToml "modded" "max-known-packs" "2048"

# Modern forwarding secret. Velocity owns authentication; backend workers run offline-mode and trust only this proxy secret.
$Secret = Join-Path $VelocityDir "forwarding.secret"
if (!(Test-Path $Secret)) {
  $bytes=New-Object byte[] 32
  $rng=[System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  [IO.File]::WriteAllText($Secret,[Convert]::ToBase64String($bytes),[Text.UTF8Encoding]::new($false))
}
Copy-Item -Force $Secret (Join-Path $Root "server-template\forwarding.secret")

# NeoForge 1.21.1 modern forwarding + server-switch compatibility.
# Proxy Compatible Forge is installed into the worker template and configured with the same Velocity secret.
Get-ChildItem $TemplateMods -Filter "velocity-support-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
$ForwardingMod = Join-Path $TemplateMods "proxy-compatible-forge-1.2.2.jar"
function Test-ForwardingModJar([string]$Path) {
  if(!(Test-Path -LiteralPath $Path)){return $false}
  try {
    $zip=[IO.Compression.ZipFile]::OpenRead($Path)
    try {
      $entry=$zip.GetEntry("META-INF/neoforge.mods.toml")
      if(!$entry){return $false}
      $reader=New-Object IO.StreamReader($entry.Open(),[Text.Encoding]::UTF8)
      try {$meta=$reader.ReadToEnd()} finally {$reader.Dispose()}
      return ($meta -match '(?m)^modId\s*=\s*"pcf"' -and $meta -match '(?m)^version\s*=\s*"1\.2\.2"')
    } finally {$zip.Dispose()}
  } catch {return $false}
}
if (!(Test-ForwardingModJar $ForwardingMod)) {
  Write-Host "[Foundations Cluster] Downloading Proxy Compatible Forge 1.2.2..."
  $forwardingStage=$ForwardingMod+".download"
  try {
    Invoke-WebRequest -UseBasicParsing -Headers @{"User-Agent"=$UserAgent} -Uri "https://cdn.modrinth.com/data/vDyrHl8l/versions/mNsCcGDZ/proxy-compatible-forge-1.2.2.jar" -OutFile $forwardingStage -TimeoutSec 120
    if(!(Test-ForwardingModJar $forwardingStage)){throw "Proxy Compatible Forge download is not a valid NeoForge 1.2.2 JAR."}
    if(Test-Path -LiteralPath $ForwardingMod){[IO.File]::Replace($forwardingStage,$ForwardingMod,[NullString]::Value)}else{[IO.File]::Move($forwardingStage,$ForwardingMod)}
  } finally {if(Test-Path -LiteralPath $forwardingStage){Remove-Item -LiteralPath $forwardingStage -Force}}
}
Write-Host "[Foundations Cluster] Proxy Compatible Forge 1.2.2 validated in server-template/mods."
$PcfConfigDir=Join-Path $Root "server-template\config"
New-Item -ItemType Directory -Force -Path $PcfConfigDir | Out-Null
$SecretText=(Get-Content -Raw $Secret).Trim().Replace('"','\"')
$PcfToml=@"
[forwarding]
enabled = true
mode = "MODERN"
secret = "$SecretText"
approvedProxyHosts = ["127.0.0.1", "::1"]

[crossStitch]
enabled = true
forceWrappedArguments = []
forceWrapVanillaArguments = false

[debug]
enabled = false
disabledMixins = []

[advanced]
modernForwardingVersion = "NO_OVERRIDE"
"@

[IO.File]::WriteAllText((Join-Path $PcfConfigDir "proxy-compatible-forge.toml"),$PcfToml,[Text.UTF8Encoding]::new($false))

# R13 forwarding synchronization: synchronize modern-forwarding prerequisites into every
# pre-existing worker directory too. This makes in-place upgrades safe even
# when workers were originally created before the Velocity/PCF gateway existed.
function Set-ServerProperty([string]$Path,[string]$Key,[string]$Value) {
  $lines = @()
  if (Test-Path $Path) { $lines = @(Get-Content $Path) }
  $escaped = [regex]::Escape($Key)
  $found = $false
  $out = foreach($line in $lines) {
    if($line -match "^\s*$escaped\s*=") { $found=$true; "$Key=$Value" } else { $line }
  }
  if(-not $found) { $out += "$Key=$Value" }
  [IO.File]::WriteAllLines($Path,[string[]]$out,[Text.UTF8Encoding]::new($false))
}

$WorkersRoot = Join-Path $Root "workers"
if(Test-Path $WorkersRoot){
  Get-ChildItem $WorkersRoot -Directory -Filter "worker-*" -ErrorAction SilentlyContinue | ForEach-Object {
    $WorkerDir=$_.FullName
    $WorkerMods=Join-Path $WorkerDir "mods"
    $WorkerConfig=Join-Path $WorkerDir "config"
    New-Item -ItemType Directory -Force -Path $WorkerMods,$WorkerConfig | Out-Null
    Get-ChildItem $WorkerMods -Filter "proxy-compatible-forge-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
    Copy-Item -Force $ForwardingMod (Join-Path $WorkerMods (Split-Path -Leaf $ForwardingMod))
    Copy-Item -Force (Join-Path $PcfConfigDir "proxy-compatible-forge.toml") (Join-Path $WorkerConfig "proxy-compatible-forge.toml")
    Copy-Item -Force $Secret (Join-Path $WorkerDir "forwarding.secret")
    $ServerProps=Join-Path $WorkerDir "server.properties"
    Set-ServerProperty $ServerProps "online-mode" "false"
    Set-ServerProperty $ServerProps "server-ip" (Get-ConfigValue "worker.bindAddress" "127.0.0.1")
    Write-Host "[Foundations Cluster] Synchronized Velocity forwarding prerequisites into $($_.Name)."
  }
}

# Build/install Foundations Velocity adapter. Remove older revisions first so
# Velocity never sees two JARs with the same plugin id after an in-place upgrade.
# R14 also validates the plugin JAR contents before allowing Velocity to start;
# a Gradle `jar` task with missing Java source is NOT considered a successful build.
$GatewayPlugin = Join-Path $PluginsDir "foundations_velocity_gateway-0.0.6a.R19.jar"

if((Test-Path $GatewayPlugin) -and -not (Test-VelocityPluginJar $GatewayPlugin)) {
  Write-Host "[Foundations Cluster] Removing invalid/stale Foundations Velocity plugin JAR..."
  Remove-Item -Force $GatewayPlugin -ErrorAction SilentlyContinue
}

# HF13 updaters include the compiled adapter; source startup builds it above.
if ((Test-VelocityPluginJar $BundledPlugin) -and (!(Test-VelocityPluginJar $GatewayPlugin) -or (Get-GatewayFileHash $BundledPlugin) -ne (Get-GatewayFileHash $GatewayPlugin))) {
  $pluginStage=$GatewayPlugin+'.staging'
  try {
    Copy-Item -LiteralPath $BundledPlugin -Destination $pluginStage -Force
    if(Test-Path -LiteralPath $GatewayPlugin){[IO.File]::Replace($pluginStage,$GatewayPlugin,[NullString]::Value)}else{[IO.File]::Move($pluginStage,$GatewayPlugin)}
  } finally {if(Test-Path -LiteralPath $pluginStage){Remove-Item -LiteralPath $pluginStage -Force}}
  Write-Host "[Foundations Cluster] Installed bundled Foundations Gateway $GatewayVersion."
}

if(-not (Test-VelocityPluginJar $GatewayPlugin)){
  throw "Foundations Velocity plugin validation failed after installation: $GatewayPlugin"
}
Get-ChildItem $PluginsDir -Filter "foundations_velocity_gateway-*.jar" -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -ne $GatewayPlugin } |
  Remove-Item -Force -ErrorAction SilentlyContinue

Write-Host "[Foundations Cluster] Foundations Velocity plugin validated: $(Split-Path -Leaf $GatewayPlugin)"

$Bind=Get-ConfigValue "gateway.bind" "0.0.0.0"
$Port=Get-ConfigValue "gateway.port" "25565"
$Motd=(Get-ConfigValue "gateway.motd" "Foundations Cluster").Replace('"','\"')
$Toml=@"
config-version = "2.7"
bind = "$Bind`:$Port"
motd = "$Motd"
show-max-players = 100
online-mode = true
force-key-authentication = true
prevent-client-proxy-connections = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
announce-forge = true
kick-existing-players = false
ping-passthrough = "DISABLED"

[servers]
try = []

# Velocity 3.x may merge its built-in sample forced hosts when this section is
# absent. Foundations registers backend workers dynamically, so keep it explicit
# and empty.
[forced-hosts]

[advanced]
compression-threshold = 256
compression-level = -1
login-ratelimit = 100
connection-timeout = 5000
read-timeout = 30000
haproxy-protocol = false
tcp-fast-open = false
bungee-plugin-message-channel = true
show-ping-requests = false
failover-on-unexpected-server-disconnect = true
announce-proxy-commands = true
log-command-executions = false
log-player-connections = true
accepts-transfers = false
"@
$VelocityToml = Join-Path $VelocityDir "velocity.toml"
[IO.File]::WriteAllText($VelocityToml,$Toml,[Text.UTF8Encoding]::new($false))
$WrittenToml = Get-Content -Raw $VelocityToml
if ($WrittenToml -notmatch '(?m)^\[forced-hosts\]\s*$') { throw "Velocity config validation failed: explicit [forced-hosts] section missing." }
if ($WrittenToml -match 'lobby\.example\.com|factions\.example\.com|minigames\.example\.com') { throw "Velocity config validation failed: example forced hosts are still present." }
Write-Host "[Foundations Cluster] Conduit seamless session gateway is prepared."
