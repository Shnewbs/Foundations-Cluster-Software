@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\stop_foundations.ps1"
timeout /t 2 /nobreak >nul
