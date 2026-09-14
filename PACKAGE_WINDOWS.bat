@echo off
setlocal
cd /d "%~dp0"
if exist "%LOCALAPPDATA%\AOKUVUE\jdk-21\bin\java.exe" (
  set "JAVA_HOME=%LOCALAPPDATA%\AOKUVUE\jdk-21"
  set "PATH=%LOCALAPPDATA%\AOKUVUE\jdk-21\bin;%PATH%"
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\check-java.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
for /f "usebackq delims=" %%V in (`call gradlew.bat -q printVersion --no-daemon`) do set "APP_VERSION=%%V"
if not defined APP_VERSION (echo Unable to determine Aokuvue version.& exit /b 1)
call gradlew.bat clean test installDist --no-daemon
if errorlevel 1 exit /b %ERRORLEVEL%
where jpackage >nul 2>nul || (echo jpackage was not found. Use a JDK 21 distribution that includes jpackage.& exit /b 1)
if exist dist\Aokuvue rmdir /s /q dist\Aokuvue
mkdir dist 2>nul
jpackage --type app-image --name Aokuvue --app-version %APP_VERSION% --vendor "Aokuvue" --description "Aokuvue anime and manga desktop application" --input "build\install\Aokuvue\lib" --main-jar "Aokuvue-%APP_VERSION%.jar" --main-class app.kspani.MainLauncher --java-options "-Dfile.encoding=UTF-8" --java-options "-Dprism.order=d3d,sw" --icon "src\main\resources\images\aokuvue.ico" --dest dist
if errorlevel 1 exit /b %ERRORLEVEL%
powershell -NoProfile -Command "Compress-Archive -Path 'dist\Aokuvue\*' -DestinationPath 'dist\Aokuvue-portable.zip' -Force"
echo Created dist\Aokuvue and dist\Aokuvue-portable.zip for version %APP_VERSION%
