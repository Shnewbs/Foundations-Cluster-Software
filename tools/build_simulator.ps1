$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$LASTEXITCODE = 0
$jdk = & (Join-Path $PSScriptRoot 'ensure_jdk21.ps1')
if (!$jdk -or $LASTEXITCODE -ne 0) { throw 'JDK 21 discovery failed' }
$project = Join-Path $root 'node-simulator'
$stage = Join-Path $project 'build\classes'
$argfile = Join-Path $project 'build\sources.txt'
$jar = Join-Path $project 'build\FoundationsClusterNodeSimulator.jar'
Push-Location $project
try {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
    New-Item -ItemType Directory -Path $stage -Force | Out-Null
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $project 'src\main\java') -Filter '*.java' -Recurse |
        ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
    if ($sources.Count -eq 0) { throw 'Node simulator Java source is missing' }
    # javac @files treat backslashes as escapes inside quoted paths. Use forward slashes.
    [IO.File]::WriteAllLines($argfile, [string[]]$sources, [Text.UTF8Encoding]::new($false))
    & (Join-Path $jdk 'bin\javac.exe') --release 21 -encoding UTF-8 -d $stage "@$argfile"
    if ($LASTEXITCODE -ne 0) { throw 'Node simulator compilation failed' }
    & (Join-Path $jdk 'bin\jar.exe') --create --file ($jar + '.staging') --main-class com.foundations.cluster.simulator.Main -C $stage .
    if ($LASTEXITCODE -ne 0) { throw 'Node simulator JAR packaging failed' }
    Move-Item -LiteralPath ($jar + '.staging') -Destination $jar -Force
    Remove-Item -LiteralPath $argfile
    Write-Host '[Foundations Cluster] Built node simulator.'
} finally {
    Pop-Location
}
