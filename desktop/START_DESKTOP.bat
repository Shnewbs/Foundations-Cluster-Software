@echo off
setlocal
where py >nul 2>&1
if errorlevel 1 (
  python "%~dp0foundations_desktop.py"
) else (
  py -3 "%~dp0foundations_desktop.py"
)
if errorlevel 1 pause
