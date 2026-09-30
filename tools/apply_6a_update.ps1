$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Expected = "0.0.6a"
$NodeName = "foundations_cluster_node-$Expected.jar"
$NodeSource = Join-Path $Root "minecraft-agent\build\libs\$NodeName"
$ManagerJar = Join-Path $Root "cluster-manager\build\FoundationsClusterManager.jar"
$Config = Join-Path $Root "config\cluster.properties"
$DefaultConfig = Join-Path $Root "config\cluster.properties.default"
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
    $escaped = [regex]::Escape($Key)
    if (-not (Select-String -Path $Config -Pattern "^\s*$escaped\s*=" -Quiet)) {
        [System.IO.File]::AppendAllText($Config, [Environment]::NewLine + "$Key=$Value", $Utf8NoBom)
        Write-Host "  + config: $Key=$Value"
    }
}

Write-Host "[Foundations Cluster] Applying $Expected Dynamic Cluster Scaling update..." -ForegroundColor Cyan

if (!(Test-Path $ManagerJar)) { throw "Missing 0.0.6a Manager JAR: $ManagerJar" }
if (!(Test-Path $NodeSource)) { throw "Missing 0.0.6a Cluster Node JAR: $NodeSource" }

# Refuse to update live manager/workers.
$webPort = [int](Get-ConfigValue "web.port" "8765")
try {
    $health = Invoke-RestMethod -UseBasicParsing -Uri "http://127.0.0.1:$webPort/api/health" -TimeoutSec 1
    if ($health) { throw "Cluster Manager is still running on port $webPort. Shut down the entire cluster, then run this updater again." }
} catch {
    if ($_.Exception.Message -like "Cluster Manager is still running*") { throw }
}

if (!(Test-Path $Config)) {
    if (!(Test-Path $DefaultConfig)) { throw "Missing both cluster.properties and cluster.properties.default." }
    New-Item -ItemType Directory -Force -Path (Split-Path $Config -Parent) | Out-Null
    Copy-Item $DefaultConfig $Config -Force
    Write-Host "  + created config\cluster.properties from 0.0.6a defaults"
} else {
    $backup = "$Config.pre-6a.bak"
    if (!(Test-Path $backup)) { Copy-Item $Config $backup -Force; Write-Host "  + backed up config to cluster.properties.pre-6a.bak" }
}

# Upgrade-safe settings. Existing choices are never overwritten.
Ensure-ConfigLine "worker.evacuationGraceSeconds" "10"
Ensure-ConfigLine "handoff.maxCommandsPerHeartbeat" "128"
Ensure-ConfigLine "playerProfile.enabled" "true"
Ensure-ConfigLine "playerProfile.sharedFiles" "playerdata/{uuid}.dat;advancements/{uuid}.json;stats/{uuid}.json"
Ensure-ConfigLine "scaling.mode" "manual"
Ensure-ConfigLine "scaling.checkSeconds" "5"
Ensure-ConfigLine "scaling.minWorkers" "1"
Ensure-ConfigLine "scaling.maxWorkers" "64"
Ensure-ConfigLine "scaling.warmWorkers" "0"
Ensure-ConfigLine "scaling.targetUtilization" "0.75"
Ensure-ConfigLine "scaling.highPlayersPerWorker" "70"
Ensure-ConfigLine "scaling.lowPlayersPerWorker" "15"
Ensure-ConfigLine "scaling.highMspt" "45.0"
Ensure-ConfigLine "scaling.highCpuPercent" "85"
Ensure-ConfigLine "scaling.highHeapPercent" "85"
Ensure-ConfigLine "scaling.scaleUpSustainSeconds" "30"
Ensure-ConfigLine "scaling.scaleDownSustainSeconds" "300"
Ensure-ConfigLine "scaling.scaleUpCooldownSeconds" "120"
Ensure-ConfigLine "scaling.scaleDownCooldownSeconds" "120"
Ensure-ConfigLine "scaling.scaleDownMigratePlayers" "true"
Ensure-ConfigLine "scaling.maxConcurrentDrains" "4"
Ensure-ConfigLine "scaling.memory.enforceHardLimit" "true"
Ensure-ConfigLine "scaling.memory.totalBudgetGb" "0"
Ensure-ConfigLine "scaling.memory.systemReserveGb" "16"
Ensure-ConfigLine "scaling.memory.workerReservationGb" "0"
Ensure-ConfigLine "scaling.memory.jvmOverheadPercent" "20"
Ensure-ConfigLine "world.seed" "AUTO"
Ensure-ConfigLine "world.lockSeed" "true"
# Existing clusters may predate the locked-seed model. Start permissive, inspect MATCH, then enable.
Ensure-ConfigLine "world.identity.enforce" "false"
Ensure-ConfigLine "world.identity.configKeywords" "world,worldgen,terrain,biome,generation,climate,tfc"

# Install exactly one 0.0.6a Node JAR in the template and every existing worker.
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

# Preserve/create runtime directories. Never delete persistence or player profiles.
New-Item -ItemType Directory -Force -Path (Join-Path $Root "runtime") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Root "runtime\player-profiles") | Out-Null

Write-Host ""
Write-Host "[Foundations Cluster] $Expected applied." -ForegroundColor Green
Write-Host "Start with scaling.mode=manual, verify Seed + World Identity MATCH, then try assisted mode." -ForegroundColor Yellow
Write-Host "On an upgraded cluster, world.identity.enforce defaults to false until you verify all expected workers." -ForegroundColor Yellow
Write-Host "The memory guardrail is active immediately and also applies to manual worker creation." -ForegroundColor Green
Write-Host "Existing worker worlds and R7.R4/R7.R5/R7.R6 persistence/profile stores were preserved." -ForegroundColor Green
Write-Host "Run 6A_INTEGRATION_CHECKLIST.md before enabling automatic scaling in production." -ForegroundColor Yellow
