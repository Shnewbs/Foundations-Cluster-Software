@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "NEOFORGE_VERSION=21.1.250"
set "TEMPLATE=%CD%\server-template"
set "INSTALLER=neoforge-%NEOFORGE_VERSION%-installer.jar"
set "URL=https://maven.neoforged.net/releases/net/neoforged/neoforge/%NEOFORGE_VERSION%/%INSTALLER%"

where java >nul 2>nul || (
  echo [Foundations Cluster] Java 21 was not found on PATH.
  exit /b 1
)

if not exist "%TEMPLATE%" mkdir "%TEMPLATE%"
if not exist "%TEMPLATE%\.bootstrap" mkdir "%TEMPLATE%\.bootstrap"

if exist "%TEMPLATE%\libraries\net\neoforged\neoforge\%NEOFORGE_VERSION%\win_args.txt" (
  echo [Foundations Cluster] NeoForge %NEOFORGE_VERSION% is already installed in server-template.
  goto :eula
)

if not exist "%TEMPLATE%\.bootstrap\%INSTALLER%" (
  echo [Foundations Cluster] Downloading NeoForge %NEOFORGE_VERSION% installer...
  curl.exe -fL "%URL%" -o "%TEMPLATE%\.bootstrap\%INSTALLER%"
  if errorlevel 1 (
    echo [Foundations Cluster] Download failed.
    exit /b 1
  )
)

echo [Foundations Cluster] Installing NeoForge server template...
pushd "%TEMPLATE%"
java -jar ".bootstrap\%INSTALLER%" --installServer
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" (
  echo [Foundations Cluster] NeoForge installer exited with code %RC%.
  exit /b %RC%
)

if not exist "%TEMPLATE%\libraries\net\neoforged\neoforge\%NEOFORGE_VERSION%\win_args.txt" (
  echo [Foundations Cluster] Install finished but win_args.txt was not found.
  exit /b 1
)

:eula
if not exist "%TEMPLATE%\eula.txt" (
  >"%TEMPLATE%\eula.txt" echo # Review https://aka.ms/MinecraftEULA before changing this value.
  >>"%TEMPLATE%\eula.txt" echo eula=false
)
if not exist "%TEMPLATE%\mods" mkdir "%TEMPLATE%\mods"

echo.
echo [Foundations Cluster] NeoForge template is installed.
echo [Foundations Cluster] Review https://aka.ms/MinecraftEULA and set server-template\eula.txt to eula=true,
echo [Foundations Cluster] or use the dashboard Accept EULA button.
exit /b 0
