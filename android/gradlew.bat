@echo off
setlocal
set "GRADLE_VERSION=8.9"
if not "%GRADLE_USER_HOME%"=="" (set "GH=%GRADLE_USER_HOME%") else (set "GH=%USERPROFILE%\.gradle")
set "GRADLE_DIR=%GH%\wrapper\dists\gradle-%GRADLE_VERSION%-bin\bootstrap\gradle-%GRADLE_VERSION%"
set "ZIP=%GH%\wrapper\dists\gradle-%GRADLE_VERSION%-bin\bootstrap\gradle-%GRADLE_VERSION%-bin.zip"
set "URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"

where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  gradle %*
  exit /b %ERRORLEVEL%
)

if exist "%GRADLE_DIR%\bin\gradle.bat" goto RUN

if not exist "%GH%\wrapper\dists\gradle-%GRADLE_VERSION%-bin\bootstrap" mkdir "%GH%\wrapper\dists\gradle-%GRADLE_VERSION%-bin\bootstrap"
if not exist "%ZIP%" (
  echo Downloading Gradle %GRADLE_VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri '%URL%' -OutFile '%ZIP%'"
  if errorlevel 1 exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP%' '%GH%\wrapper\dists\gradle-%GRADLE_VERSION%-bin\bootstrap'"
if errorlevel 1 exit /b 1

:RUN
call "%GRADLE_DIR%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
