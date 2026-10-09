$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$build = Join-Path $root 'reference\build\v22-classes'
New-Item -ItemType Directory -Path $build -Force | Out-Null
$sources = @(Get-ChildItem (Join-Path $root 'reference\src\main\java') -Recurse -Filter '*.java')
$sources += @(Get-ChildItem (Join-Path $root 'reference\src\test\java') -Recurse -Filter '*.java')
$argsFile = Join-Path $build 'sources.txt'
[IO.File]::WriteAllLines($argsFile, [string[]]($sources | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }), [Text.UTF8Encoding]::new($false))
& javac --release 21 -encoding UTF-8 -d $build "@$argsFile"
if ($LASTEXITCODE -ne 0) { throw 'Reference compilation failed' }
foreach ($test in @('com.foundations.cluster.transfer.RegionTransferCoordinatorSelfTest',
    'com.foundations.cluster.persistence.PersistentRegionTransferSelfTest',
    'com.foundations.cluster.replication.ReplicationProtocolSelfTest',
    'com.foundations.cluster.replication.AuthorityResultJournalSelfTest')) {
    & java -cp $build $test
    if ($LASTEXITCODE -ne 0) { throw "Failed: $test" }
}
