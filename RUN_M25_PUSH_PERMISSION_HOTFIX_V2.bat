@echo off
setlocal
cd /d "%~dp0"
echo Applying M25 push-permission hotfix v2...
where python >nul 2>nul
if errorlevel 1 (
  echo ERROR: Python was not found in PATH.
  pause
  exit /b 1
)
python tools\apply_m25_push_permission_hotfix_v2.py .
if errorlevel 1 (
  echo.
  echo Hotfix failed. Nothing should be committed until the error above is fixed.
  pause
  exit /b 1
)
echo.
echo HOTFIX READY.
echo Commit the changed files to MAIN, then rerun:
echo   M25 Final RC Burn-In Controlled LIVE
pause
