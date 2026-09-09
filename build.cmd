@echo off
cd /d "%~dp0"
if exist ".tools\apache-maven-3.9.11\bin\mvn.cmd" (
  call ".tools\apache-maven-3.9.11\bin\mvn.cmd" -B -Dmaven.repo.local=.tools/repository verify
) else (
  call mvn -B verify
)
exit /b %errorlevel%
