@echo off
setlocal EnableExtensions
cd /d "%~dp0"
if not exist logs mkdir logs
set "LOG=%~dp0logs\build-all.log"
>"%LOG%" echo [Foundations Cluster] Full build started %date% %time%

echo [Foundations Cluster] Building Manager 0.0.6a.R19...
call "%~dp0cluster-manager\build_manager.bat" >>"%LOG%" 2>&1
if errorlevel 1 goto :fail

echo [Foundations Cluster] Building node simulator...
call "%~dp0node-simulator\build_simulator.bat" >>"%LOG%" 2>&1
if errorlevel 1 goto :fail

echo [Foundations Cluster] Building Cluster Node 0.0.6a.R11...
call "%~dp0minecraft-agent\build_mod.bat" %1 >>"%LOG%" 2>&1
if errorlevel 1 goto :fail

echo [Foundations Cluster] Building Gateway 0.0.6a.R19...
call "%~dp0velocity-gateway\build_plugin.bat" %1 >>"%LOG%" 2>&1
if errorlevel 1 goto :fail

echo [Foundations Cluster] Full build complete.
echo [Foundations Cluster] Details: logs\build-all.log
exit /b 0

:fail
echo [Foundations Cluster] BUILD FAILED. See logs\build-all.log
echo [Foundations Cluster] Last 30 lines:
powershell -NoProfile -Command "Get-Content -LiteralPath $env:LOG -Tail 30"
exit /b 1
