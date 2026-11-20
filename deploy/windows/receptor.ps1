<#
.SYNOPSIS
  Receptor de backup no Windows: o comando "receptor", de qualquer terminal.

.DESCRIPTION
  O Receptor recebe, na máquina do trabalho, o backup das pastas do sistema de um cliente, enviado pelo navegador
  do cliente (sem instalar nada lá). Roda num jar, iniciado pela tarefa agendada "ReceptorBackup" quando você entra no
  Windows, sem janela, e sobe de novo em até 5 minutos se cair.

  Instalação, uma vez, na pasta do projeto:   .\instalar-receptor.cmd   (opcional: -PastaBackups D:\Backups)
  Depois, em qualquer terminal (abra um novo depois de instalar):

    receptor              situação: rodando? tela, túnel, pasta dos backups
    receptor abrir        abre a tela no navegador
    receptor iniciar      sobe agora (e volta a subir sozinho no logon)
    receptor parar        para (e não sobe sozinho até "receptor iniciar")
    receptor reiniciar    para e sobe de novo (ex.: depois de mudar a configuração)
    receptor logs         acompanha o log ao vivo (Ctrl+C para sair)
    receptor config       abre a configuração no Bloco de Notas
    receptor tunel        instala o cloudflared pelo winget (o túnel que publica a página de coleta)
    receptor atualizar    recompila a partir da pasta do projeto e troca a versão
    receptor console      roda no próprio terminal, com a saída na tela (diagnóstico)
    receptor desinstalar  remove a tarefa e o comando (mantém configuração, logs e backups)

  Instalado em %USERPROFILE%\.receptor-backup:
    app\receptor-backup.jar        a aplicação
    config\application.yml         configuração desta máquina (pasta dos backups, portas, nível do zstd...)
    logs\receptor.log              log
  Backups em <pasta dos backups>\<Cliente>\<Cliente>_<data>.tar.zst (padrão: %USERPROFILE%\Backups-Clientes).
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)] [string] $Comando = 'status',
    [string] $PastaBackups = '',
    [int] $Porta = 0
)

$ErrorActionPreference = 'Stop'

$NomeTarefa  = 'ReceptorBackup'
$Raiz        = Join-Path $HOME '.receptor-backup'
$PastaApp    = Join-Path $Raiz 'app'
$PastaBin    = Join-Path $Raiz 'bin'
$PastaConfig = Join-Path $Raiz 'config'
$PastaLogs   = Join-Path $Raiz 'logs'
$Jar         = Join-Path $PastaApp 'receptor-backup.jar'
$ArquivoLog  = Join-Path $PastaLogs 'receptor.log'
$ArquivoConfig = Join-Path $PastaConfig 'application.yml'
$ArquivoEstado = Join-Path $Raiz 'instalacao.json'
$PortaPadrao = 8095

$ArgumentosJava = @(
    '-Xms32m', '-Xmx512m', '-XX:+ExitOnOutOfMemoryError',
    '-Dfile.encoding=UTF-8',
    '-jar', 'app\receptor-backup.jar',
    '--logging.file.name=logs/receptor.log',
    '--logging.logback.rollingpolicy.max-history=14'
)

function Titulo([string] $texto) { Write-Host ''; Write-Host $texto -ForegroundColor Cyan }
function Info([string] $texto)   { Write-Host "  $texto" }
function Ok([string] $texto)     { Write-Host "  $texto" -ForegroundColor Green }
function Aviso([string] $texto)  { Write-Host "  $texto" -ForegroundColor Yellow }
function Falhar([string] $texto) { Write-Host ''; Write-Host "  $texto" -ForegroundColor Red; exit 1 }

$SemBom = New-Object Text.UTF8Encoding $false

