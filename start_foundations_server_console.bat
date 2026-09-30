@echo off
setlocal
cd /d "%~dp0"
if not exist "cluster-manager\build\FoundationsClusterManager.jar" call build_all.bat
java -cp "cluster-manager\build\FoundationsClusterManager.jar;cluster-manager\lib\bcprov.jar" com.foundations.cluster.manager.Main
pause
