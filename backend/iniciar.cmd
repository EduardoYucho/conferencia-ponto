@echo off
rem Atalho para o cmd: repassa os parametros para o iniciar.ps1 (ex.: iniciar.cmd -Jar)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0iniciar.ps1" %*
exit /b %ERRORLEVEL%
