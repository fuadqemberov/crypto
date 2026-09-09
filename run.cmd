@echo off
cd /d "%~dp0"
if not exist "target\futures-lab-1.0.0.jar" (
  call build.cmd
  if errorlevel 1 exit /b 1
)
java -jar target\futures-lab-1.0.0.jar %*
pause
