$ErrorActionPreference = "SilentlyContinue"
$Root = Split-Path -Parent $PSScriptRoot
$ConfigFile = Join-Path $Root "config\cluster.properties"

function Get-ConfigValue([string]$Key, [string]$Default) {
    if (!(Test-Path $ConfigFile)) { return $Default }
    $escaped=[regex]::Escape($Key)
    foreach($line in Get-Content $ConfigFile){ if($line -match "^\s*$escaped\s*=\s*(.*?)\s*$"){return $Matches[1]} }
    return $Default
}

$webPort=[int](Get-ConfigValue "web.port" "8765")
$Url="http://127.0.0.1:$webPort/"
$Health="$Url`api/health"
$up=$false
try { $h=Invoke-RestMethod -UseBasicParsing -Uri $Health -TimeoutSec 2; if($h.ok){$up=$true} } catch {}

if($up){
    Write-Host "[Foundations Cluster] Graceful shutdown is protected by your administrator session."
    Write-Host "Opening the dashboard. Use Settings > Danger Zone > Shut Down Cluster."
    Start-Process $Url | Out-Null
    exit 0
}

Write-Host "[Foundations Cluster] Manager API is not responding on port $webPort." -ForegroundColor Yellow
$targets = @(Get-CimInstance Win32_Process | Where-Object {
    ($_.Name -eq 'java.exe' -or $_.Name -eq 'javaw.exe') -and $_.CommandLine -match 'com\.foundations\.cluster\.manager\.Main'
})
if($targets.Count -eq 0){
    Write-Host "[Foundations Cluster] No Foundations Cluster Manager Java process was found."
    exit 0
}
Write-Host "[Foundations Cluster] Found unresponsive Cluster Manager process(es):" -ForegroundColor Yellow
$targets | ForEach-Object { Write-Host "  PID $($_.ProcessId)  $($_.Name)" }
$answer=Read-Host "Terminate only these Foundations Cluster Manager process(es)? [y/N]"
if($answer -match '^(y|yes)$'){
    foreach($t in $targets){ Stop-Process -Id $t.ProcessId -Force -ErrorAction SilentlyContinue }
    Write-Host "[Foundations Cluster] Unresponsive manager process(es) terminated." -ForegroundColor Green
} else {
    Write-Host "[Foundations Cluster] No processes were terminated."
}
