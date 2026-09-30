<#
.SYNOPSIS
  Conferência de Ponto no Windows: o comando "ponto", de qualquer terminal, sem abrir a pasta do projeto.

.DESCRIPTION
  A aplicação inteira (API + telas) roda num único jar, iniciado pela tarefa agendada "ConferenciaPonto"
  quando você entra no Windows. A tarefa roda com o seu usuário (acessa a pasta de rede com as suas
  credenciais), sem janela, e sobe a aplicação de novo em até 5 minutos se ela cair.

  Instalação, uma vez, na pasta do projeto:   .\instalar.cmd
  Depois, em qualquer terminal (abra um novo depois de instalar):

    ponto               situação: rodando? endereço, logs, configuração
    ponto abrir         abre no navegador
    ponto iniciar       sobe agora (e volta a subir sozinho no logon)
    ponto parar         para (e não sobe sozinho até "ponto iniciar")
    ponto reiniciar     para e sobe de novo (ex.: depois de mudar a configuração)
    ponto logs          acompanha o log (Ctrl+C para sair)
    ponto config        abre a configuração (pasta dos PDFs, usuários...) no Bloco de Notas
    ponto atualizar     recompila a partir da pasta do projeto e troca a versão (-Testes roda os testes)
    ponto console       roda no próprio terminal, com a saída na tela (diagnóstico; Ctrl+C para parar)
    ponto desinstalar   remove a tarefa e o comando (mantém configuração, logs, banco e PDFs)

  Instalado em %USERPROFILE%\.conferencia-ponto\servico (ao lado de comprovantes\, o arquivo dos PDFs):
    app\conferencia-ponto.jar     a aplicação
    config\application.yml        configuração desta máquina (copiada de backend\config na instalação)
    config\servico.yml            porta, segredo do login e logs (gerado)
    logs\conferencia-ponto.log    log da aplicação (e build.log da última compilação)

.EXAMPLE
  ponto
.EXAMPLE
  ponto atualizar -Testes
.EXAMPLE
  .\instalar.cmd -Porta 8090
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)] [string] $Comando = 'status',
    [int] $Porta = 0,
    [switch] $Testes
)

$ErrorActionPreference = 'Stop'

$NomeTarefa    = 'ConferenciaPonto'
# Fora do AppData de propósito: é uma pasta comum, igual para qualquer programa (o AppData pode ser
# virtualizado para apps empacotados, e a tarefa agendada não enxergaria a instalação).
$Raiz          = Join-Path $HOME '.conferencia-ponto\servico'
$PastaApp      = Join-Path $Raiz 'app'
$PastaBin      = Join-Path $Raiz 'bin'
$PastaConfig   = Join-Path $Raiz 'config'
$PastaLogs     = Join-Path $Raiz 'logs'
$Jar           = Join-Path $PastaApp 'conferencia-ponto.jar'
$ArquivoEstado = Join-Path $Raiz 'instalacao.json'
$ArquivoLog    = Join-Path $PastaLogs 'conferencia-ponto.log'
$LogBuild      = Join-Path $PastaLogs 'build.log'
$PortaPadrao   = 8080

# Argumentos da JVM e da aplicação (a tarefa roda com a pasta de instalação como diretório de trabalho,
# então o Spring Boot lê config\application.yml sozinho; o servico.yml vem por último e prevalece).
$ArgumentosJava = @(
    '-Xms64m', '-Xmx512m', '-XX:+ExitOnOutOfMemoryError',
    '-Dnet.bytebuddy.experimental=true',   # Hibernate em JDKs mais novos que o suportado pelo Spring Boot 3.5
    '-Dfile.encoding=UTF-8',
    '-jar', 'app\conferencia-ponto.jar',
    '--spring.config.additional-location=optional:file:./config/servico.yml'
)

