@echo off
rem Comando "ponto" da Conferencia de Ponto: repassa os parametros para o ponto.ps1 desta pasta.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0ponto.ps1" %*
exit /b %ERRORLEVEL%
