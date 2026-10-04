@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Compartir.ps1"
if errorlevel 1 pause