# ------------------------------------------------------------------------------------------ saída
function Titulo([string] $texto) { Write-Host ''; Write-Host $texto -ForegroundColor Cyan }
function Info([string] $texto)   { Write-Host "  $texto" }
function Passo([string] $texto)  { Write-Host "  > $texto" -ForegroundColor DarkGray }
function Ok([string] $texto)     { Write-Host "  $texto" -ForegroundColor Green }
function Aviso([string] $texto)  { Write-Host "  $texto" -ForegroundColor Yellow }
function Falhar([string] $texto) {
    Write-Host ''
    Write-Host "  $texto" -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------------------------------ estado
function Ler-Estado {
    if (-not (Test-Path -LiteralPath $ArquivoEstado)) { return $null }
    return (Get-Content -LiteralPath $ArquivoEstado -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Salvar-Estado([string] $repositorio, [int] $porta, [string] $javaw) {
    $estado = [ordered]@{
        repositorio  = $repositorio
        porta        = $porta
        javaw        = $javaw
        atualizadoEm = (Get-Date).ToString('s')
    }
    $json = $estado | ConvertTo-Json
    [IO.File]::WriteAllText($ArquivoEstado, $json, (New-Object Text.UTF8Encoding $false))
}

function Porta-Configurada {
    $estado = Ler-Estado
    if ($estado -and $estado.porta) { return [int] $estado.porta }
    return $PortaPadrao
}

function Exigir-Instalacao {
    if (-not (Get-ScheduledTask -TaskName $NomeTarefa -ErrorAction SilentlyContinue)) {
        Falhar 'A Conferência de Ponto não está instalada. Na pasta do projeto, rode: .\instalar.cmd'
    }
}

# ------------------------------------------------------------------------------------------ ferramentas
function Resolver-Repositorio {
    # Rodando de dentro do projeto (deploy\windows)? Senão, a pasta gravada na instalação.
    $candidato = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    if ($candidato -and (Test-Path -LiteralPath (Join-Path $candidato 'backend\pom.xml'))) { return $candidato }
    $estado = Ler-Estado
    if ($estado -and $estado.repositorio -and (Test-Path -LiteralPath (Join-Path $estado.repositorio 'backend\pom.xml'))) {
        return $estado.repositorio
    }
    Falhar 'Pasta do projeto não encontrada. Rode o instalar.cmd de dentro da pasta do projeto.'
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

    $java = Join-Path (Split-Path -Parent $javaw) 'java.exe'
    $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $linha = (& $java -version 2>&1 | Select-Object -First 1 | Out-String)
    $ErrorActionPreference = $eap
    $versao = 0
    if ($linha -match 'version "(\d+)(?:\.(\d+))?') {
        $versao = [int] $Matches[1]
        if ($versao -eq 1 -and $Matches[2]) { $versao = [int] $Matches[2] }
    }
    if ($versao -lt 17) { Falhar "Java 17 ou mais novo é necessário (encontrado: $($linha.Trim()))." }
    return [pscustomobject]@{ Javaw = $javaw; Java = $java; Versao = $versao }
}

function Resolver-Maven([string] $backend) {
    if (Test-Path -LiteralPath (Join-Path $backend '.mvn\wrapper\maven-wrapper.properties')) {
        return (Join-Path $backend 'mvnw.cmd')
    }
    $noPath = Get-Command mvn -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($noPath) { return $noPath.Source }
    $baixado = Get-ChildItem -Path (Join-Path $HOME '.m2\wrapper\dists') -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($baixado) { return $baixado.FullName }
    return $null
}

# Roda um .cmd/.exe pelo cmd.exe, com toda a saída anexada ao build.log. Devolve o código de saída.
function Executar-NoLog([string] $pasta, [string] $executavel, [string] $argumentos) {
    Add-Content -LiteralPath $LogBuild -Value "`r`n=== $(Get-Date -Format 's') $executavel $argumentos (em $pasta)" -Encoding UTF8
    Push-Location -LiteralPath $pasta
    try {
        $eap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
        & cmd.exe /d /s /c "`"$executavel`" $argumentos >> `"$LogBuild`" 2>&1"
        $codigo = $LASTEXITCODE
        $ErrorActionPreference = $eap
    } finally {
        Pop-Location
    }
    return $codigo
}

function Mostrar-FimDoLog([string] $arquivo, [int] $linhas = 25) {
    if (Test-Path -LiteralPath $arquivo) {
        Write-Host ''
        Get-Content -LiteralPath $arquivo -Tail $linhas -Encoding UTF8 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
    }
}

# ------------------------------------------------------------------------------------------ build
function Compilar([string] $repositorio) {
    $frontend = Join-Path $repositorio 'frontend'
    $backend  = Join-Path $repositorio 'backend'

    $npm = Get-Command npm.cmd -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $npm) { Falhar 'Node.js (npm) não encontrado. Instale o Node.js 20 ou mais novo.' }
    $maven = Resolver-Maven $backend
    if (-not $maven) {
        Falhar ('Maven não encontrado. Instale o Maven (e coloque-o no PATH) ou crie o arquivo do Maven Wrapper ' +
                'em backend\.mvn\wrapper (veja o README).')
    }

    New-Item -ItemType Directory -Force -Path $PastaLogs | Out-Null
    Set-Content -LiteralPath $LogBuild -Value "Compilação de $(Get-Date -Format 's') a partir de $repositorio" -Encoding UTF8

    Passo 'Front-end: npm run build'
    if (-not (Test-Path -LiteralPath (Join-Path $frontend 'node_modules'))) {
        if ((Executar-NoLog $frontend $npm.Source 'ci --no-audit --no-fund') -ne 0) {
            Mostrar-FimDoLog $LogBuild; Falhar "Falhou o npm ci. Log completo: $LogBuild"
        }
    }
    if ((Executar-NoLog $frontend $npm.Source 'run build') -ne 0) {
        Mostrar-FimDoLog $LogBuild; Falhar "Falhou o build do front-end. Log completo: $LogBuild"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $frontend 'dist\index.html'))) {
        Falhar 'O build do front-end não gerou frontend\dist\index.html.'
    }

    $argumentos = '-B -Papp clean package'
    if ($Testes) { Passo 'Back-end: Maven com testes (pode levar alguns minutos)' }
    else { Passo 'Back-end: Maven (sem testes; use -Testes para rodá-los)'; $argumentos += ' -DskipTests' }
    if ((Executar-NoLog $backend $maven $argumentos) -ne 0) {
        Mostrar-FimDoLog $LogBuild 40; Falhar "Falhou o build do back-end. Log completo: $LogBuild"
    }

    $gerado = Join-Path $backend 'target-app\conferencia-ponto.jar'
    if (-not (Test-Path -LiteralPath $gerado)) { Falhar "O Maven não gerou $gerado." }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($gerado)
    try { $temTelas = [bool] ($zip.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' }) }
    finally { $zip.Dispose() }
    if (-not $temTelas) { Falhar 'O jar gerado não contém o front-end (static/index.html).' }
    return $gerado
}

# ------------------------------------------------------------------------------------------ configuração
function Segredo-Existente([string] $repositorio) {
    $servico = Join-Path $PastaConfig 'servico.yml'
    if (Test-Path -LiteralPath $servico) {
        $achado = Select-String -LiteralPath $servico -Pattern '^\s*segredo:\s*"(.+)"\s*$' | Select-Object -First 1
        if ($achado) { return $achado.Matches[0].Groups[1].Value }
    }
    # O mesmo segredo do terminal/Eclipse (backend\ambiente.local.ps1): o login vale nos dois
    $ambiente = Join-Path $repositorio 'backend\ambiente.local.ps1'
    if (Test-Path -LiteralPath $ambiente) {
        $achado = Select-String -LiteralPath $ambiente -Pattern "^\s*\`$env:PONTO_JWT_SEGREDO\s*=\s*['""]([^'""]{32,})['""]" |
            Select-Object -First 1
        if ($achado) { return $achado.Matches[0].Groups[1].Value }
    }
    if ($env:PONTO_JWT_SEGREDO -and $env:PONTO_JWT_SEGREDO.Length -ge 32) { return $env:PONTO_JWT_SEGREDO }
    $bytes = New-Object byte[] 48
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return [Convert]::ToBase64String($bytes)
}

function Preparar-Config([string] $repositorio, [int] $porta) {
    New-Item -ItemType Directory -Force -Path $PastaConfig | Out-Null
    $semBom = New-Object Text.UTF8Encoding $false

    $aplicacao = Join-Path $PastaConfig 'application.yml'
    if (-not (Test-Path -LiteralPath $aplicacao)) {
        $local = Join-Path $repositorio 'backend\config\application.yml'
        if (Test-Path -LiteralPath $local) {
            Copy-Item -LiteralPath $local -Destination $aplicacao
            Passo 'Configuração copiada de backend\config\application.yml'
        } else {
            $modelo = @(
                '# Configuração desta instalação: tem prioridade sobre o application.yml do projeto.',
                '# Depois de editar: ponto reiniciar',
                '#',
                '# ponto:',
                '#   importacao-pdf:',
                '#     # Pasta dos comprovantes em PDF (local, ou de rede com barras normais)',
                '#     diretorio: //servidor/Ponto',
                ''
            ) -join "`r`n"
            [IO.File]::WriteAllText($aplicacao, $modelo, $semBom)
        }
    }

    $segredo = Segredo-Existente $repositorio
    $log = ($ArquivoLog -replace '\\', '/')
    $servico = @(
        '# Gerado por "ponto instalar" / "ponto atualizar": porta, segredo do login e logs do serviço.',
        '# A configuração da aplicação (pasta dos PDFs, usuários...) fica no application.yml desta pasta.',
        'server:',
        "  port: $porta",
        'ponto:',
        '  seguranca:',
        '    jwt:',
        "      segredo: `"$segredo`"",
        'logging:',
        '  file:',
        "    name: `"$log`"",
        '  threshold:',
        '    console: "OFF"',
        '  logback:',
        '    rollingpolicy:',
        '      max-file-size: 10MB',
        '      max-history: 14',
        ''
    ) -join "`r`n"
    [IO.File]::WriteAllText((Join-Path $PastaConfig 'servico.yml'), $servico, $semBom)
}

function Copiar-Scripts([string] $repositorio) {
    New-Item -ItemType Directory -Force -Path $PastaBin | Out-Null
    $origem = Join-Path $repositorio 'deploy\windows'
    foreach ($arquivo in 'ponto.ps1', 'ponto.cmd') {
        $de = Join-Path $origem $arquivo
        $para = Join-Path $PastaBin $arquivo
        if ((Resolve-Path -LiteralPath $de).Path -ne $para) { Copy-Item -LiteralPath $de -Destination $para -Force }
    }
}

function Avisar-MudancaDeAmbiente {
    if (-not ('Ponto.Win32' -as [type])) {
        Add-Type -Namespace Ponto -Name Win32 -MemberDefinition @'
[DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam,
    uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
'@
    }
    $resultado = [UIntPtr]::Zero
    [Ponto.Win32]::SendMessageTimeout([IntPtr] 0xffff, 0x1A, [UIntPtr]::Zero, 'Environment', 2, 3000, [ref] $resultado) | Out-Null
}

# PATH do usuário, preservando entradas com %VARIAVEIS% (REG_EXPAND_SZ)
function Alterar-Path([switch] $Remover) {
    $chave = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
    try {
        $bruto = [string] $chave.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        # remove também o caminho de versões anteriores (%LOCALAPPDATA%\ConferenciaPonto\bin)
        $itens = @($bruto -split ';' | Where-Object {
            $_ -and ($_.TrimEnd('\') -ne $PastaBin) -and ($_ -notlike '*\ConferenciaPonto\bin')
        })
        if (-not $Remover) { $itens += $PastaBin }
        $novo = $itens -join ';'
        if ($novo -ne $bruto) {
            $chave.SetValue('Path', $novo, [Microsoft.Win32.RegistryValueKind]::ExpandString)
            Avisar-MudancaDeAmbiente
        }
    } finally {
        $chave.Close()
    }
    if (-not $Remover -and (($env:Path -split ';') -notcontains $PastaBin)) { $env:Path = "$env:Path;$PastaBin" }
}

# ------------------------------------------------------------------------------------------ tarefa agendada
function Registrar-Tarefa([string] $javaw, [int] $porta) {
    $usuario = [Security.Principal.WindowsIdentity]::GetCurrent()
    $esc = { param($t) [Security.SecurityElement]::Escape($t) }
    $argumentos = ($ArgumentosJava | ForEach-Object { if ($_ -match '\s') { "`"$_`"" } else { $_ } }) -join ' '
    $inicio = (Get-Date).AddMinutes(1).ToString('yyyy-MM-ddTHH:mm:00')
    $comando = if ($javaw -match '\s') { "`"$javaw`"" } else { $javaw }
    $descricao = "Conferência de Ponto em http://localhost:$porta (criada por 'ponto instalar'; controle pelo comando 'ponto')."

    # Dois gatilhos: no logon (15 s depois, para a rede subir) e a cada 5 minutos, que só tem efeito se a
    # aplicação não estiver rodando (IgnoreNew): é o que a sobe de novo se ela cair.
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

# Processos da aplicação. Com o Java da Oracle no PATH, o javaw.exe de "javapath" é um lançador que fica
# esperando o javaw.exe real (filho): os dois têm o jar na linha de comando.
function Obter-Processos {
    @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like '*app\conferencia-ponto.jar*' })
}

# O processo que de fato roda a aplicação (o de maior memória)
function Obter-Processo {
    Obter-Processos | Sort-Object WorkingSetSize -Descending | Select-Object -First 1
}

function Dono-Da-Porta([int] $porta) {
    $conexao = Get-NetTCPConnection -LocalPort $porta -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $conexao) { return $null }
    return (Get-Process -Id $conexao.OwningProcess -ErrorAction SilentlyContinue)
}

function Responde([int] $porta) {
    try {
        $resposta = Invoke-WebRequest -Uri "http://127.0.0.1:$porta/" -UseBasicParsing -TimeoutSec 3
        return ($resposta.StatusCode -eq 200)
    } catch {
        return $false
    }
}

function Aguardar-Subida([int] $porta, [int] $segundos = 150) {
    $inicio = Get-Date
    Write-Host '  Subindo' -NoNewline
    while (((Get-Date) - $inicio).TotalSeconds -lt $segundos) {
        if (Responde $porta) { Write-Host ' pronto.' -ForegroundColor Green; return $true }
        Start-Sleep -Seconds 2
        Write-Host '.' -NoNewline
        if (((Get-Date) - $inicio).TotalSeconds -gt 10 -and -not (Obter-Processo)) { Write-Host ''; return $false }
    }
    Write-Host ''
    return $false
}

function Parar-Aplicacao([switch] $Silencioso) {
    $tarefa = Get-ScheduledTask -TaskName $NomeTarefa -ErrorAction SilentlyContinue
    if ($tarefa) {
        if ($tarefa.State -ne 'Disabled') { Disable-ScheduledTask -TaskName $NomeTarefa | Out-Null }
        Stop-ScheduledTask -TaskName $NomeTarefa -ErrorAction SilentlyContinue
    }
    foreach ($processo in Obter-Processos) { Stop-Process -Id $processo.ProcessId -Force -ErrorAction SilentlyContinue }
    for ($i = 0; $i -lt 30 -and (Obter-Processo); $i++) { Start-Sleep -Milliseconds 500 }
    if (-not $Silencioso) { Ok 'Parado. Não sobe sozinho até "ponto iniciar".' }
}

function Iniciar-Aplicacao {
    Exigir-Instalacao
    $porta = Porta-Configurada
    $tarefa = Get-ScheduledTask -TaskName $NomeTarefa
    if ($tarefa.State -eq 'Disabled') { Enable-ScheduledTask -TaskName $NomeTarefa | Out-Null }

    if (Obter-Processo) {
        if ((Responde $porta) -or (Aguardar-Subida $porta)) { Ok "Rodando: http://localhost:$porta"; return }
    } else {
        $dono = Dono-Da-Porta $porta
        if ($dono) {
            Aviso "A porta $porta está ocupada por $($dono.ProcessName) (PID $($dono.Id)) — provavelmente o back-end no Eclipse."
            Aviso 'Pare-o: a Conferência de Ponto sobe sozinha em até 5 minutos (ou rode "ponto iniciar" de novo).'
            return
        }
        Start-ScheduledTask -TaskName $NomeTarefa
        if (Aguardar-Subida $porta) { Ok "Rodando: http://localhost:$porta"; return }
    }
    Aviso 'A aplicação não respondeu. Fim do log:'
    Mostrar-FimDoLog $ArquivoLog 30
    Aviso 'Para ver o erro completo na tela: ponto parar; ponto console'
    exit 1
}

# ------------------------------------------------------------------------------------------ comandos
function Cmd-Instalar([switch] $Atualizacao) {
    $repositorio = Resolver-Repositorio
    $estado = Ler-Estado
    $porta = if ($Porta -gt 0) { $Porta } elseif ($estado -and $estado.porta) { [int] $estado.porta } else { $PortaPadrao }

    if ($Atualizacao) { Titulo "Atualizando a Conferência de Ponto a partir de $repositorio" }
    else { Titulo "Instalando a Conferência de Ponto a partir de $repositorio" }

    $java = Resolver-Java
    Passo "Java $($java.Versao): $($java.Javaw)"
    New-Item -ItemType Directory -Force -Path $Raiz, $PastaApp, $PastaLogs | Out-Null
    $gerado = Compilar $repositorio

    Passo "Instalando em $Raiz"
    Parar-Aplicacao -Silencioso
    Copy-Item -LiteralPath $gerado -Destination $Jar -Force
    Copiar-Scripts $repositorio
    Preparar-Config $repositorio $porta
    Alterar-Path
    Registrar-Tarefa $java.Javaw $porta
    Salvar-Estado $repositorio $porta $java.Javaw

    Iniciar-Aplicacao
    Titulo 'Pronto.'
    Info "Endereço:       http://localhost:$porta"
    Info 'Início:         automático quando você entra no Windows (tarefa agendada "ConferenciaPonto")'
    Info "Configuração:   $(Join-Path $PastaConfig 'application.yml')"
    Info "Logs:           $ArquivoLog"
    if (-not $Atualizacao) {
        Write-Host ''
        Info 'Abra um terminal NOVO e use:  ponto  |  ponto abrir  |  ponto logs  |  ponto parar  |  ponto atualizar'
    }
}

function Cmd-Status {
    Titulo 'Conferência de Ponto'
    $tarefa = Get-ScheduledTask -TaskName $NomeTarefa -ErrorAction SilentlyContinue
    if (-not $tarefa) {
        Info 'Não instalada. Na pasta do projeto, rode: .\instalar.cmd'
        return
    }
    $porta = Porta-Configurada
    $processo = Obter-Processo
    if ($processo) {
        $detalhe = Get-Process -Id $processo.ProcessId -ErrorAction SilentlyContinue
        $desde = if ($detalhe) { $detalhe.StartTime.ToString('dd/MM HH:mm') } else { '?' }
        $memoria = if ($detalhe) { '{0:N0} MB' -f ($detalhe.WorkingSet64 / 1MB) } else { '?' }
        if (Responde $porta) { Ok "Situação:       rodando desde $desde (PID $($processo.ProcessId), $memoria)" }
        else { Aviso "Situação:       subindo... (PID $($processo.ProcessId), desde $desde)" }
    } elseif ($tarefa.State -eq 'Disabled') {
        Aviso 'Situação:       parada (ponto iniciar para subir)'
    } else {
        $info = Get-ScheduledTaskInfo -TaskName $NomeTarefa
        Aviso "Situação:       parada; tenta subir de novo a cada 5 min (última saída: código $($info.LastTaskResult))"
        $dono = Dono-Da-Porta $porta
        if ($dono) { Aviso "                a porta $porta está ocupada por $($dono.ProcessName) (PID $($dono.Id)) — Eclipse?" }
    }
    Info "Endereço:       http://localhost:$porta"
    $automatico = if ($tarefa.State -eq 'Disabled') { 'desligado (ponto iniciar religa)' } else { 'no logon do Windows' }
    Info "Início:         $automatico"
    Info "Configuração:   $(Join-Path $PastaConfig 'application.yml')"
    Info "Logs:           $ArquivoLog"
    $estado = Ler-Estado
    if ($estado -and $estado.repositorio) { Info "Projeto:        $($estado.repositorio)" }
    Write-Host ''
    Info 'Comandos: ponto abrir | iniciar | parar | reiniciar | logs | config | atualizar [-Testes] | console | desinstalar'
}

function Cmd-Logs {
    if (-not (Test-Path -LiteralPath $ArquivoLog)) { Falhar "Ainda não há log em $ArquivoLog." }
    Info "$ArquivoLog  (Ctrl+C para sair)"
    Get-Content -LiteralPath $ArquivoLog -Tail 60 -Wait -Encoding UTF8
}

function Cmd-Console {
    Exigir-Instalacao
    $porta = Porta-Configurada
    if (Obter-Processo) { Falhar 'A aplicação já está rodando em segundo plano. Primeiro: ponto parar' }
    $dono = Dono-Da-Porta $porta
    if ($dono) { Falhar "A porta $porta está ocupada por $($dono.ProcessName) (PID $($dono.Id))." }
    $estado = Ler-Estado
    $java = if ($estado -and $estado.javaw) { Join-Path (Split-Path -Parent $estado.javaw) 'java.exe' } else { (Resolver-Java).Java }
    Info "Rodando em primeiro plano (Ctrl+C para parar). Depois: ponto iniciar"
    Push-Location -LiteralPath $Raiz
    try {
        $ErrorActionPreference = 'Continue'
        & $java @($ArgumentosJava + '--logging.threshold.console=INFO')
    } finally {
        Pop-Location
    }
}

function Cmd-Desinstalar {
    Titulo 'Removendo a Conferência de Ponto'
    Parar-Aplicacao -Silencioso
    if (Get-ScheduledTask -TaskName $NomeTarefa -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $NomeTarefa -Confirm:$false
    }
    Alterar-Path -Remover
    Ok 'Tarefa agendada e comando "ponto" removidos.'
    Info "Mantidos: $Raiz (configuração e logs), o banco de dados e os PDFs arquivados."
}

switch ($Comando.ToLowerInvariant()) {
    'instalar'    { Cmd-Instalar }
    'atualizar'   { Cmd-Instalar -Atualizacao }
    'status'      { Cmd-Status }
    'situacao'    { Cmd-Status }
    'iniciar'     { Iniciar-Aplicacao }
    'parar'       { Exigir-Instalacao; Parar-Aplicacao }
    'reiniciar'   { Exigir-Instalacao; Parar-Aplicacao -Silencioso; Iniciar-Aplicacao }
    'logs'        { Cmd-Logs }
    'log'         { Cmd-Logs }
    'abrir'       { Exigir-Instalacao; Start-Process "http://localhost:$(Porta-Configurada)/" }
    'config'      {
        Exigir-Instalacao
        Start-Process notepad.exe -ArgumentList "`"$(Join-Path $PastaConfig 'application.yml')`""
        Info 'Depois de salvar: ponto reiniciar'
    }
    'console'     { Cmd-Console }
    'desinstalar' { Cmd-Desinstalar }
    'path'        { Alterar-Path; Ok "$PastaBin está no PATH do usuário (vale para terminais novos)." }
    { $_ -in 'ajuda', 'help', '-h', '/?', '--help' } { Get-Help $PSCommandPath -Detailed }
    default       { Aviso "Comando desconhecido: $Comando"; Cmd-Status; exit 1 }
}
