@echo off
setlocal
set "APP_HOME=%~dp0"
set "GRADLE_VERSION=9.5.1"

where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  gradle %*
  exit /b %ERRORLEVEL%
)

set "BOOT=%APP_HOME%.gradle-bootstrap"
set "ZIP=%BOOT%\gradle-%GRADLE_VERSION%-bin.zip"
set "DIST=%BOOT%\gradle-%GRADLE_VERSION%\bin\gradle.bat"

if not exist "%DIST%" (
  if not exist "%BOOT%" mkdir "%BOOT%"
  echo [CKS bootstrap] Downloading Gradle %GRADLE_VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'; Expand-Archive -Force '%ZIP%' '%BOOT%'"
  if errorlevel 1 exit /b %ERRORLEVEL%
)

call "%DIST%" %*
exit /b %ERRORLEVEL%
