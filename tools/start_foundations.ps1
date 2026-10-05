$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root
$Jar = Join-Path $Root "cluster-manager\build\FoundationsClusterManager.jar"
$Bc = Join-Path $Root "cluster-manager\lib\bcprov.jar"
$ConfigDir = Join-Path $Root "config"
$ConfigFile = Join-Path $ConfigDir "cluster.properties"
$DefaultConfig = Join-Path $ConfigDir "cluster.properties.default"
$ExpectedVersion = "0.0.6a.R19"
if (Test-Path -LiteralPath (Join-Path $Root 'runtime/update-in-progress.json')) {
    throw 'An update was interrupted. Use the updater rollback command and the backup recorded in runtime/update-in-progress.json before starting.'
}

function Get-JavaVersionText([string]$JavaPath) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $JavaPath; $psi.Arguments = "-version"; $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
    $p = New-Object System.Diagnostics.Process; $p.StartInfo = $psi
    if (-not $p.Start()) { throw "Could not start Java for version detection." }
    $stdout = $p.StandardOutput.ReadToEnd(); $stderr = $p.StandardError.ReadToEnd(); $p.WaitForExit()
    if ($p.ExitCode -ne 0) { throw "Java version check exited with code $($p.ExitCode)." }
    return (($stdout + [Environment]::NewLine + $stderr).Trim())
}

function Get-ConfigValue([string]$Key, [string]$Default) {
    if (!(Test-Path $ConfigFile)) { return $Default }
    $escaped = [regex]::Escape($Key)
    foreach ($line in Get-Content $ConfigFile) {
        if ($line -match "^\s*$escaped\s*=\s*(.*?)\s*$") { return $Matches[1] }
    }
    return $Default
}

function Show-StartupDiagnostics {
    param([string]$Reason)
    Write-Host "[Foundations Cluster] $Reason" -ForegroundColor Red
    foreach ($path in @(
        (Join-Path $Root "logs\cluster-manager-startup-error.log"),
        (Join-Path $Root "logs\manager-stderr.log"),
        (Join-Path $Root "logs\cluster-manager.log")
    )) {
        if (Test-Path $path) {
            Write-Host "`n--- $([IO.Path]::GetFileName($path)) ---" -ForegroundColor Yellow
            Get-Content $path -Tail 30 | ForEach-Object { Write-Host $_ }
        }
    }
}

foreach ($dir in @("runtime", "logs", "workers", "server-template")) { New-Item -ItemType Directory -Force -Path (Join-Path $Root $dir) | Out-Null }
New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
if (!(Test-Path $ConfigFile)) {
    if (Test-Path $DefaultConfig) { Copy-Item $DefaultConfig $ConfigFile; Write-Host "[Foundations Cluster] Created config\cluster.properties from 0.0.6a defaults." -ForegroundColor Yellow }
    else { Write-Host "[Foundations Cluster] Missing configuration defaults." -ForegroundColor Red; Read-Host "Press Enter to close"; exit 1 }
}

$webPort = [int](Get-ConfigValue "web.port" "8765")
$Health = "http://127.0.0.1:$webPort/api/health"
$Dashboard = "http://127.0.0.1:$webPort/"
function Get-ManagerHealth {
    try { return Invoke-RestMethod -UseBasicParsing -Uri $Health -TimeoutSec 2 } catch { return $null }
}

$jdk21=& (Join-Path $PSScriptRoot 'ensure_jdk21.ps1')
if(!$jdk21 -or $LASTEXITCODE -ne 0){throw 'Java 21 JDK discovery failed'}
$env:JAVA_HOME=$jdk21
$env:PATH=(Join-Path $jdk21 'bin')+';'+$env:PATH
$javaCmd=[pscustomobject]@{Source=(Join-Path $jdk21 'bin\java.exe')}
Write-Host "[Foundations Cluster] Java 21: $($javaCmd.Source)"

if (!(Test-Path $Bc)) { Write-Host "[Foundations Cluster] Missing cluster-manager\lib\bcprov.jar required for Argon2id security." -ForegroundColor Red; Read-Host "Press Enter to close"; exit 1 }
if (!(Test-Path $Jar)) { Write-Host "[Foundations Cluster] Building 0.0.6a.R19 control plane..."; & (Join-Path $Root "build_all.bat"); if ($LASTEXITCODE -ne 0) { throw "Control-plane build failed." } }

$existing = Get-ManagerHealth
if ($existing -and $existing.version -ne $ExpectedVersion) {
    Write-Host "[Foundations Cluster] An older manager ($($existing.version)) is already running on port $webPort." -ForegroundColor Yellow
    Write-Host "Use its dashboard Shut Down button, then launch 0.0.6a.R19 again." -ForegroundColor Yellow
    Start-Process $Dashboard | Out-Null
    Read-Host "Press Enter to close"
    exit 1
}

if (-not $existing) {
    $cp = "$Jar;$Bc"
    $stdoutLog = Join-Path $Root "logs\manager-stdout.log"
    $stderrLog = Join-Path $Root "logs\manager-stderr.log"
    foreach ($f in @($stdoutLog,$stderrLog)) { if(Test-Path $f){ Remove-Item $f -Force -ErrorAction SilentlyContinue } }
    Write-Host "[Foundations Cluster] Starting Cluster Manager $ExpectedVersion..."
    Write-Host "[Foundations Cluster] Startup diagnostics are being captured in logs\." -ForegroundColor DarkGray
    $proc = Start-Process -FilePath $javaCmd.Source -ArgumentList @("-cp", "`"$cp`"", "com.foundations.cluster.manager.Main") -WorkingDirectory $Root -PassThru -WindowStyle Hidden -RedirectStandardOutput $stdoutLog -RedirectStandardError $stderrLog
    $ready=$false; $lastStatus=""
    for($i=0;$i -lt 120;$i++){
        Start-Sleep -Milliseconds 500
        if ($proc.HasExited) {
            try { $proc.WaitForExit(); $proc.Refresh(); $exitCode = $proc.ExitCode } catch { $exitCode = "unknown" }
            Show-StartupDiagnostics "Cluster Manager exited during startup with code $exitCode."
            Read-Host "Press Enter to close"; exit 1
        }
        $h=Get-ManagerHealth
        if($h -and $h.version -eq $ExpectedVersion){$ready=$true;break}
        if(($i % 10) -eq 9){
            $managerLog=Join-Path $Root "logs\cluster-manager.log"
            if(Test-Path $managerLog){
                $status=(Get-Content $managerLog -Tail 1 -ErrorAction SilentlyContinue)
                if($status -and $status -ne $lastStatus){Write-Host "[Foundations Cluster] $status" -ForegroundColor DarkGray;$lastStatus=$status}
            } else {
                Write-Host "[Foundations Cluster] Waiting for security vault / manager initialization..." -ForegroundColor DarkGray
            }
        }
    }
    if(-not $ready){Show-StartupDiagnostics "Manager did not become ready within 60 seconds.";Read-Host "Press Enter to close";exit 1}
} else { Write-Host "[Foundations Cluster] 0.0.6a.R19 Cluster Manager is already running." }

Write-Host "[Foundations Cluster] Cluster Manager READY on port $webPort." -ForegroundColor Green
Write-Host "[Foundations Cluster] Opening secure all-in-one dashboard..."
Start-Process $Dashboard | Out-Null
Write-Host "[Foundations Cluster] First run will ask you to create the admin login. This window may be closed."
