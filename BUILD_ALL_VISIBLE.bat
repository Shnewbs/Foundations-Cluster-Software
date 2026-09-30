@echo off
cd /d "%~dp0"
call build_all.bat
set "RC=%ERRORLEVEL%"
echo.
if "%RC%"=="0" (
  echo [Foundations Cluster] BUILD COMPLETE.
) else (
  echo [Foundations Cluster] BUILD FAILED with exit code %RC%.
  echo See logs\build-all.log for the full output.
)
echo.
pause
exit /b %RC%
