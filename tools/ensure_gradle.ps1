param(
  [string]$Version = "8.14.3",
  [switch]$Quiet
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Tools = Join-Path $Root "runtime\tools"
$GradleHome = Join-Path $Tools ("gradle-" + $Version)
$GradleBat = Join-Path $GradleHome "bin\gradle.bat"

function Say([string]$Message) {
  if (-not $Quiet) { Write-Host $Message }
}

if (Test-Path $GradleBat) {
  Write-Output $GradleBat
  exit 0
}

New-Item -ItemType Directory -Force -Path $Tools | Out-Null

# Multiple first-boot jobs can need Gradle (Cluster Node + Velocity adapter).
# Serialize the distribution bootstrap so they never download/extract the same
# file at the same time. Local\ scopes this to the current Windows session.
$SafeVersion = ($Version -replace '[^A-Za-z0-9_-]', '_')
$MutexName = "Local\FoundationsCluster-GradleBootstrap-$SafeVersion"
$Mutex = New-Object System.Threading.Mutex($false, $MutexName)
$LockTaken = $false
try {
  try {
    $LockTaken = $Mutex.WaitOne([TimeSpan]::FromMinutes(10))
  } catch [System.Threading.AbandonedMutexException] {
    # Previous bootstrap process died while holding the mutex. Windows grants
    # ownership to us in this case; continue after rechecking the installation.
    $LockTaken = $true
  }
  if (-not $LockTaken) { throw "Timed out waiting for the shared Gradle bootstrap lock ($MutexName)." }

  # Another process may have completed while we waited.
  if (Test-Path $GradleBat) {
    Write-Output $GradleBat
    exit 0
  }

  $Uri = "https://services.gradle.org/distributions/gradle-$Version-bin.zip"
  $Id = "$PID-$([Guid]::NewGuid().ToString('N'))"
  $TempZip = Join-Path $Tools (".gradle-$Version-$Id.zip")
  $Stage = Join-Path $Tools (".gradle-$Version-stage-$Id")

  try {
    Say "[Foundations Cluster] Downloading Gradle $Version (single-owner bootstrap)..."
    Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $TempZip -TimeoutSec 300

    $zipInfo = Get-Item $TempZip -ErrorAction Stop
    if ($zipInfo.Length -lt 1048576) {
      throw "Downloaded Gradle archive was unexpectedly small ($($zipInfo.Length) bytes)."
    }

    Say "[Foundations Cluster] Extracting Gradle $Version to staging..."
    New-Item -ItemType Directory -Force -Path $Stage | Out-Null
    Expand-Archive -Path $TempZip -DestinationPath $Stage -Force

    $StagedHome = Join-Path $Stage ("gradle-" + $Version)
    $StagedBat = Join-Path $StagedHome "bin\gradle.bat"
    if (!(Test-Path $StagedBat)) {
      throw "Gradle archive extracted but staged gradle.bat was not found: $StagedBat"
    }

    # Never expose a half-extracted live Gradle directory. Replace only after
    # the staged install is complete and verified.
    if (Test-Path $GradleHome) {
      Remove-Item -Recurse -Force $GradleHome -ErrorAction Stop
    }
    Move-Item -Path $StagedHome -Destination $GradleHome -Force

    if (!(Test-Path $GradleBat)) {
      throw "Gradle bootstrap completed but gradle.bat was not found: $GradleBat"
    }

    Say "[Foundations Cluster] Gradle $Version ready."
    Write-Output $GradleBat
  } finally {
    Remove-Item -Force $TempZip -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $Stage -ErrorAction SilentlyContinue
    # Clean stale R2 shared archive if it exists and is no longer locked. R3
    # does not use this path, so failure to remove it is harmless.
    Remove-Item -Force (Join-Path $Tools ("gradle-" + $Version + "-bin.zip")) -ErrorAction SilentlyContinue
  }
} finally {
  if ($LockTaken) {
    try { $Mutex.ReleaseMutex() } catch {}
  }
  $Mutex.Dispose()
}
