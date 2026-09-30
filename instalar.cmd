@echo off
rem Instala (ou atualiza) a Conferencia de Ponto como servico do Windows e cria o comando "ponto".
rem Parametros opcionais: -Porta 8090  -Testes
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\ponto.ps1" instalar %*
exit /b %ERRORLEVEL%
