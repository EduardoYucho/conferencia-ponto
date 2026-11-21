@echo off
rem Comando "ponto" do Expedio: repassa os parametros para o ponto.ps1 desta pasta.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0ponto.ps1" %*
exit /b %ERRORLEVEL%
