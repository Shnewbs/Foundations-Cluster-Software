@echo off
set "FOUNDATIONS_DISCOVERED_JDK21="
for /f "usebackq delims=" %%J in (`powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0ensure_jdk21.ps1"`) do set "FOUNDATIONS_DISCOVERED_JDK21=%%J"
if not defined FOUNDATIONS_DISCOVERED_JDK21 exit /b 2
set "FOUNDATIONS_JDK21=%FOUNDATIONS_DISCOVERED_JDK21%"
set "FOUNDATIONS_DISCOVERED_JDK21="
if not exist "%FOUNDATIONS_JDK21%\bin\jar.exe" exit /b 2
set "JAVA_HOME=%FOUNDATIONS_JDK21%"
set "PATH=%FOUNDATIONS_JDK21%\bin;%PATH%"
exit /b 0
