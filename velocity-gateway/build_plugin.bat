@echo off
setlocal EnableExtensions
cd /d "%~dp0"
call "%~dp0..\tools\use_jdk21.bat"
if errorlevel 1 exit /b 2
set "ROOT=%~dp0.."
set "GRADLE_VERSION=8.14.3"
set "GRADLE_BAT=%ROOT%\runtime\tools\gradle-%GRADLE_VERSION%\bin\gradle.bat"
if not exist "source\FoundationsVelocityGateway.java" (
  echo [Foundations Cluster] ERROR: Velocity gateway source is missing: velocity-gateway\source\FoundationsVelocityGateway.java
  exit /b 2
)
if not exist "resources\velocity-plugin.json" (
  echo [Foundations Cluster] ERROR: Velocity plugin metadata is missing: velocity-gateway\resources\velocity-plugin.json
  exit /b 2
)
if not exist "%GRADLE_BAT%" (
  for /f "usebackq delims=" %%G in (`powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\tools\ensure_gradle.ps1" -Version "%GRADLE_VERSION%"`) do set "GRADLE_BAT=%%G"
)
if not exist "%GRADLE_BAT%" (
  echo [Foundations Cluster] Gradle bootstrap failed.
  exit /b 1
)
call "%GRADLE_BAT%" --no-daemon clean jar
if errorlevel 1 exit /b 1
if /I not "%~1"=="--install" exit /b 0
if not exist "%ROOT%\velocity\plugins" mkdir "%ROOT%\velocity\plugins"
del /q "%ROOT%\velocity\plugins\foundations_velocity_gateway-*.jar" >nul 2>&1
for %%F in (build\libs\foundations_velocity_gateway-0.0.6a.R19.jar) do copy /Y "%%F" "%ROOT%\velocity\plugins\foundations_velocity_gateway-0.0.6a.R19.jar" >nul
exit /b 0
