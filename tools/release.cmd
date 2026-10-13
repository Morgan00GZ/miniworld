@echo off
rem Wrapper so double-click / cmd works even with a Restricted execution policy.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0release.ps1" %*
