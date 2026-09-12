@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\gradle-bootstrap.ps1" %*
exit /b %ERRORLEVEL%
