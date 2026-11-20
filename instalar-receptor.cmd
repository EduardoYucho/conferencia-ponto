@echo off
rem Instala (ou reinstala) o Receptor de backup nesta maquina e cria o comando "receptor".
rem Parametros opcionais: -PastaBackups D:\Backups  -Porta 8095
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\receptor.ps1" instalar %*
exit /b %ERRORLEVEL%
