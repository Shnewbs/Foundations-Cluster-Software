$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$jdk=& (Join-Path $PSScriptRoot 'ensure_jdk21.ps1')
if(!$jdk -or $LASTEXITCODE -ne 0){throw 'JDK 21 discovery failed'}
$project=Join-Path $root 'cluster-manager'
Push-Location $project
try {
  $stage=Join-Path $project 'build\r18-classes'
  if(Test-Path -LiteralPath $stage){Remove-Item -LiteralPath $stage -Recurse -Force}
  New-Item -ItemType Directory -Path $stage -Force | Out-Null
  $argfile=Join-Path $project 'build\sources.txt'
  $sources=Get-ChildItem -LiteralPath (Join-Path $project 'src\main\java') -Filter '*.java' -Recurse | ForEach-Object {'"'+$_.FullName.Replace('\','/')+'"'}
  $sources+=Get-ChildItem -LiteralPath (Join-Path $root 'protocol\src\main\java') -Filter '*.java' -Recurse | ForEach-Object {'"'+$_.FullName.Replace('\','/')+'"'}
  [IO.File]::WriteAllLines($argfile,[string[]]$sources,[Text.UTF8Encoding]::new($false))
  & (Join-Path $jdk 'bin\javac.exe') --release 21 -encoding UTF-8 -cp 'lib\bcprov.jar' -d $stage "@$argfile"
  if($LASTEXITCODE -ne 0){throw 'Manager compilation failed'}
  $jar=Join-Path $project 'build\FoundationsClusterManager.jar'
  & (Join-Path $jdk 'bin\jar.exe') --create --file ($jar+'.staging') --main-class com.foundations.cluster.manager.Main -C $stage .
  if($LASTEXITCODE -ne 0){throw 'Manager JAR packaging failed'}
  Move-Item -LiteralPath ($jar+'.staging') -Destination $jar -Force
  Write-Host '[Foundations] Manager 0.0.6a.R19 built.'
} finally {Pop-Location}
