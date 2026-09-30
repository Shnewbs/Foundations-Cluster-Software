$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$Expected = "0.0.5a.R7.R5"
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

function Ensure-MinimumInteger([string]$Key, [long]$Minimum) {
    if (!(Test-Path $Config)) { return }
    $escaped = [regex]::Escape($Key)
    $lines = Get-Content $Config
    $found = $false
    $changed = $false
    $output = foreach ($line in $lines) {
        if ($line -match "^\s*$escaped\s*=\s*(\d+)\s*$") {
            $found = $true
            $current = [long]$Matches[1]
            if ($current -lt $Minimum) {
                $changed = $true
                "$Key=$Minimum"
            } else { $line }
        } else { $line }
    }
    if (-not $found) {
        $output += "$Key=$Minimum"
        $changed = $true
    }
    if ($changed) {
        [System.IO.File]::WriteAllLines($Config, [string[]]$output, $Utf8NoBom)
        Write-Host "  + config minimum: $Key=$Minimum"
    }
}

Write-Host "[Foundations Cluster] Applying $Expected generic BlockEntity persistence update..." -ForegroundColor Cyan

if (!(Test-Path $ManagerJar)) { throw "Missing R7.R5 manager JAR: $ManagerJar" }
if (!(Test-Path $NodeSource)) { throw "Missing R7.R5 Cluster Node JAR: $NodeSource" }

# Refuse to mutate a live installation. Updating worker mods while JVMs are running
# would make persistence/version validation ambiguous.
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
    $backup = "$Config.pre-r7r5.bak"
    if (!(Test-Path $backup)) { Copy-Item $Config $backup -Force }
    Ensure-ConfigLine "region.startupRecoverySeconds" "90"
    Ensure-ConfigLine "persistence.blockJournalEnabled" "true"
    Ensure-ConfigLine "persistence.blockEntityJournalEnabled" "true"
    Ensure-ConfigLine "persistence.blockEntityScanIntervalTicks" "10"
    Ensure-ConfigLine "persistence.blockEntityScanBudget" "512"
    Ensure-ConfigLine "persistence.blockEntityMaxPayloadBytes" "8388608"
    Ensure-ConfigLine "persistence.checkpointSeconds" "30"
    # R7.R4 installations commonly had a 4 MiB manager data-plane limit. R7.R5
    # allows an 8 MiB BlockEntity payload, so guarantee at least a 16 MiB frame cap.
    Ensure-MinimumInteger "dataplane.maxPayloadBytes" 16777216
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
Write-Host "Existing worker world folders and R7.R4 persistence journals were preserved." -ForegroundColor Green
Write-Host "R7.R5 adds layer-independent full BlockEntity NBT/component/attachment persistence." -ForegroundColor Green
Write-Host "Start normally with start_foundations_server.bat, then run the container restart test." -ForegroundColor Yellow
