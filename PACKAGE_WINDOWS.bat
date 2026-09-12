@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\check-java.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
call gradlew.bat clean installDist
if errorlevel 1 exit /b %ERRORLEVEL%
where jpackage >nul 2>nul || (echo jpackage was not found. Use a JDK 21 distribution that includes jpackage.& exit /b 1)
if exist dist\KSPAni rmdir /s /q dist\KSPAni
mkdir dist 2>nul
jpackage --type app-image --name KSPAni --app-version 1.5.12 --vendor "KSP Ani" --description "KSP Ani desktop AniList client" --input "build\install\KSPAni\lib" --main-jar "KSPAni-1.5.12.jar" --main-class app.kspani.Main --dest dist
if errorlevel 1 exit /b %ERRORLEVEL%
powershell -NoProfile -Command "Compress-Archive -Path 'dist\KSPAni\*' -DestinationPath 'dist\KSPAni-portable.zip' -Force"
echo Created dist\KSPAni and dist\KSPAni-portable.zip
