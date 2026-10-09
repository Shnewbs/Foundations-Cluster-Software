@echo off
setlocal EnableExtensions
cd /d "%~dp0"
call "%~dp0..\tools\use_jdk21.bat"
if errorlevel 1 exit /b 2
set "ROOT=%~dp0.."
set "GRADLE_VERSION=8.14.3"
set "GRADLE_BAT=%ROOT%\runtime\tools\gradle-%GRADLE_VERSION%\bin\gradle.bat"

if not exist "%GRADLE_BAT%" (
  for /f "usebackq delims=" %%G in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\tools\ensure_gradle.ps1" -Version "%GRADLE_VERSION%"`) do set "GRADLE_BAT=%%G"
)
if not exist "%GRADLE_BAT%" (
  echo [Foundations Cluster] Gradle bootstrap failed.
  exit /b 1
)

echo [Foundations Cluster] Building Foundations Cluster Node 0.0.6a.R14...
call "%GRADLE_BAT%" --no-daemon clean build
if errorlevel 1 exit /b 1

set "NODE_JAR=%~dp0build\libs\foundations_cluster_node-0.0.6a.R14.jar"
if not exist "%NODE_JAR%" (
  echo [Foundations Cluster] ERROR: Gradle returned success but Node R14 JAR was not produced:
  echo   %NODE_JAR%
  echo [Foundations Cluster] Contents of build\libs:
  dir /b "%~dp0build\libs" 2^>nul
  exit /b 3
)

echo [Foundations Cluster] Verified Node R14 artifact: %NODE_JAR%

if /I "%~1"=="--install" (
  if not exist "%ROOT%\server-template\mods" mkdir "%ROOT%\server-template\mods"
  del /q "%ROOT%\server-template\mods\foundations_cluster_node-*.jar" >nul 2>&1
  copy /Y "%~dp0build\libs\foundations_cluster_node-0.0.6a.R14.jar" "%ROOT%\server-template\mods\foundations_cluster_node-0.0.6a.R14.jar" >nul
  if errorlevel 1 exit /b 1
  echo [Foundations Cluster] Cluster Node installed into server-template\mods.
)
exit /b 0
