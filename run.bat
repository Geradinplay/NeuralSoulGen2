@echo off
cd /d "%~dp0"
where mvn >nul 2>nul
if errorlevel 1 (
  echo Maven was not found in PATH. Open pom.xml in IntelliJ IDEA and run javafx:run.
  pause
  exit /b 1
)
call mvn javafx:run
if errorlevel 1 pause
