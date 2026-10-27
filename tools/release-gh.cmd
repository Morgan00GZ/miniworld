@echo off
rem ============================================================
rem  Publish a GitHub Release for a tagged version (and upload
rem  that version's APK from snapshots\).
rem
rem  Usage:
rem    double-click this file            -> latest git tag
rem    release-gh.cmd 1.12               -> that version
rem    release-gh.cmd 1.12 -DryRun       -> print the plan, touch nothing
rem    release-gh.cmd 1.12 -Force        -> overwrite notes / re-upload APK
rem  Any extra switch is passed straight to release-gh.ps1.
rem ============================================================
setlocal
set "HERE=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%release-gh.ps1" %*
if errorlevel 1 (
  echo.
  echo   [X] Release failed - see the message above.
)
echo.
pause