@echo off
setlocal
cd /d "%~dp0"
call "%~dp0..\tools\use_jdk21.bat"
if errorlevel 1 exit /b 2
if exist build\classes rmdir /s /q build\classes
mkdir build\classes 2>nul
if not exist build mkdir build
if exist build\sources.txt del build\sources.txt
for /r src\main\java %%f in (*.java) do echo "%%f">>build\sources.txt
javac --release 21 -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1
jar --create --file build\FoundationsClusterNodeSimulator.jar --main-class com.foundations.cluster.simulator.Main -C build\classes .
del build\sources.txt
echo [Foundations Cluster] Built node simulator.
