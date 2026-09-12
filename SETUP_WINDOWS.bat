@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\check-java.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
if defined APPDATA if not exist "%APPDATA%\KSPAni\clean-v1\source-plugins" mkdir "%APPDATA%\KSPAni\clean-v1\source-plugins" >nul 2>nul
call gradlew.bat classes
exit /b %ERRORLEVEL%
