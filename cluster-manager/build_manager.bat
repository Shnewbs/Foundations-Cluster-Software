@echo off
setlocal EnableExtensions DisableDelayedExpansion
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0..\tools\build_manager.ps1"
exit /b %errorlevel%
