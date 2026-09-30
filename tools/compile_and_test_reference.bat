@echo off
setlocal
set ROOT=%~dp0..
set BUILD=%ROOT%\reference\build\classes
if exist "%ROOT%\reference\build" rmdir /s /q "%ROOT%\reference\build"
mkdir "%BUILD%"
set SOURCES=%TEMP%\foundations_r7r4_sources.txt
if exist "%SOURCES%" del "%SOURCES%"
for /r "%ROOT%\reference\src\main\java" %%F in (*.java) do echo %%F>>"%SOURCES%"
for /r "%ROOT%\reference\src\test\java" %%F in (*.java) do echo %%F>>"%SOURCES%"
javac --release 21 -d "%BUILD%" @"%SOURCES%"
if errorlevel 1 exit /b 1
java -cp "%BUILD%" com.foundations.cluster.transfer.RegionTransferCoordinatorSelfTest
if errorlevel 1 exit /b 1
java -cp "%BUILD%" com.foundations.cluster.persistence.PersistentRegionTransferSelfTest
if errorlevel 1 exit /b 1
echo R7.R4 all reference tests PASS
endlocal
