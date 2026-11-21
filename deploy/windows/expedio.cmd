@echo off
rem Comando "expedio": repassa os parametros para o expedio.ps1 desta pasta.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0ponto.ps1" %*
exit /b %ERRORLEVEL%
