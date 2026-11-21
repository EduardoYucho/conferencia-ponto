@echo off
rem Instala (ou atualiza) o Expedio como servico do Windows e cria o comando "expedio".
rem Parametros opcionais: -Porta 8090  -Testes
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\expedio.ps1" instalar %*
exit /b %ERRORLEVEL%
