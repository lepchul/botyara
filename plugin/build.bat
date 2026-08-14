@echo off
cd /d "%~dp0"
title Build TgAuth

where java >nul 2>&1
if errorlevel 1 (
  echo [X] Java not found.
  echo     Install JDK 21: https://adoptium.net
  echo     Pick "Temurin 21 LTS" - .msi for Windows.
  echo     IMPORTANT: during setup enable "Set JAVA_HOME variable".
  echo.
  pause
  exit /b 1
)
echo [OK] Java found:
java -version
echo.

where mvn >nul 2>&1
if errorlevel 1 (
  echo [X] Maven not found.
  echo     1. Download "Binary zip archive" from https://maven.apache.org/download.cgi
  echo     2. Unpack to C:\maven
  echo     3. Add C:\maven\bin to PATH ^(Win+R -^> sysdm.cpl -^> Advanced -^> Environment Variables^)
  echo     4. Open a NEW cmd window and run this file again
  echo.
  pause
  exit /b 1
)
echo [OK] Maven found.
echo.

echo Building, first run downloads dependencies and takes a few minutes...
echo.
call mvn clean package
if errorlevel 1 (
  echo.
  echo [X] Build failed. See the error above.
  pause
  exit /b 1
)

echo.
echo [DONE] File ready: %~dp0target\TgAuth.jar
echo Upload it to the plugins folder of your server.
explorer "%~dp0target"
pause
