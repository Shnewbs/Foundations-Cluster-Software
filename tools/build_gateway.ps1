param(
    [string]$ConduitJar,
    [string]$JavaHome
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
if (!$ConduitJar) { $ConduitJar = Join-Path $Root 'velocity/velocity.jar' }
if (!$JavaHome) { $JavaHome = Join-Path $Root 'velocity/runtime/java25' }
$ConduitJar = [IO.Path]::GetFullPath($ConduitJar)
$JavaHome = [IO.Path]::GetFullPath($JavaHome)
$javac = Join-Path $JavaHome 'bin/javac.exe'
$jar = Join-Path $JavaHome 'bin/jar.exe'
if (!(Test-Path -LiteralPath $javac -PathType Leaf) -or !(Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw 'Gateway source builds require a full JDK 25. The launcher installs one automatically when needed.'
}
if (!(Test-Path -LiteralPath $ConduitJar -PathType Leaf)) { throw 'Prepare Conduit 1.8.0 before building the gateway.' }
$version = '0.0.6a.R19'
$bridgeSources = @('SeamlessConfigSessionHandler.java','FoundationsHandshakeCapture.java','FoundationsTagConfiguration.java','FoundationsNeoForgeConfiguration.java')
$required = @('SeamlessConfigSessionHandler.class','FoundationsHandshakeCapture.class','FoundationsHandshakeCapture$Transcript.class','FoundationsHandshakeCapture$Cursor.class','FoundationsHandshakeCapture$1.class','FoundationsTagConfiguration.class','FoundationsNeoForgeConfiguration.class')
$sourcePaths = @($bridgeSources | ForEach-Object { Join-Path $Root ('tools/conduit-r15-patch/'+$_) })
$pluginSource = Join-Path $Root 'velocity-gateway/source/FoundationsVelocityGateway.java'
$metadata = Join-Path $Root 'velocity-gateway/resources/velocity-plugin.json'
foreach ($source in @($sourcePaths) + @($pluginSource,$metadata)) {
    if (!(Test-Path -LiteralPath $source -PathType Leaf)) { throw "Gateway source missing: $source" }
}
# Compile in a unique directory so a previous build can never supply stale classes.
$buildRoot = [IO.Path]::GetFullPath((Join-Path $Root 'velocity-gateway/build'))
$stage = Join-Path $buildRoot ('gateway-'+[Guid]::NewGuid().ToString('N'))
$bridge = Join-Path $stage 'bridge'
$plugin = Join-Path $stage 'plugin'
$libs = Join-Path $buildRoot 'libs'
New-Item -ItemType Directory -Force -Path $bridge,$plugin,$libs | Out-Null
function Publish-Jar([string]$source,[string]$destination) {
    if (Test-Path -LiteralPath $destination) { [IO.File]::Replace($source,$destination,[NullString]::Value) }
    else { [IO.File]::Move($source,$destination) }
}
try {
    Write-Host '[Foundations Cluster] Compiling R19 bridge and gateway from source...'
    & $javac --release 25 -encoding UTF-8 -proc:none -cp $ConduitJar -d $bridge @sourcePaths
    if ($LASTEXITCODE -ne 0) { throw 'R19 Conduit bridge compilation failed.' }
    $package = Join-Path $bridge 'com/velocitypowered/proxy/connection/backend'
    foreach ($name in $required) {
        if (!(Test-Path -LiteralPath (Join-Path $package $name) -PathType Leaf)) { throw "R19 bridge build did not produce $name" }
    }
    & $javac --release 21 -encoding UTF-8 -proc:none -cp $ConduitJar -d $plugin $pluginSource
    if ($LASTEXITCODE -ne 0) { throw 'Foundations gateway plugin compilation failed.' }
    Copy-Item -LiteralPath $metadata -Destination (Join-Path $plugin 'velocity-plugin.json')
    Copy-Item -LiteralPath (Join-Path $Root 'tools/conduit-r15-patch/GPL-3.0.txt') -Destination (Join-Path $bridge 'GPL-3.0.txt')
    $bridgeJar = Join-Path $stage "foundations_conduit_bridge-$version.jar"
    $pluginJar = Join-Path $stage "foundations_velocity_gateway-$version.jar"
    & $jar --create --file $bridgeJar -C $bridge .
    if ($LASTEXITCODE -ne 0) { throw 'R19 bridge packaging failed.' }
    & $jar --create --file $pluginJar -C $plugin .
    if ($LASTEXITCODE -ne 0) { throw 'Gateway plugin packaging failed.' }
    # Both builds must succeed before either published artifact changes.
    Publish-Jar $bridgeJar (Join-Path $libs "foundations_conduit_bridge-$version.jar")
    Publish-Jar $pluginJar (Join-Path $libs "foundations_velocity_gateway-$version.jar")
    Write-Host '[Foundations Cluster] R19 bridge (Java 25) and gateway plugin (Java 21) built.'
} finally {
    $resolvedStage = [IO.Path]::GetFullPath($stage)
    if (!$resolvedStage.StartsWith($buildRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Build cleanup escaped its root.' }
    if (Test-Path -LiteralPath $resolvedStage) { Remove-Item -LiteralPath $resolvedStage -Recurse -Force }
}
