$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Expected = "0.0.5a.R7.R4"
$NodeName = "foundations_cluster_node-$Expected.jar"
$NodeSource = Join-Path $Root "minecraft-agent\build\libs\$NodeName"
$ManagerJar = Join-Path $Root "cluster-manager\build\FoundationsClusterManager.jar"
$Config = Join-Path $Root "config\cluster.properties"

function Get-ConfigValue([string]$Key, [string]$Default) {
    if (!(Test-Path $Config)) { return $Default }
    $escaped = [regex]::Escape($Key)
    foreach ($line in Get-Content $Config) {
        if ($line -match "^\s*$escaped\s*=\s*(.*?)\s*$") { return $Matches[1] }
    }
    return $Default
}

function Ensure-ConfigLine([string]$Key, [string]$Value) {
    if (!(Test-Path $Config)) { return }
    $escaped = [regex]::Escape($Key)
    if (-not (Select-String -Path $Config -Pattern "^\s*$escaped\s*=" -Quiet)) {
        Add-Content -Path $Config -Value "$Key=$Value"
        Write-Host "  + config: $Key=$Value"
    }
}

Write-Host "[Foundations Cluster] Applying $Expected restart-persistence update..." -ForegroundColor Cyan

if (!(Test-Path $ManagerJar)) { throw "Missing R7.R4 manager JAR: $ManagerJar" }
if (!(Test-Path $NodeSource)) { throw "Missing R7.R4 Cluster Node JAR: $NodeSource" }

# Refuse to mutate a live installation. The manager JAR may be replaceable on Windows,
# but updating worker mods/config while the cluster is live creates an ambiguous test.
$webPort = [int](Get-ConfigValue "web.port" "8765")
try {
    $health = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$webPort/api/health" -TimeoutSec 1
    if ($health) {
        throw "Cluster Manager is still running on port $webPort (reported version $($health.version)). Shut it down from the dashboard, then run this updater again."
    }
} catch {
    if ($_.Exception.Message -like "Cluster Manager is still running*") { throw }
}

if (Test-Path $Config) {
    $backup = "$Config.pre-r7r4.bak"
    if (!(Test-Path $backup)) { Copy-Item $Config $backup -Force }
    Ensure-ConfigLine "region.startupRecoverySeconds" "90"
    Ensure-ConfigLine "persistence.blockJournalEnabled" "true"
    Ensure-ConfigLine "persistence.checkpointSeconds" "30"
}

$templateMods = Join-Path $Root "server-template\mods"
New-Item -ItemType Directory -Force -Path $templateMods | Out-Null
Get-ChildItem $templateMods -Filter "foundations_cluster_node-*.jar" -File -ErrorAction SilentlyContinue | Remove-Item -Force
Copy-Item $NodeSource (Join-Path $templateMods $NodeName) -Force
Write-Host "  + installed $NodeName into server-template\mods"

$workersDir = Join-Path $Root "workers"
if (Test-Path $workersDir) {
    foreach ($worker in Get-ChildItem $workersDir -Directory -Filter "worker-*") {
        $mods = Join-Path $worker.FullName "mods"
        New-Item -ItemType Directory -Force -Path $mods | Out-Null
        Get-ChildItem $mods -Filter "foundations_cluster_node-*.jar" -File -ErrorAction SilentlyContinue | Remove-Item -Force
        Copy-Item $NodeSource (Join-Path $mods $NodeName) -Force
        Write-Host "  + updated $($worker.Name) Cluster Node"
    }
}

Write-Host ""
Write-Host "[Foundations Cluster] $Expected applied." -ForegroundColor Green
Write-Host "No worker world directory or runtime persistence file was deleted or replaced." -ForegroundColor Green
Write-Host "Start normally with start_foundations_server.bat, then run the restart persistence test." -ForegroundColor Yellow
