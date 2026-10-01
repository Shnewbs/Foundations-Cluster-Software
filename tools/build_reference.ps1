$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$LASTEXITCODE = 0
$jdk = & (Join-Path $PSScriptRoot 'ensure_jdk21.ps1')
if (!$jdk -or $LASTEXITCODE -ne 0) { throw 'JDK 21 discovery failed' }
$build = Join-Path $root 'reference\build\classes'
if (Test-Path -LiteralPath $build) { Remove-Item -LiteralPath $build -Recurse -Force }
New-Item -ItemType Directory -Path $build -Force | Out-Null
$argfile = Join-Path $build 'sources.txt'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'reference\src\main\java'),(Join-Path $root 'reference\src\test\java') -Recurse -Filter '*.java' |
    ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
if ($sources.Count -eq 0) { throw 'Reference Java source is missing' }
[IO.File]::WriteAllLines($argfile, [string[]]$sources, [Text.UTF8Encoding]::new($false))
& (Join-Path $jdk 'bin\javac.exe') --release 21 -encoding UTF-8 -d $build "@$argfile"
if ($LASTEXITCODE -ne 0) { throw 'Reference compilation failed' }
foreach ($test in @('com.foundations.cluster.transfer.RegionTransferCoordinatorSelfTest',
    'com.foundations.cluster.persistence.PersistentRegionTransferSelfTest')) {
    & (Join-Path $jdk 'bin\java.exe') -cp $build $test
    if ($LASTEXITCODE -ne 0) { throw "Failed: $test" }
}
Write-Host 'R7.R4 all reference tests PASS'
