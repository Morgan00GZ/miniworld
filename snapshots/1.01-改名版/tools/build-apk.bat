@echo off
REM ============================================================
REM  OneStep Workbench - build APK (double-click me)
REM  All Chinese messages come from setup-build-env.ps1
REM ============================================================
setlocal
chcp 65001 >nul 2>nul
set "PS1=%~dp0setup-build-env.ps1"

if not exist "%PS1%" (
  echo [FAIL] setup-build-env.ps1 is not next to this file:
  echo        %PS1%
  echo        Keep both files together in the tools folder.
  echo.
  pause
  exit /b 2
)

REM --- make sure the .ps1 is UTF-8 WITH BOM ---------------------
REM Windows PowerShell 5.1 reads a BOM-less UTF-8 file as GBK: every Chinese
REM character turns to garbage and the script dies with bogus syntax errors.
REM Editors (and AI edits) silently drop the BOM, so we re-add it here.
powershell -NoProfile -Command "$p='%PS1%'; $b=[IO.File]::ReadAllBytes($p); if (-not ($b.Length -ge 3 -and $b[0] -eq 239 -and $b[1] -eq 187 -and $b[2] -eq 191)) { [IO.File]::WriteAllBytes($p, [byte[]](239,187,191) + $b) }"

REM --- take out the trash from earlier debugging sessions ---
if exist "%~dp0_argtest.ps1" del /q "%~dp0_argtest.ps1" >nul 2>nul
if exist "%~dp0_argtest" rmdir /s /q "%~dp0_argtest" >nul 2>nul

echo.
echo Building OneStep Workbench APK ...
echo (first run downloads JDK + Android SDK + Gradle, about 500MB)
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
set RC=%ERRORLEVEL%
echo.
if "%RC%"=="0" (
  echo DONE. APK is in: app\build\outputs\apk\debug\app-debug.apk
) else (
  echo FAILED with code %RC%.
  echo.
  echo Full log: %~dp0build-log.txt
  if exist "%~dp0build-log.txt" start "" notepad "%~dp0build-log.txt"
)
echo.
pause
endlocal
