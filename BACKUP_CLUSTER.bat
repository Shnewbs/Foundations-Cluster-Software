@echo off
setlocal
where py >nul 2>&1
if errorlevel 1 (
  python "%~dp0tools\cluster_backup.py" backup
) else (
  py -3 "%~dp0tools\cluster_backup.py" backup
)
set "RESULT=%errorlevel%"
pause
exit /b %RESULT%
