@echo off
setlocal
cd /d "%~dp0"
if not exist "desktop\START_DESKTOP.bat" (
  echo Foundations Cluster Desktop is not installed.
  pause
  exit /b 1
)
call "desktop\START_DESKTOP.bat"
