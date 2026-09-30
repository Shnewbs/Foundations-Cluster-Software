$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Expected = "0.0.5a.R7.R6"
$NodeName = "foundations_cluster_node-$Expected.jar"
$NodeSource = Join-Path $Root "minecraft-agent\build\libs\$NodeName"
$ManagerJar = Join-Path $Root "cluster-manager\build\FoundationsClusterManager.jar"
$Config = Join-Path $Root "config\cluster.properties"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

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
        $prefix = if ((Get-Item $Config).Length -gt 0) { [Environment]::NewLine } else { "" }
        [System.IO.File]::AppendAllText($Config, $prefix + "$Key=$Value", $Utf8NoBom)
        Write-Host "  + config: $Key=$Value"
    }
}

Write-Host "[Foundations Cluster] Applying $Expected player-continuity + graceful-stop update..." -ForegroundColor Cyan

if (!(Test-Path $ManagerJar)) { throw "Missing R7.R6 manager JAR: $ManagerJar" }
if (!(Test-Path $NodeSource)) { throw "Missing R7.R6 Cluster Node JAR: $NodeSource" }

# Never replace live Node JARs or control-plane code while the manager is up.
$webPort = [int](Get-ConfigValue "web.port" "8765")
try {
    $health = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$webPort/api/health" -TimeoutSec 1
    if ($health) {
        throw "Cluster Manager is still running on port $webPort (reported version $($health.version)). Shut the entire cluster down from the dashboard, then run this updater again."
    }
} catch {
    if ($_.Exception.Message -like "Cluster Manager is still running*") { throw }
}

if (Test-Path $Config) {
    $backup = "$Config.pre-r7r6.bak"
    if (!(Test-Path $backup)) { Copy-Item $Config $backup -Force }

    # Player evacuation gets its own 10s deadline. Do not shorten the existing
    # Minecraft process stop/save grace if the administrator customized it.
    Ensure-ConfigLine "worker.evacuationGraceSeconds" "10"
    Ensure-ConfigLine "handoff.maxCommandsPerHeartbeat" "128"
    Ensure-ConfigLine "playerProfile.enabled" "true"
    Ensure-ConfigLine "playerProfile.sharedFiles" "playerdata/{uuid}.dat;advancements/{uuid}.json;stats/{uuid}.json"
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

$profiles = Join-Path $Root "runtime\player-profiles"
New-Item -ItemType Directory -Force -Path $profiles | Out-Null
Write-Host "  + player profile store ready: runtime\player-profiles"

Write-Host ""
Write-Host "[Foundations Cluster] $Expected applied." -ForegroundColor Green
Write-Host "Normal Stop now drains players/authority for up to 10 seconds before Minecraft stop." -ForegroundColor Green
Write-Host "Force Stop remains the explicit emergency hard-kill path." -ForegroundColor Yellow
Write-Host "Playerdata, advancements and stats now checkpoint through the cluster profile store on evacuation." -ForegroundColor Green
Write-Host "Existing worker worlds, R7.R4/R7.R5 persistence journals and the compatible R7.R2 Velocity Gateway were preserved." -ForegroundColor Green
Write-Host "Start normally with start_foundations_server.bat, then run R7_R6_INTEGRATION_CHECKLIST.md." -ForegroundColor Yellow
