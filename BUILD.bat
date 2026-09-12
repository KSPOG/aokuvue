@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\check-java.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
call gradlew.bat clean test installDist
exit /b %ERRORLEVEL%