# ------------------------------------------------------------------------------------------ estado
function Ler-Estado {
    if (-not (Test-Path -LiteralPath $ArquivoEstado)) { return $null }
    return (Get-Content -LiteralPath $ArquivoEstado -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Salvar-Estado([string] $repositorio, [string] $javaw) {
    $json = [ordered]@{ repositorio = $repositorio; javaw = $javaw; atualizadoEm = (Get-Date).ToString('s') } | ConvertTo-Json
    [IO.File]::WriteAllText($ArquivoEstado, $json, $SemBom)
}

# Porta da tela: a da configuração desta máquina (receptor.porta-admin), senão 8095
function Porta-Configurada {
    if (Test-Path -LiteralPath $ArquivoConfig) {
        $linha = Select-String -LiteralPath $ArquivoConfig -Pattern '^\s*porta-admin:\s*(\d+)' | Select-Object -First 1
        if ($linha) { return [int] $linha.Matches[0].Groups[1].Value }
    }
    return $PortaPadrao
}

function Exigir-Instalacao {
    if (-not (Get-ScheduledTask -TaskName $NomeTarefa -ErrorAction SilentlyContinue)) {
        Falhar 'O Receptor não está instalado. Na pasta do projeto, rode: .\instalar-receptor.cmd'
    }
}

# ------------------------------------------------------------------------------------------ ferramentas
function Resolver-Repositorio {
    $candidato = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    if ($candidato -and (Test-Path -LiteralPath (Join-Path $candidato 'receptor-backup\pom.xml'))) { return $candidato }
    $estado = Ler-Estado
    if ($estado -and $estado.repositorio -and (Test-Path -LiteralPath (Join-Path $estado.repositorio 'receptor-backup\pom.xml'))) {
        return $estado.repositorio
    }
    Falhar 'Pasta do projeto não encontrada. Rode o instalar-receptor.cmd de dentro da pasta do projeto.'
}

function Resolver-Java {
    $javaw = $null
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javaw.exe'))) {
        $javaw = Join-Path $env:JAVA_HOME 'bin\javaw.exe'
    } else {
        $comando = Get-Command javaw.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($comando) { $javaw = $comando.Source }
    }
    if (-not $javaw) { Falhar 'Java não encontrado. Instale o JDK 17 ou mais novo (ou defina JAVA_HOME).' }
    return $javaw
}

function Resolver-Maven([string] $pasta) {
    if (Test-Path -LiteralPath (Join-Path $pasta '.mvn\wrapper\maven-wrapper.properties')) { return (Join-Path $pasta 'mvnw.cmd') }
    $noPath = Get-Command mvn -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($noPath) { return $noPath.Source }
    $baixado = Get-ChildItem -Path (Join-Path $HOME '.m2\wrapper\dists') -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($baixado) { return $baixado.FullName }
    Falhar 'Maven não encontrado. Instale o Maven (no PATH) ou rode uma vez o build do backend do ponto (que baixa o Maven).'
}

function Compilar([string] $repositorio) {
    $pasta = Join-Path $repositorio 'receptor-backup'
    $maven = Resolver-Maven $pasta
    New-Item -ItemType Directory -Force -Path $PastaLogs | Out-Null
    $logBuild = Join-Path $PastaLogs 'build.log'
    Info 'Compilando (o log fica em logs\build.log)...'
    $processo = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', "`"$maven`" -q -DskipTests package > `"$logBuild`" 2>&1" `
        -WorkingDirectory $pasta -NoNewWindow -Wait -PassThru
    if ($processo.ExitCode -ne 0) {
        Get-Content -LiteralPath $logBuild -Tail 25 | ForEach-Object { Write-Host "    $_" }
        Falhar 'A compilação falhou (veja acima).'
    }
    $gerado = Join-Path $pasta 'target\receptor-backup.jar'
    if (-not (Test-Path -LiteralPath $gerado)) { Falhar "Compilou, mas não achei $gerado." }
    return $gerado
}

# ------------------------------------------------------------------------------------------ tarefa e processo
function Registrar-Tarefa([string] $javaw) {
    $usuario = [Security.Principal.WindowsIdentity]::GetCurrent()
    $esc = { param($t) [Security.SecurityElement]::Escape($t) }
    $argumentos = ($ArgumentosJava | ForEach-Object { if ($_ -match '\s') { "`"$_`"" } else { $_ } }) -join ' '
    $inicio = (Get-Date).AddMinutes(1).ToString('yyyy-MM-ddTHH:mm:00')
    $comando = if ($javaw -match '\s') { "`"$javaw`"" } else { $javaw }
    $descricao = "Receptor de backup em http://localhost:$(Porta-Configurada) (criada por 'receptor instalar'; controle pelo comando 'receptor')."
    $xml = @"
<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>$(& $esc $descricao)</Description>
  </RegistrationInfo>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
      <UserId>$(& $esc $usuario.Name)</UserId>
      <Delay>PT15S</Delay>
    </LogonTrigger>
    <TimeTrigger>
      <Enabled>true</Enabled>
      <StartBoundary>$inicio</StartBoundary>
      <Repetition>
        <Interval>PT5M</Interval>
        <StopAtDurationEnd>false</StopAtDurationEnd>
      </Repetition>
    </TimeTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>$($usuario.User.Value)</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <IdleSettings>
      <StopOnIdleEnd>false</StopOnIdleEnd>
      <RestartOnIdle>false</RestartOnIdle>
    </IdleSettings>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>false</Hidden>
    <RunOnlyIfIdle>false</RunOnlyIfIdle>
    <WakeToRun>false</WakeToRun>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>5</Priority>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>$(& $esc $comando)</Command>
      <Arguments>$(& $esc $argumentos)</Arguments>
      <WorkingDirectory>$(& $esc $Raiz)</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
"@
    Register-ScheduledTask -TaskName $NomeTarefa -Xml $xml -Force | Out-Null
}

function Obter-Processos {
    @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like '*app\receptor-backup.jar*' })
}

function Responde([int] $porta) {
    try {
        $resposta = Invoke-WebRequest -Uri "http://127.0.0.1:$porta/api/estado" -UseBasicParsing -TimeoutSec 3
        return ($resposta.StatusCode -eq 200)
    } catch {
        return $false
    }
}

function Aguardar-Subida([int] $porta) {
    $inicio = Get-Date
    Write-Host '  Subindo' -NoNewline
    while (((Get-Date) - $inicio).TotalSeconds -lt 90) {
        if (Responde $porta) { Write-Host ' pronto.' -ForegroundColor Green; return $true }
        Start-Sleep -Seconds 2
        Write-Host '.' -NoNewline
    }
    Write-Host ''
    return $false
}

function Parar([switch] $Silencioso) {
    $tarefa = Get-ScheduledTask -TaskName $NomeTarefa -ErrorAction SilentlyContinue
    if ($tarefa) {
        if ($tarefa.State -ne 'Disabled') { Disable-ScheduledTask -TaskName $NomeTarefa | Out-Null }
        Stop-ScheduledTask -TaskName $NomeTarefa -ErrorAction SilentlyContinue
    }
    foreach ($p in Obter-Processos) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
    # o cloudflared iniciado pelo Receptor
    Get-CimInstance Win32_Process -Filter "Name = 'cloudflared.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*--url http://127.0.0.1:*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    for ($i = 0; $i -lt 30 -and (Obter-Processos).Count -gt 0; $i++) { Start-Sleep -Milliseconds 500 }
    if (-not $Silencioso) { Ok 'Parado. Não sobe sozinho até "receptor iniciar".' }
}

function Iniciar {
    Exigir-Instalacao
    $porta = Porta-Configurada
    $tarefa = Get-ScheduledTask -TaskName $NomeTarefa
    if ($tarefa.State -eq 'Disabled') { Enable-ScheduledTask -TaskName $NomeTarefa | Out-Null }
    if (Responde $porta) { Ok "Já está rodando: http://localhost:$porta"; return }
    Start-ScheduledTask -TaskName $NomeTarefa
    if (Aguardar-Subida $porta) { Ok "Rodando: http://localhost:$porta" } else { Aviso 'Não respondeu a tempo. Veja: receptor logs' }
}

# ------------------------------------------------------------------------------------------ comando no PATH
function Avisar-MudancaDeAmbiente {
    if (-not ('Receptor.Win32' -as [type])) {
        Add-Type -Namespace Receptor -Name Win32 -MemberDefinition @'
[DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam,
    uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
'@
    }
    $resultado = [UIntPtr]::Zero
    [Receptor.Win32]::SendMessageTimeout([IntPtr] 0xffff, 0x1A, [UIntPtr]::Zero, 'Environment', 2, 3000, [ref] $resultado) | Out-Null
}

function Alterar-Path([switch] $Remover) {
    $chave = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
    try {
        $bruto = [string] $chave.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        $itens = @($bruto -split ';' | Where-Object { $_ -and ($_.TrimEnd('\') -ne $PastaBin) })
        if (-not $Remover) { $itens += $PastaBin }
        $novo = $itens -join ';'
        if ($novo -ne $bruto) {
            $chave.SetValue('Path', $novo, [Microsoft.Win32.RegistryValueKind]::ExpandString)
            Avisar-MudancaDeAmbiente
        }
    } finally {
        $chave.Close()
    }
}

# ------------------------------------------------------------------------------------------ túnel
function Achar-Cloudflared {
    $comando = Get-Command cloudflared.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($comando) { return $comando.Source }
    foreach ($c in @("${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe", "$env:ProgramFiles\cloudflared\cloudflared.exe",
            "$env:LOCALAPPDATA\Microsoft\WinGet\Links\cloudflared.exe")) {
        if ($c -and (Test-Path -LiteralPath $c)) { return $c }
    }
    return $null
}

function Instalar-Tunel {
    $achado = Achar-Cloudflared
    if ($achado) { Ok "cloudflared já instalado: $achado"; return }
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        Falhar 'winget não encontrado. Baixe o cloudflared em https://github.com/cloudflare/cloudflared/releases e configure receptor.cloudflared.'
    }
    Info 'Instalando o cloudflared pelo winget (Cloudflare.cloudflared)...'
    & winget install --id Cloudflare.cloudflared -e --accept-source-agreements --accept-package-agreements
    if (Achar-Cloudflared) {
        Ok 'cloudflared instalado. Reiniciando o Receptor para abrir o túnel...'
        Parar -Silencioso
        Iniciar
    } else {
        Aviso 'O winget terminou, mas não achei o cloudflared. Abra um terminal novo e rode: receptor reiniciar'
    }
}

# ------------------------------------------------------------------------------------------ comandos
function Instalar {
    Titulo 'Instalando o Receptor de backup'
    $repositorio = Resolver-Repositorio
    $javaw = Resolver-Java
    New-Item -ItemType Directory -Force -Path $PastaApp, $PastaBin, $PastaConfig, $PastaLogs | Out-Null

    if (-not (Test-Path -LiteralPath $ArquivoConfig)) {
        $pasta = if ($PastaBackups) { $PastaBackups } else { Join-Path $HOME 'Backups-Clientes' }
        $portaTela = if ($Porta -gt 0) { $Porta } else { $PortaPadrao }
        $config = @(
            '# Configuração do Receptor nesta máquina (prevalece sobre o padrão do projeto). Depois de mudar: receptor reiniciar',
            'receptor:',
            '  # Onde ficam os backups: <pasta>\<Cliente>\<Cliente>_<data>.tar.zst',
            "  pasta-dados: `"$($pasta.Replace('\', '/'))`"",
            '  # Tela do Receptor (só nesta máquina) e porta que o túnel publica',
            "  porta-admin: $portaTela",
            "  porta-publica: $($portaTela + 1)",
            '  # Endereço fixo (túnel nomeado com domínio próprio). Vazio = túnel rápido do cloudflared.',
            '  endereco-publico: ""',
            '  # Nível do zstd no navegador do cliente (1 a 19; 17 compacta quase igual ao 19 e é muito mais rápido)',
            '  nivel-zstd: 17',
            '  # Limpeza automática: apaga coletas com mais de N dias (0 = desligada), mantendo a última de cada cliente',
            '  limpeza-dias: 0',
            '  manter-ultima-por-cliente: true'
        ) -join "`r`n"
        [IO.File]::WriteAllText($ArquivoConfig, $config + "`r`n", $SemBom)
        Ok "Configuração criada: $ArquivoConfig (backups em $pasta)"
    } else {
        Info "Configuração mantida: $ArquivoConfig"
    }

    $gerado = Compilar $repositorio
    Parar -Silencioso
    Copy-Item -LiteralPath $gerado -Destination $Jar -Force
    foreach ($arquivo in 'receptor.ps1', 'receptor.cmd') {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $arquivo) -Destination (Join-Path $PastaBin $arquivo) -Force
    }
    Alterar-Path
    Salvar-Estado $repositorio $javaw
    Registrar-Tarefa $javaw
    Ok 'Tarefa agendada "ReceptorBackup" criada (sobe no logon e volta se cair).'
    Iniciar
    if (-not (Achar-Cloudflared)) {
        Aviso 'Falta o túnel (cloudflared) para o navegador do cliente alcançar esta máquina. Instale com:'
        Aviso '  receptor tunel'
    }
    Info "Tela: http://localhost:$(Porta-Configurada)   (abra com: receptor abrir; em terminal novo)"
}

function Atualizar {
    Exigir-Instalacao
    Titulo 'Atualizando o Receptor'
    $repositorio = Resolver-Repositorio
    $gerado = Compilar $repositorio
    Parar -Silencioso
    Copy-Item -LiteralPath $gerado -Destination $Jar -Force
    foreach ($arquivo in 'receptor.ps1', 'receptor.cmd') {
        Copy-Item -LiteralPath (Join-Path $repositorio "deploy\windows\$arquivo") -Destination (Join-Path $PastaBin $arquivo) -Force
    }
    Registrar-Tarefa (Resolver-Java)
    Iniciar
}

function Status {
    Titulo 'Receptor de backup'
    if (-not (Get-ScheduledTask -TaskName $NomeTarefa -ErrorAction SilentlyContinue)) {
        Aviso 'Não instalado. Na pasta do projeto: .\instalar-receptor.cmd'
        return
    }
    $porta = Porta-Configurada
    try {
        $estado = Invoke-RestMethod -Uri "http://127.0.0.1:$porta/api/estado" -TimeoutSec 3
        Ok "Rodando: http://localhost:$porta"
        if ($estado.tunel.endereco) { Ok "Endereço público: $($estado.tunel.endereco)" } else { Aviso "Túnel: $($estado.tunel.mensagem)" }
        Info "Backups em: $($estado.pastaDados) ($([math]::Round($estado.espacoLivre / 1GB, 1)) GB livres)"
    } catch {
        Aviso 'Parado (ou ainda subindo). Para subir: receptor iniciar'
    }
    Info "Configuração: $ArquivoConfig"
    Info "Log: $ArquivoLog"
}

function Console {
    Exigir-Instalacao
    Parar -Silencioso
    $javaw = (Ler-Estado).javaw
    $java = Join-Path (Split-Path -Parent $javaw) 'java.exe'
    Push-Location $Raiz
    try { & $java @ArgumentosJava } finally { Pop-Location }
}

function Desinstalar {
    Parar -Silencioso
    Unregister-ScheduledTask -TaskName $NomeTarefa -Confirm:$false -ErrorAction SilentlyContinue
    Alterar-Path -Remover
    Remove-Item -LiteralPath $PastaBin -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $PastaApp -Recurse -Force -ErrorAction SilentlyContinue
    Ok "Removido. Ficaram a configuração e os logs em $Raiz e os backups na pasta deles."
}

switch ($Comando.ToLowerInvariant()) {
    'status'      { Status }
    'instalar'    { Instalar }
    'atualizar'   { Atualizar }
    'iniciar'     { Iniciar }
    'parar'       { Exigir-Instalacao; Parar }
    'reiniciar'   { Exigir-Instalacao; Parar -Silencioso; Iniciar }
    'abrir'       { Start-Process "http://localhost:$(Porta-Configurada)" }
    'logs'        { Get-Content -LiteralPath $ArquivoLog -Tail 40 -Wait -Encoding UTF8 }
    'config'      { Start-Process notepad.exe $ArquivoConfig }
    'tunel'       { Instalar-Tunel }
    'console'     { Console }
    'desinstalar' { Desinstalar }
    default       { Falhar "Comando desconhecido: $Comando. Veja: Get-Help receptor.ps1 -Detailed" }
}
