param([switch]$NoDownload)
$ErrorActionPreference='Stop'
$clusterRoot=Split-Path -Parent $PSScriptRoot
function Test-Jdk21([string]$candidate){
  if(!$candidate){return $false}
  foreach($tool in @('java.exe','javac.exe','jar.exe')){if(!(Test-Path -LiteralPath (Join-Path $candidate "bin\$tool"))){return $false}}
  $release=Join-Path $candidate 'release'
  if(!(Test-Path -LiteralPath $release)){return $false}
  return [bool]((Get-Content -LiteralPath $release) -match '^JAVA_VERSION="21(?:\.|\+|")')
}
$candidates=New-Object 'System.Collections.Generic.List[string]'
foreach($candidate in @($env:FOUNDATIONS_JDK21,$env:JAVA_HOME,(Join-Path $clusterRoot 'runtime\tools\jdk21'))){if($candidate){$candidates.Add($candidate)}}
$compiler=Get-Command javac.exe -ErrorAction SilentlyContinue
if($compiler){$candidates.Add((Split-Path -Parent (Split-Path -Parent $compiler.Source)))}
foreach($base in @((Join-Path $env:ProgramFiles 'Java'),(Join-Path $env:ProgramFiles 'Eclipse Adoptium'),(Join-Path $env:ProgramFiles 'Microsoft'),(Join-Path $env:LOCALAPPDATA 'Programs\Eclipse Adoptium'))){
  if(Test-Path -LiteralPath $base){Get-ChildItem -LiteralPath $base -Directory | ForEach-Object {$candidates.Add($_.FullName)}}
}
foreach($candidate in $candidates){if(Test-Jdk21 $candidate){Write-Output $candidate;exit 0}}
if($NoDownload){throw 'Full JDK 21 not found. Set FOUNDATIONS_JDK21 to a JDK containing java.exe, javac.exe and jar.exe.'}
[Console]::Error.WriteLine('[Foundations] Downloading a verified Temurin JDK 21 build...')
$arch=if($env:PROCESSOR_ARCHITECTURE -eq 'ARM64'){'aarch64'}else{'x64'}
$uri="https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=$arch&image_type=jdk&os=windows&vendor=eclipse"
$asset=@(Invoke-RestMethod -Uri $uri -TimeoutSec 45)[0]
if(!$asset.binary.package.link -or !$asset.binary.package.checksum){throw 'JDK metadata is incomplete'}
$staging=Join-Path $clusterRoot ('runtime\tools\jdk21-stage-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $staging -Force | Out-Null
try {
  $archive=Join-Path $staging 'jdk.zip'
  Invoke-WebRequest -UseBasicParsing -Uri $asset.binary.package.link -OutFile $archive -TimeoutSec 600
  if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $asset.binary.package.checksum){throw 'JDK SHA-256 validation failed'}
  Expand-Archive -LiteralPath $archive -DestinationPath $staging
  $jdk=Get-ChildItem -LiteralPath $staging -Directory | Where-Object {Test-Jdk21 $_.FullName} | Select-Object -First 1
  if(!$jdk){throw 'Downloaded archive is not a complete JDK 21'}
  $destination=Join-Path $clusterRoot 'runtime\tools\jdk21'
  if(Test-Path -LiteralPath $destination){throw 'An invalid runtime/tools/jdk21 already exists. Move it aside and rerun.'}
  Move-Item -LiteralPath $jdk.FullName -Destination $destination
  Write-Output $destination
} finally {Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue}
