@echo off
rem Comando "receptor" do Receptor de backup: repassa os parametros para o receptor.ps1 desta pasta.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0receptor.ps1" %*
exit /b %ERRORLEVEL%
