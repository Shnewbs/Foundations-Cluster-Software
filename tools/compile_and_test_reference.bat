@echo off
setlocal EnableExtensions
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0build_reference.ps1"
exit /b %errorlevel%
