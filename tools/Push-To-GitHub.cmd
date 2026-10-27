@echo off
rem ============================================================
rem  Push-To-GitHub - commit all local changes in THIS repo and
rem  push them to GitHub in one double-click.
rem
rem  Layout (one copy per project):
rem      <workspace>\<project>\tools\Push-To-GitHub.cmd
rem  Repo root is assumed to be one level up (..), and portable
rem  git in ..\..\PortableGit. If that portable git is missing it
rem  falls back to git on PATH.
rem
rem  Usage:
rem    double-click this file                  (default message)
rem    Push-To-GitHub.cmd "your message"      (custom message)
rem
rem  All ASCII on purpose: no codepage surprises.
rem ============================================================
setlocal
set "HERE=%~dp0"
for %%N in ("%HERE%..") do set "REPO=%%~fN"
set "MSG=%~1"
if "%MSG%"=="" set "MSG=chore: sync local changes"

rem ---- locate git (portable first, then PATH) ---------------
set "GIT="
for %%P in ("%HERE%..\..\PortableGit" "%HERE%..\PortableGit" "%HERE%PortableGit") do (
  if not defined GIT if exist "%%~P\cmd\git.exe" set "GIT=%%~P\cmd\git.exe"
)
if not defined GIT set "GIT=git"
if defined GIT for %%N in ("%GIT%") do set "GIT=%%~fN"

if not exist "%REPO%\.git" goto :norepo
if not defined GIT goto :nogit
if "%GIT%"=="git" goto :usingpath
if not exist "%GIT%" goto :nogit

:usingpath
set "PATH=%HERE%..\..\PortableGit\cmd;%PATH%"

for %%N in ("%REPO%") do set "NAME=%%~nxN"

cd /d "%REPO%"

echo.
echo   ==========================================================
echo    Commit and push  :  %NAME%
echo   ==========================================================
echo.
echo   [1/3] What changed since the last commit:
echo.
"%GIT%" status --short
if errorlevel 1 goto :fail
echo.

echo   [2/3] Staging and committing...
echo.
"%GIT%" add -A
"%GIT%" commit -m "%MSG%"
if errorlevel 1 echo   [i] Nothing new to commit - continuing to push.
echo.

echo   [3/3] Pushing to GitHub (branch + tags)...
echo.
"%GIT%" push origin main
"%GIT%" push origin --tags
if errorlevel 1 goto :pushfail

echo.
echo   [OK] Done. Your changes are on GitHub.
echo.
"%GIT%" log --oneline -3
echo.
goto :end

:nogit
echo.
echo   [X] No usable git found (looked for PortableGit, then git on PATH).
echo       Set PATH so that git.exe is reachable, or restore the
echo       PortableGit folder next to the workspace root.
echo.
goto :end

:norepo
echo.
echo   [X] Git repository not found at:
echo       %REPO%
echo   This file must sit in the tools\ folder of a git repo.
echo.
goto :end

:pushfail
echo.
echo   [!] Push failed. The commit itself is safe on your disk.
echo       Common causes and fixes:
echo         - no network / proxy  : check your connection
echo         - login expired       : open the git shell at the
echo                                 workspace root and type:
echo                                   git push origin main
echo                                 then sign in again in the browser
echo         - password prompt     : GitHub needs a Personal Access
echo                                 Token, not your account password
echo.

:fail
:end
echo.
pause
endlocal
