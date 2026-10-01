@echo off
setlocal EnableExtensions
cd /d "%~dp0"
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\tools\build_simulator.ps1"
exit /b %errorlevel%
