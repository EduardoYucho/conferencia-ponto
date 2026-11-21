<#
.SYNOPSIS
  Expedio no Windows: o comando "expedio", de qualquer terminal, sem abrir a pasta do projeto.

.DESCRIPTION
  A aplicação inteira (API + telas) roda num único jar, iniciado pela tarefa agendada "Expedio"
  quando você entra no Windows. A tarefa roda com o seu usuário (acessa a pasta de rede com as suas
  credenciais), sem janela, e sobe a aplicação de novo em até 5 minutos se ela cair.

  Instalação, uma vez, na pasta do projeto:   .\instalar.cmd
  Depois, em qualquer terminal (abra um novo depois de instalar):

    expedio             situação: rodando? endereço, logs, configuração
    expedio abrir         abre no navegador
    expedio iniciar       sobe agora (e volta a subir sozinho no logon)
    expedio parar         para (e não sobe sozinho até "expedio iniciar")
    expedio reiniciar     para e sobe de novo (ex.: depois de mudar a configuração)
    expedio logs          acompanha o log ao vivo, colorido por gravidade (Ctrl+C para sair)
    expedio logs maria    ao vivo, só o que é do usuário "maria" (ou "sistema": o que não é de uma pessoa)
    expedio logs -Erros   ao vivo, só avisos e erros (combina com o usuário: expedio logs maria -Erros)
    expedio logs pasta    abre a pasta dos logs por usuário e por hora
    expedio config        abre a configuração (pasta dos PDFs, usuários...) no Bloco de Notas
    expedio atualizar     recompila a partir da pasta do projeto, faz o backup e troca a versão (-Testes roda os testes)
    expedio voltar        volta para a versão anterior (a de antes do último "expedio atualizar"; de novo = desfaz)
    expedio backup        copia o banco (e as imagens da base de conhecimento) para a pasta de backups
    expedio backup antes-da-mudanca   o mesmo, com um motivo no nome da pasta
    expedio backup lista  mostra os backups guardados (e a regra de guarda)
    expedio restaurar     volta o banco para um backup (pergunta qual e pede confirmação; faz um backup de agora antes)
    expedio console       roda no próprio terminal, com a saída na tela (diagnóstico; Ctrl+C para parar)
    expedio tunel         instala o cloudflared (o túnel dos Backups de clientes) pelo winget e reinicia
    expedio versao        a versão instalada (a do rodapé), a anterior e as últimas geradas
    expedio desinstalar   remove a tarefa e o comando (mantém configuração, logs, banco e PDFs)

  Instalado em %USERPROFILE%\.expedio\servico (ao lado de comprovantes\, o arquivo dos PDFs):
    app\expedio.jar     a aplicação
    app\expedio.anterior.jar   a versão de antes do último "expedio atualizar" (para "expedio voltar")
    config\application.yml        configuração desta máquina (copiada de backend\config na instalação)
    config\servico.yml            porta, segredo do login e logs (gerado)
    logs\expedio.log    log da aplicação (e build.log da última compilação)
    logs\usuarios\<login>\<aaaa-mm-dd>\<hh>h.log   um arquivo por usuário e por hora (guardados por 30 dias)
  Backups em %USERPROFILE%\.expedio\backups\<aaaa-mm-dd_hhmmss>[_motivo]\ (banco.dump e backup.json).
  A aplicação faz um por dia sozinha ("expedio backup diario", depois de expedio.backup.horario).

.EXAMPLE
  expedio
.EXAMPLE
  expedio atualizar -Testes
.EXAMPLE
  expedio backup lista
.EXAMPLE
  expedio restaurar
.EXAMPLE
  .\instalar.cmd -Porta 8090
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)] [string] $Comando = 'status',
    # "expedio logs <usuario>": de quem são as linhas a acompanhar (ou "pasta" para abrir a pasta dos logs);
    # "expedio backup <motivo>" ou "expedio backup lista"; "expedio restaurar <pasta do backup>"
    [Parameter(Position = 1)] [string] $Alvo = '',
    [int] $Porta = 0,
    [switch] $Testes,
    # "expedio logs -Erros": só avisos e erros
    [switch] $Erros
)

$ErrorActionPreference = 'Stop'

$NomeTarefa    = 'Expedio'
# Fora do AppData de propósito: é uma pasta comum, igual para qualquer programa (o AppData pode ser
# virtualizado para apps empacotados, e a tarefa agendada não enxergaria a instalação).
$Raiz          = Join-Path $HOME '.expedio\servico'
$PastaApp      = Join-Path $Raiz 'app'
$PastaBin      = Join-Path $Raiz 'bin'
$PastaConfig   = Join-Path $Raiz 'config'
$PastaLogs     = Join-Path $Raiz 'logs'
$Jar           = Join-Path $PastaApp 'expedio.jar'
$JarAnterior   = Join-Path $PastaApp 'expedio.anterior.jar'
$ArquivoEstado = Join-Path $Raiz 'instalacao.json'
$ArquivoLog    = Join-Path $PastaLogs 'expedio.log'
$PastaLogsUsuarios = Join-Path $PastaLogs 'usuarios'
$LogBuild      = Join-Path $PastaLogs 'build.log'
# Versões geradas ("expedio atualizar"): a numeração 1.1.001.001 continua daqui (veja Proxima-Versao)
$ArquivoVersoes = if ($env:EXPEDIO_VERSOES) { $env:EXPEDIO_VERSOES } else { Join-Path $Raiz 'versoes.json' }
# Diagnóstico de quedas da JVM (veja $ArgumentosJava): a memória que acabou e os erros graves da própria JVM
$ArquivoMemoriaEsgotada = Join-Path $PastaLogs 'memoria-esgotada.hprof'
$ErrosDaJvmGuardados = 5
$PortaPadrao   = 8080
# Backups do banco ("expedio backup"); EXPEDIO_BACKUP_DIR troca a pasta (usado nos testes do próprio script)
$PastaBackups  = if ($env:EXPEDIO_BACKUP_DIR) { $env:EXPEDIO_BACKUP_DIR } else { Join-Path $HOME '.expedio\backups' }
# Guarda: os $BackupsGuardados mais recentes, o mais recente de cada um dos últimos $DiasComBackup dias e o mais
# recente de cada versão do banco (o de antes de cada migração)
$BackupsGuardados = 10
$DiasComBackup = 14
# Código de saída do "expedio backup" quando o backup foi feito mas a cópia fora do PC não (a aplicação avisa no sino)
$SaidaSemCopiaFora = 3

# Argumentos da JVM e da aplicação (a tarefa roda com a pasta de instalação como diretório de trabalho,
# então o Spring Boot lê config\application.yml sozinho; o servico.yml vem por último e prevalece).
# Memória: até 1 GB (o uso normal fica perto de 250 MB). Se ela acabar, a JVM grava o diagnóstico em
# logs\memoria-esgotada.hprof (só o primeiro: com o arquivo lá, não grava outro, para uma queda em laço não encher
# o disco) e sai; a tarefa agendada sobe de novo em até 5 minutos. Um erro grave da própria JVM vai para
# logs\erro-da-jvm-<pid>.log.
$ArgumentosJava = @(
    '-Xms64m', '-Xmx1g', '-XX:+ExitOnOutOfMemoryError',
    '-XX:+HeapDumpOnOutOfMemoryError', '-XX:HeapDumpPath=logs\memoria-esgotada.hprof',
    '-XX:ErrorFile=logs\erro-da-jvm-%p.log',
    '-Dnet.bytebuddy.experimental=true',   # Hibernate em JDKs mais novos que o suportado pelo Spring Boot 3.5
    '-Dfile.encoding=UTF-8',
    '-jar', 'app\expedio.jar',
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
        Falhar 'O Expedio não está instalado. Na pasta do projeto, rode: .\instalar.cmd'
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
function Compilar([string] $repositorio, [string] $versao) {
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

    # As dependências são reinstaladas quando faltam ou quando a lista delas (package-lock.json) mudou desde a
    # última instalação (o npm grava node_modules\.package-lock.json ao instalar).
    $trava     = Join-Path $frontend 'package-lock.json'
    $instalado = Join-Path $frontend 'node_modules\.package-lock.json'
    $instalar  = -not (Test-Path -LiteralPath $instalado)
    if (-not $instalar -and (Test-Path -LiteralPath $trava)) {
        $instalar = (Get-Item -LiteralPath $trava).LastWriteTimeUtc -gt (Get-Item -LiteralPath $instalado).LastWriteTimeUtc
    }
    if ($instalar) {
        Passo 'Front-end: npm ci (dependências novas ou alteradas)'
        if ((Executar-NoLog $frontend $npm.Source 'ci --no-audit --no-fund') -ne 0) {
            Mostrar-FimDoLog $LogBuild; Falhar "Falhou o npm ci. Log completo: $LogBuild"
        }
    }
    # A versão vai para o rodapé das telas (vite.config.js) e para o jar (build-info, lido por "expedio versao")
    $env:EXPEDIO_VERSAO = $versao
    $env:EXPEDIO_VERSAO_GERADA_EM = (Get-Date).ToString('s')
    Passo 'Front-end: npm run build'
    if ((Executar-NoLog $frontend $npm.Source 'run build') -ne 0) {
        Mostrar-FimDoLog $LogBuild; Falhar "Falhou o build do front-end. Log completo: $LogBuild"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $frontend 'dist\index.html'))) {
        Falhar 'O build do front-end não gerou frontend\dist\index.html.'
    }

    $argumentos = "-B -Papp clean package -Dexpedio.versao=$versao"
    if ($Testes) { Passo 'Back-end: Maven com testes (pode levar alguns minutos)' }
    else { Passo 'Back-end: Maven (sem testes; use -Testes para rodá-los)'; $argumentos += ' -DskipTests' }
    if ((Executar-NoLog $backend $maven $argumentos) -ne 0) {
        Mostrar-FimDoLog $LogBuild 40; Falhar "Falhou o build do back-end. Log completo: $LogBuild"
    }

    $gerado = Join-Path $backend 'target-app\expedio.jar'
    if (-not (Test-Path -LiteralPath $gerado)) { Falhar "O Maven não gerou $gerado." }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($gerado)
    try { $temTelas = [bool] ($zip.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' }) }
    finally { $zip.Dispose() }
    if (-not $temTelas) { Falhar 'O jar gerado não contém o front-end (static/index.html).' }
    return $gerado
}

# ------------------------------------------------------------------------------------------ versão
# A versão do rodapé: tecnologia.ano.número.subversão (ex.: 1.1.001.001), gerada a cada "expedio atualizar".
#   tecnologia  versao.json do projeto; sobe à mão quando muda a geração do Java, do Spring ou do Vue
#   ano         ano do sistema: o anoInicial do versao.json é o 1
#   número      sobe na primeira atualização de cada dia; volta a 001 quando o ano ou a tecnologia muda
#   subversão   sobe a cada atualização no mesmo dia; volta a 001 quando o número sobe
# As geradas ficam em versoes.json (na instalação). Uma versão só conta depois de instalada: compilação que falha
# não gasta número.

function Ler-Versoes {
    if (-not (Test-Path -LiteralPath $ArquivoVersoes)) { return @() }
    $lidas = Get-Content -LiteralPath $ArquivoVersoes -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $lidas -or -not $lidas.historico) { return @() }
    return @($lidas.historico)
}

function Proxima-Versao([string] $repositorio, [datetime] $agora = (Get-Date)) {
    $arquivo = Join-Path $repositorio 'versao.json'
    if (-not (Test-Path -LiteralPath $arquivo)) { Falhar "Falta $arquivo (a tecnologia e o ano inicial da versão)." }
    $base = Get-Content -LiteralPath $arquivo -Raw -Encoding UTF8 | ConvertFrom-Json
    $tecnologia = [int] $base.tecnologia
    $ano = $agora.Year - [int] $base.anoInicial + 1
    if ($tecnologia -lt 1 -or $ano -lt 1) { Falhar "versao.json inválido: tecnologia $tecnologia, ano do sistema $ano." }
    $dia = $agora.ToString('yyyy-MM-dd')

    $numero = 1; $sub = 1
    $ultima = Ler-Versoes | Select-Object -Last 1
    if ($ultima -and [int] $ultima.tecnologia -eq $tecnologia -and [int] $ultima.ano -eq $ano) {
        if ($ultima.dia -eq $dia) { $numero = [int] $ultima.numero; $sub = [int] $ultima.sub + 1 }
        else { $numero = [int] $ultima.numero + 1 }
    }
    return [pscustomobject]@{
        versao     = '{0}.{1}.{2:D3}.{3:D3}' -f $tecnologia, $ano, $numero, $sub
        tecnologia = $tecnologia
        ano        = $ano
        numero     = $numero
        sub        = $sub
        dia        = $dia
    }
}

function Registrar-Versao($versao) {
    $projeto = Versao-Do-Projeto
    $nova = [ordered]@{
        versao     = $versao.versao
        tecnologia = $versao.tecnologia
        ano        = $versao.ano
        numero     = $versao.numero
        sub        = $versao.sub
        dia        = $versao.dia
        geradaEm   = (Get-Date).ToString('s')
        ramo       = if ($projeto) { $projeto.ramo } else { $null }
        commit     = if ($projeto) { $projeto.commit } else { $null }
    }
    $historico = @(Ler-Versoes) + @([pscustomobject] $nova)
    $json = [ordered]@{ historico = $historico } | ConvertTo-Json -Depth 4
    [IO.File]::WriteAllText($ArquivoVersoes, $json, (New-Object Text.UTF8Encoding $false))
}

# A versão gravada dentro de um jar (build-info do Maven); $null se o jar não existe ou é de antes da numeração
function Versao-DoJar([string] $jar) {
    if (-not (Test-Path -LiteralPath $jar)) { return $null }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        # O Spring Boot põe o build-info na raiz do jar (META-INF/); o outro caminho cobre um empacotamento diferente
        $entrada = $zip.Entries |
            Where-Object { $_.FullName -in 'META-INF/build-info.properties', 'BOOT-INF/classes/META-INF/build-info.properties' } |
            Select-Object -First 1
        if (-not $entrada) { return $null }
        $leitor = New-Object IO.StreamReader($entrada.Open())
        try { $texto = $leitor.ReadToEnd() } finally { $leitor.Dispose() }
    } finally { $zip.Dispose() }
    $linha = ($texto -split "`r?`n") | Where-Object { $_ -like 'build.versao=*' } | Select-Object -First 1
    if (-not $linha) { return $null }
    $valor = $linha.Substring('build.versao='.Length).Trim()
    if (-not $valor -or $valor -eq 'desenvolvimento') { return $null }
    return $valor
}

function Cmd-Versao {
    Titulo 'Versão do Expedio'
    $instalada = Versao-DoJar $Jar
    $anterior = Versao-DoJar $JarAnterior
    Info "Instalada:      $(if ($instalada) { $instalada } else { 'sem número (de antes da numeração)' })"
    if (Test-Path -LiteralPath $JarAnterior) {
        Info "Anterior:       $(if ($anterior) { $anterior } else { 'sem número' })  (expedio voltar)"
    }
    $geradas = @(Ler-Versoes)
    if ($geradas.Count -gt 0) {
        Write-Host ''
        Info 'Últimas geradas:'
        foreach ($v in ($geradas | Select-Object -Last 10)) {
            $quando = ([datetime] $v.geradaEm).ToString('dd/MM/yyyy HH:mm')
            $origem = if ($v.commit) { "  $($v.ramo) $($v.commit)" } else { '' }
            Info "  $($v.versao)   $quando$origem"
        }
    }
    $estado = Ler-Estado
    if ($estado -and $estado.repositorio -and (Test-Path -LiteralPath (Join-Path $estado.repositorio 'versao.json'))) {
        Write-Host ''
        Info "Próxima:        $((Proxima-Versao $estado.repositorio).versao)  (no próximo expedio atualizar)"
    }
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
        $achado = Select-String -LiteralPath $ambiente -Pattern "^\s*\`$env:EXPEDIO_JWT_SEGREDO\s*=\s*['""]([^'""]{32,})['""]" |
            Select-Object -First 1
        if ($achado) { return $achado.Matches[0].Groups[1].Value }
    }
    if ($env:EXPEDIO_JWT_SEGREDO -and $env:EXPEDIO_JWT_SEGREDO.Length -ge 32) { return $env:EXPEDIO_JWT_SEGREDO }
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
                '# Depois de editar: expedio reiniciar',
                '#',
                '# expedio:',
                '#   importacao-pdf:',
                '#     # Pasta inicial dos comprovantes do administrador (depois cada usuario escolhe a sua',
                '#     # em "Minha conta"; local, ou de rede com barras normais)',
                '#     diretorio: //servidor/Ponto',
                ''
            ) -join "`r`n"
            [IO.File]::WriteAllText($aplicacao, $modelo, $semBom)
        }
    }

    $segredo = Segredo-Existente $repositorio
    $log = ($ArquivoLog -replace '\\', '/')
    $pastaLogs = ($PastaLogs -replace '\\', '/')
    $servico = @(
        '# Gerado por "expedio instalar" / "expedio atualizar": porta, segredo do login e logs do serviço.',
        '# A configuração da aplicação (pasta dos PDFs, usuários...) fica no application.yml desta pasta.',
        'server:',
        "  port: $porta",
        'expedio:',
        '  seguranca:',
        '    jwt:',
        "      segredo: `"$segredo`"",
        '  # logs por usuário e por hora: <pasta>/usuarios/<login>/<aaaa-mm-dd>/<hh>h.log',
        '  logs:',
        "    diretorio: `"$pastaLogs`"",
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
    foreach ($arquivo in 'expedio.ps1', 'expedio.cmd') {
        $de = Join-Path $origem $arquivo
        $para = Join-Path $PastaBin $arquivo
        if ((Resolve-Path -LiteralPath $de).Path -ne $para) { Copy-Item -LiteralPath $de -Destination $para -Force }
    }
}

function Avisar-MudancaDeAmbiente {
    if (-not ('Expedio.Win32' -as [type])) {
        Add-Type -Namespace Expedio -Name Win32 -MemberDefinition @'
[DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam,
    uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
'@
    }
    $resultado = [UIntPtr]::Zero
    [Expedio.Win32]::SendMessageTimeout([IntPtr] 0xffff, 0x1A, [UIntPtr]::Zero, 'Environment', 2, 3000, [ref] $resultado) | Out-Null
}

# PATH do usuário, preservando entradas com %VARIAVEIS% (REG_EXPAND_SZ)
function Alterar-Path([switch] $Remover) {
    $chave = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Environment', $true)
    try {
        $bruto = [string] $chave.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        # remove também o caminho de versões anteriores (%LOCALAPPDATA%\Expedio\bin)
        $itens = @($bruto -split ';' | Where-Object {
            $_ -and ($_.TrimEnd('\') -ne $PastaBin) -and ($_ -notlike '*\Expedio\bin')
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
    $descricao = "Expedio em http://localhost:$porta (criada por 'expedio instalar'; controle pelo comando 'expedio')."

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
        Where-Object { $_.CommandLine -and $_.CommandLine -like '*app\expedio.jar*' })
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
    Parar-Tunel
    for ($i = 0; $i -lt 30 -and (Obter-Processo); $i++) { Start-Sleep -Milliseconds 500 }
    if (-not $Silencioso) { Ok 'Parado. Não sobe sozinho até "expedio iniciar".' }
}

# ------------------------------------------------------------------------------------------ túnel (Backups de clientes)
# O sistema abre um túnel rápido do cloudflared ("cloudflared tunnel --url http://127.0.0.1:<porta pública>") para o
# navegador do cliente chegar à página de coleta. Parar a aplicação à força deixa o cloudflared vivo: encerra junto.
function Parar-Tunel {
    Get-CimInstance Win32_Process -Filter "Name = 'cloudflared.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*tunnel*--url http://127.0.0.1:*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Achar-Cloudflared {
    $comando = Get-Command cloudflared.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($comando) { return $comando.Source }
    foreach ($c in @("${env:ProgramFiles(x86)}\cloudflared\cloudflared.exe", "$env:ProgramFiles\cloudflared\cloudflared.exe",
            "$env:LOCALAPPDATA\Microsoft\WinGet\Links\cloudflared.exe")) {
        if ($c -and (Test-Path -LiteralPath $c)) { return $c }
    }
    return $null
}

function Cmd-Tunel {
    Titulo 'Túnel dos Backups de clientes (cloudflared)'
    $achado = Achar-Cloudflared
    if ($achado) {
        Ok "cloudflared já instalado: $achado"
    } else {
        if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
            Falhar ('winget não encontrado. Baixe o cloudflared em https://github.com/cloudflare/cloudflared/releases ' +
                'e informe o caminho em backup-clientes.cloudflared (expedio config).')
        }
        Info 'Instalando pelo winget (Cloudflare.cloudflared)...'
        & winget install --id Cloudflare.cloudflared -e --accept-source-agreements --accept-package-agreements
        if (-not (Achar-Cloudflared)) {
            Aviso 'O winget terminou, mas o cloudflared não apareceu. Abra um terminal novo e rode: expedio reiniciar'
            return
        }
        Ok 'cloudflared instalado.'
    }
    if (Get-ScheduledTask -TaskName $NomeTarefa -ErrorAction SilentlyContinue) {
        Info 'Reiniciando o sistema para abrir o túnel...'
        Parar-Aplicacao -Silencioso
        Iniciar-Aplicacao
        Info 'O endereço público aparece em Suporte > Backups de clientes.'
    }
}

function Iniciar-Aplicacao {
    Exigir-Instalacao
    Limpar-ErrosDaJvm
    $porta = Porta-Configurada
    $tarefa = Get-ScheduledTask -TaskName $NomeTarefa
    if ($tarefa.State -eq 'Disabled') { Enable-ScheduledTask -TaskName $NomeTarefa | Out-Null }

    if (Obter-Processo) {
        if ((Responde $porta) -or (Aguardar-Subida $porta)) { Ok "Rodando: http://localhost:$porta"; return }
    } else {
        $dono = Dono-Da-Porta $porta
        if ($dono) {
            Aviso "A porta $porta está ocupada por $($dono.ProcessName) (PID $($dono.Id)) — provavelmente o back-end no Eclipse."
            Aviso 'Pare-o: o Expedio sobe sozinho em até 5 minutos (ou rode "expedio iniciar" de novo).'
            return
        }
        Start-ScheduledTask -TaskName $NomeTarefa
        if (Aguardar-Subida $porta) { Ok "Rodando: http://localhost:$porta"; return }
    }
    Aviso 'A aplicação não respondeu. Fim do log:'
    Mostrar-FimDoLog $ArquivoLog 30
    Aviso 'Para ver o erro completo na tela: expedio parar; expedio console'
    if (Test-Path -LiteralPath $JarAnterior) {
        Aviso 'Se foi depois de uma atualização: "expedio voltar" sobe a versão anterior (README, "Voltar para a versão anterior").'
    }
    exit 1
}

# Uma queda em laço (a tarefa sobe a aplicação de novo a cada 5 minutos) deixaria um erro-da-jvm-<pid>.log por
# tentativa: ficam só os mais recentes. O memoria-esgotada.hprof não sai daqui: quem apaga é quem olhou.
function Limpar-ErrosDaJvm {
    Get-ChildItem -LiteralPath $PastaLogs -Filter 'erro-da-jvm-*.log' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -Skip $ErrosDaJvmGuardados |
        Remove-Item -Force -ErrorAction SilentlyContinue
}

# "expedio voltar": troca a versão instalada pela anterior (e a atual fica como anterior: rodar de novo desfaz).
# O banco não volta junto: se a versão que sai aplicou uma migração, restaure o backup "antes-de-atualizar".
function Cmd-Voltar {
    Exigir-Instalacao
    if (-not (Test-Path -LiteralPath $JarAnterior)) {
        Falhar 'Não há versão anterior guardada (ela é guardada a cada "expedio atualizar", a partir desta versão do comando).'
    }
    Titulo 'Voltando para a versão anterior'
    $quando = (Get-Item -LiteralPath $JarAnterior).LastWriteTime.ToString('dd/MM/yyyy HH:mm')
    $numero = Versao-DoJar $JarAnterior
    Passo "Versão anterior: $(if ($numero) { "$numero, " } else { '' })compilada em $quando"
    Parar-Aplicacao -Silencioso
    $troca = "$Jar.troca"
    Move-Item -LiteralPath $Jar -Destination $troca -Force
    Move-Item -LiteralPath $JarAnterior -Destination $Jar -Force
    Move-Item -LiteralPath $troca -Destination $JarAnterior -Force
    Ok 'Versão trocada. A que saiu ficou guardada: "expedio voltar" de novo desfaz.'
    Aviso ('O banco não volta junto. Se a versão que saiu aplicou uma migração, restaure o backup ' +
           '"antes-de-atualizar" (expedio backup lista; README, seção Backup).')
    Iniciar-Aplicacao
}

# ------------------------------------------------------------------------------------------ backup
# "expedio backup": cópia do banco (pg_dump) e dos arquivos que não estão no banco nem se recuperam de outro
# lugar (as imagens da base de conhecimento e a chave mestra que protege as chaves do Gemini). Cada backup é
# uma pasta <aaaa-mm-dd_hhmmss>[_motivo] com banco.dump e backup.json; ficam os $BackupsGuardados mais
# recentes. Arquivos .dump soltos na pasta (backups feitos à mão) nunca são apagados.

# Valor de uma chave ("spring.datasource.url") num .yml simples: blocos por indentação, sem listas.
function Ler-ValorYaml([string] $arquivo, [string] $chave) {
    if (-not (Test-Path -LiteralPath $arquivo)) { return $null }
    $pilha = New-Object System.Collections.ArrayList
    foreach ($linha in (Get-Content -LiteralPath $arquivo -Encoding UTF8)) {
        if ($linha -match '^\s*(#.*)?$') { continue }
        if ($linha -notmatch '^(?<espacos> *)(?<nome>[A-Za-z0-9_.-]+)\s*:(?<resto>.*)$') { continue }
        $nivel = $Matches['espacos'].Length
        $nome = $Matches['nome']
        $resto = $Matches['resto']
        while ($pilha.Count -gt 0 -and $pilha[$pilha.Count - 1].Nivel -ge $nivel) { $pilha.RemoveAt($pilha.Count - 1) }
        $caminho = (@($pilha | ForEach-Object { $_.Nome }) + $nome) -join '.'
        [void] $pilha.Add([pscustomobject]@{ Nivel = $nivel; Nome = $nome })
        if ($caminho -ne $chave) { continue }
        $valor = ($resto -replace '\s+#.*$', '').Trim()
        if ($valor -match '^"(.*)"$' -or $valor -match "^'(.*)'$") { $valor = $Matches[1] }
        if ($valor) { return $valor }
        return $null
    }
    return $null
}

# "${DB_URL:jdbc:...}" vira a variável de ambiente DB_URL ou, sem ela, o que vem depois dos dois-pontos
function Resolver-Marcador([string] $valor) {
    if (-not $valor) { return $valor }
    $valor = $valor.Replace('${user.home}', $HOME)
    if ($valor -match '^\$\{(?<variavel>[A-Za-z0-9_]+)(?::(?<padrao>.*))?\}$') {
        $doAmbiente = [Environment]::GetEnvironmentVariable($Matches['variavel'])
        if ($doAmbiente) { return $doAmbiente }
        return $Matches['padrao']
    }
    return $valor
}

# Na mesma ordem em que a aplicação instalada lê: servico.yml, config\application.yml e o padrão do projeto
function Configuracao-Servico([string] $chave, [string] $padrao) {
    foreach ($arquivo in @((Join-Path $PastaConfig 'servico.yml'), (Join-Path $PastaConfig 'application.yml'))) {
        $valor = Ler-ValorYaml $arquivo $chave
        if ($valor) { return (Resolver-Marcador $valor) }
    }
    return (Resolver-Marcador $padrao)
}

# EXPEDIO_BANCO_URL, EXPEDIO_BANCO_USUARIO e EXPEDIO_BANCO_SENHA trocam o banco (usados nos testes do próprio script,
# com um PostgreSQL temporário): com eles, o "expedio restaurar" só mexe nesse banco, nunca na aplicação nem nos arquivos.
function Banco-DeTeste { return [bool] $env:EXPEDIO_BANCO_URL }

function Conexao-Banco {
    $url = if (Banco-DeTeste) { $env:EXPEDIO_BANCO_URL } else {
        Configuracao-Servico 'spring.datasource.url' '${DB_URL:jdbc:postgresql://localhost:5432/expedio}' }
    if ($url -notmatch '^jdbc:postgresql://(?<servidor>[^/:?]+)(?::(?<porta>\d+))?/(?<banco>[^?]+)') {
        throw "o endereço do banco não foi reconhecido (spring.datasource.url = $url)."
    }
    $servidor = $Matches['servidor']
    $portaBanco = if ($Matches['porta']) { $Matches['porta'] } else { '5432' }
    $nomeBanco = $Matches['banco']
    $usuarioBanco = if (Banco-DeTeste) { $env:EXPEDIO_BANCO_USUARIO } else { Configuracao-Servico 'spring.datasource.username' '${DB_USER:expedio}' }
    $senhaBanco = if (Banco-DeTeste) { $env:EXPEDIO_BANCO_SENHA } else { Configuracao-Servico 'spring.datasource.password' '${DB_PASSWORD:expedio}' }
    return [pscustomobject]@{
        Servidor = $servidor
        Porta    = $portaBanco
        Banco    = $nomeBanco
        Usuario  = $usuarioBanco
        Senha    = $senhaBanco
    }
}

# Argumentos de conexão comuns do pg_dump, do pg_restore e do psql (a senha vai só pelo ambiente)
function Argumentos-Conexao($banco) {
    return @('--no-password', "--host=$($banco.Servidor)", "--port=$($banco.Porta)", "--username=$($banco.Usuario)")
}

# Quantas tabelas o banco tem (0 = vazio, um PC novo, por exemplo); -1 com a explicação se não conectou
function Tabelas-DoBanco($banco) {
    $psql = Resolver-PgFerramenta 'psql'
    if (-not $psql) { return [pscustomobject]@{ Quantas = -1; Mensagem = 'o psql não foi encontrado' } }
    $consulta = Executar-Pg $psql ((Argumentos-Conexao $banco) + @("--dbname=$($banco.Banco)", '--tuples-only', '--no-align',
        "--command=SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind = 'r' AND n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema'")) $banco.Senha
    $numero = @($consulta.Linhas | Where-Object { $_ -match '^\s*\d+\s*$' } | Select-Object -First 1)
    if ($consulta.Codigo -ne 0 -or $numero.Count -eq 0) { return [pscustomobject]@{ Quantas = -1; Mensagem = $consulta.Mensagem } }
    return [pscustomobject]@{ Quantas = [int] $numero[0].Trim(); Mensagem = '' }
}

# A versão do banco (a última migração aplicada), para guardar o backup de antes de cada migração. $null se não der:
# o backup não depende disso.
function Versao-DoBanco($banco) {
    $psql = Resolver-PgFerramenta 'psql'
    if (-not $psql) { return $null }
    $consulta = Executar-Pg $psql ((Argumentos-Conexao $banco) + @("--dbname=$($banco.Banco)", '--tuples-only',
        '--no-align', '--command=SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1')) $banco.Senha
    $versao = @($consulta.Linhas | Where-Object { $_ -match '^\s*\d+(\.\d+)*\s*$' } | Select-Object -First 1)
    if ($consulta.Codigo -ne 0 -or $versao.Count -eq 0) { return $null }
    return $versao[0].Trim()
}

# pg_dump.exe / pg_restore.exe: EXPEDIO_PG_BIN, o PATH ou a versão mais nova em Arquivos de Programas\PostgreSQL
function Resolver-PgFerramenta([string] $nome) {
    if ($env:EXPEDIO_PG_BIN) {
        $indicado = Join-Path $env:EXPEDIO_PG_BIN "$nome.exe"
        if (Test-Path -LiteralPath $indicado) { return $indicado }
    }
    $noPath = Get-Command "$nome.exe" -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($noPath) { return $noPath.Source }
    foreach ($programas in @($env:ProgramFiles, ${env:ProgramFiles(x86)}) | Where-Object { $_ }) {
        $raizPg = Join-Path $programas 'PostgreSQL'
        if (-not (Test-Path -LiteralPath $raizPg)) { continue }
        $versao = Get-ChildItem -LiteralPath $raizPg -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "bin\$nome.exe") } |
            Sort-Object { $numero = 0.0; [void] [double]::TryParse(($_.Name -replace '[^\d.]', ''), [ref] $numero); $numero } -Descending |
            Select-Object -First 1
        if ($versao) { return (Join-Path $versao.FullName "bin\$nome.exe") }
    }
    return $null
}

# Roda uma ferramenta do PostgreSQL com a senha só no ambiente do processo filho (nunca na linha de comando
# nem na tela). Devolve o código de saída, as linhas de saída e a primeira mensagem de erro.
function Executar-Pg([string] $executavel, [string[]] $argumentos, [string] $senha) {
    $senhaAntes = $env:PGPASSWORD
    $esperaAntes = $env:PGCONNECT_TIMEOUT
    $eap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        if ($senha) { $env:PGPASSWORD = $senha }
        $env:PGCONNECT_TIMEOUT = '10'
        $linhas = @(& $executavel @argumentos 2>&1 | ForEach-Object { "$_" })
        $codigo = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $eap
        if ($null -eq $senhaAntes) { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue } else { $env:PGPASSWORD = $senhaAntes }
        if ($null -eq $esperaAntes) { Remove-Item Env:PGCONNECT_TIMEOUT -ErrorAction SilentlyContinue } else { $env:PGCONNECT_TIMEOUT = $esperaAntes }
    }
    $erro = @($linhas | Where-Object { $_ -match 'error|erro|fatal|could not|n.o foi poss|refused|recusad' } | Select-Object -First 1)
    $mensagem = if ($erro.Count -gt 0) { $erro[0].Trim() } elseif ($linhas.Count -gt 0) { $linhas[-1].Trim() } else { "código de saída $codigo" }
    return [pscustomobject]@{ Codigo = $codigo; Linhas = $linhas; Mensagem = $mensagem }
}

function Normalizar-Motivo([string] $texto) {
    if (-not $texto) { return '' }
    $semAcento = -join ($texto.Normalize([Text.NormalizationForm]::FormD).ToCharArray() |
        Where-Object { [Globalization.CharUnicodeInfo]::GetUnicodeCategory($_) -ne [Globalization.UnicodeCategory]::NonSpacingMark })
    $limpo = ($semAcento.ToLowerInvariant() -replace '[^a-z0-9]+', '-').Trim('-')
    if ($limpo.Length -gt 60) { $limpo = $limpo.Substring(0, 60).TrimEnd('-') }
    return $limpo
}

function Tamanho-Legivel([double] $bytes) {
    if ($bytes -ge 1GB) { return ('{0:N1} GB' -f ($bytes / 1GB)) }
    if ($bytes -ge 1MB) { return ('{0:N1} MB' -f ($bytes / 1MB)) }
    return ('{0:N0} KB' -f [math]::Max(1, [math]::Ceiling($bytes / 1KB)))
}

# Ramo e commit da pasta do projeto (para saber, depois, de que versão era o backup). $null se não der.
function Versao-Do-Projeto {
    $repositorio = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    if (-not ($repositorio -and (Test-Path -LiteralPath (Join-Path $repositorio '.git')))) {
        $estado = Ler-Estado
        $repositorio = if ($estado) { $estado.repositorio } else { $null }
    }
    if (-not $repositorio -or -not (Test-Path -LiteralPath (Join-Path $repositorio '.git'))) { return $null }
    if (-not (Get-Command git.exe -CommandType Application -ErrorAction SilentlyContinue)) { return $null }
    $eap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $ramo = (& git.exe -C $repositorio rev-parse --abbrev-ref HEAD 2>$null) | Select-Object -First 1
        $commit = (& git.exe -C $repositorio rev-parse --short HEAD 2>$null) | Select-Object -First 1
    } finally {
        $ErrorActionPreference = $eap
    }
    if (-not $commit) { return $null }
    return [pscustomobject]@{ ramo = "$ramo".Trim(); commit = "$commit".Trim() }
}

# Backups feitos pelo comando (pastas com backup.json), do mais novo para o mais antigo
function Backups-DoComando {
    if (-not (Test-Path -LiteralPath $PastaBackups)) { return @() }
    return @(Get-ChildItem -LiteralPath $PastaBackups -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.parcial' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'backup.json')) } |
        Sort-Object Name -Descending)
}

function Meta-DoBackup($pasta) {
    try { return (Get-Content -LiteralPath (Join-Path $pasta.FullName 'backup.json') -Raw -Encoding UTF8 | ConvertFrom-Json) }
    catch { return $null }
}

# A versão do banco guardada no backup.json ($null nos backups de antes desta regra)
function Versao-DoBackup($pasta) {
    $meta = Meta-DoBackup $pasta
    if ($meta -and $meta.banco -and $meta.banco.versao) { return [string] $meta.banco.versao }
    return $null
}

# Os nomes dos backups do comando que ficam (os outros são apagados): os $BackupsGuardados mais recentes, o mais
# recente de cada um dos últimos $DiasComBackup dias e o mais recente de cada versão do banco, que é o de antes da
# migração seguinte. $feitos vem do mais novo para o mais antigo (Backups-DoComando).
function Backups-QueFicam([object[]] $feitos, [datetime] $hoje = (Get-Date)) {
    $ficam = @{}
    foreach ($pasta in @($feitos | Select-Object -First $BackupsGuardados)) { $ficam[$pasta.Name] = $true }
    $desde = $hoje.Date.AddDays(-($DiasComBackup - 1)).ToString('yyyy-MM-dd')
    $dias = @{}
    $versoes = @{}
    foreach ($pasta in $feitos) {
        $dia = if ($pasta.Name.Length -ge 10) { $pasta.Name.Substring(0, 10) } else { $pasta.Name }
        if ($dia -ge $desde -and -not $dias.ContainsKey($dia)) { $dias[$dia] = $true; $ficam[$pasta.Name] = $true }
        $versao = Versao-DoBackup $pasta
        if ($versao -and -not $versoes.ContainsKey($versao)) { $versoes[$versao] = $true; $ficam[$pasta.Name] = $true }
    }
    return $ficam
}

# $preservar: nomes que não saem nesta limpeza (o backup que o "expedio restaurar" vai usar, por exemplo)
function Limpar-BackupsAntigos([string[]] $preservar = @()) {
    $feitos = @(Backups-DoComando)
    $ficam = Backups-QueFicam $feitos
    foreach ($antigo in $feitos) {
        if ($ficam.ContainsKey($antigo.Name) -or $preservar -contains $antigo.Name) { continue }
        Remove-Item -LiteralPath $antigo.FullName -Recurse -Force
        Passo "Backup antigo apagado: $($antigo.Name)"
    }
    # restos de um backup interrompido (janela fechada no meio, por exemplo)
    Get-ChildItem -LiteralPath $PastaBackups -Directory -Filter '*.parcial' -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -lt (Get-Date).AddHours(-1) } |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
}

# Faz o backup e devolve onde ele ficou. Qualquer problema vira exceção com a explicação: quem chama decide o
# que dizer (no "expedio atualizar", a atualização é cancelada).
function Fazer-Backup([string] $motivo, [string[]] $preservar = @()) {
    $pgDump = Resolver-PgFerramenta 'pg_dump'
    $pgRestore = Resolver-PgFerramenta 'pg_restore'
    if (-not $pgDump -or -not $pgRestore) {
        throw ('o pg_dump e o pg_restore não foram encontrados. Eles vêm com o PostgreSQL; se estiverem em outra ' +
               'pasta, indique a pasta bin deles na variável de ambiente EXPEDIO_PG_BIN.')
    }
    $banco = Conexao-Banco
    $rotulo = Normalizar-Motivo $motivo
    $nome = Get-Date -Format 'yyyy-MM-dd_HHmmss'
    if ($rotulo) { $nome = "${nome}_$rotulo" }
    New-Item -ItemType Directory -Force -Path $PastaBackups | Out-Null
    $destino = Join-Path $PastaBackups $nome
    $parcial = "$destino.parcial"
    if (Test-Path -LiteralPath $destino) { throw "já existe um backup com este nome: $destino. Tente de novo em um segundo." }
    if (Test-Path -LiteralPath $parcial) { Remove-Item -LiteralPath $parcial -Recurse -Force }
    New-Item -ItemType Directory -Path $parcial | Out-Null
    try {
        Passo "Banco $($banco.Banco) em $($banco.Servidor):$($banco.Porta) (pg_dump)"
        $dump = Join-Path $parcial 'banco.dump'
        $copia = Executar-Pg $pgDump @('--format=custom', '--no-password', "--host=$($banco.Servidor)",
            "--port=$($banco.Porta)", "--username=$($banco.Usuario)", "--file=$dump", $banco.Banco) $banco.Senha
        if ($copia.Codigo -ne 0 -or -not (Test-Path -LiteralPath $dump)) {
            $dica = ''
            if ($copia.Mensagem -match 'refused|recusad|timeout|tempo|could not connect') { $dica = ' Confira se o PostgreSQL está rodando.' }
            elseif ($copia.Mensagem -match 'password|senha|autentica') { $dica = ' Confira o usuário e a senha do banco na configuração (expedio config).' }
            throw "o pg_dump não conseguiu copiar o banco ($($copia.Mensagem)).$dica"
        }
        Passo 'Conferindo se o arquivo pode ser restaurado (pg_restore --list)'
        $conferencia = Executar-Pg $pgRestore @('--list', $dump) $null
        $itens = @($conferencia.Linhas | Where-Object { $_ -and -not $_.StartsWith(';') }).Count
        if ($conferencia.Codigo -ne 0) {
            throw "o arquivo do backup não pôde ser lido pelo pg_restore ($($conferencia.Mensagem))."
        }
        if ($itens -eq 0) { throw "o banco $($banco.Banco) está vazio (nenhuma tabela): não há o que copiar." }

        $arquivos = @()
        # mesmo padrão que a aplicação usa (atendimento.yml), inclusive a variável de ambiente
        $pastaAtendimentos = Configuracao-Servico 'atendimento.armazenamento.diretorio' '${EXPEDIO_ATENDIMENTOS_DIR:${user.home}/.expedio/atendimentos}'
        $base = Join-Path $pastaAtendimentos 'base'
        if (Test-Path -LiteralPath $base -PathType Container) {
            Passo "Imagens da base de conhecimento ($base)"
            Copy-Item -LiteralPath $base -Destination (Join-Path $parcial 'atendimentos-base') -Recurse
            $arquivos += 'atendimentos-base'
        }
        $chaveMestra = Configuracao-Servico 'atendimento.chave-mestra.arquivo' (Join-Path $pastaAtendimentos 'chave-mestra')
        if (Test-Path -LiteralPath $chaveMestra -PathType Leaf) {
            Passo 'Chave mestra das chaves do Gemini'
            Copy-Item -LiteralPath $chaveMestra -Destination (Join-Path $parcial 'chave-mestra')
            $arquivos += 'chave-mestra'
        }

        $tamanho = (Get-ChildItem -LiteralPath $parcial -Recurse -File | Measure-Object Length -Sum).Sum
        $meta = [ordered]@{
            data     = (Get-Date).ToString('s')
            motivo   = $rotulo
            banco    = [ordered]@{ nome = $banco.Banco; servidor = $banco.Servidor; porta = $banco.Porta
                                   versao = (Versao-DoBanco $banco)
                                   itens = $itens; tamanho = (Get-Item -LiteralPath $dump).Length }
            arquivos = @($arquivos)
            tamanho  = $tamanho
            projeto  = (Versao-Do-Projeto)
        }
        [IO.File]::WriteAllText((Join-Path $parcial 'backup.json'), ($meta | ConvertTo-Json -Depth 4),
            (New-Object Text.UTF8Encoding $false))
        Rename-Item -LiteralPath $parcial -NewName $nome
    } catch {
        Remove-Item -LiteralPath $parcial -Recurse -Force -ErrorAction SilentlyContinue
        throw
    }
    Limpar-BackupsAntigos $preservar
    return [pscustomobject]@{ Pasta = $destino; Tamanho = $tamanho; Itens = $itens; Arquivos = @($arquivos) }
}

# Robocopy sem lista de arquivos na tela. Devolve $null se deu certo (códigos 0 a 7) ou a explicação.
function Executar-Robocopy([string] $origem, [string] $destino, [string[]] $opcoes) {
    $eap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $linhas = @(& robocopy.exe $origem $destino @opcoes '/R:1' '/W:2' '/NP' '/NFL' '/NDL' '/NJH' '/NJS' 2>&1 | ForEach-Object { "$_".Trim() } | Where-Object { $_ })
        $codigo = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $eap
    }
    if ($codigo -lt 8) { return $null }
    $erro = @($linhas | Where-Object { $_ -match 'ERRO|ERROR' } | Select-Object -First 1)
    return $(if ($erro.Count -gt 0) { $erro[0] } else { "robocopy terminou com o código $codigo" })
}

# Cópia fora do PC (expedio.backup.copia.pasta, na configuração): a pasta dos backups espelhada em
# <pasta>\Expedio\backups (mesma guarda: o que sai daqui sai de lá) e os PDFs dos comprovantes somados em
# <pasta>\Expedio\comprovantes (lá nada é apagado). Devolve $null se copiou ou se a cópia está desligada;
# senão, o motivo (o backup no PC vale do mesmo jeito, e a próxima cópia leva tudo o que faltou).
function Copiar-ForaDoPc {
    $destino = Configuracao-Servico 'expedio.backup.copia.pasta' '${EXPEDIO_BACKUP_COPIA:}'
    if (-not $destino -or -not $destino.Trim()) { return $null }
    $destino = $destino.Trim().Trim('"')
    if (-not (Test-Path -LiteralPath $destino -PathType Container)) {
        return "a pasta $destino não está acessível (o disco ou o pen drive está ligado?)."
    }
    if (-not (Get-Command robocopy.exe -CommandType Application -ErrorAction SilentlyContinue)) {
        return 'o robocopy do Windows não foi encontrado.'
    }
    $raiz = Join-Path $destino 'Expedio'
    Passo "Cópia fora do PC: $raiz"
    $falha = Executar-Robocopy $PastaBackups (Join-Path $raiz 'backups') @('/MIR', '/XD', '*.parcial')
    if ($falha) { return "os backups não foram copiados para $raiz ($falha)." }
    $comprovantes = Configuracao-Servico 'expedio.armazenamento.diretorio' '${EXPEDIO_ARMAZENAMENTO_DIR:${user.home}/.expedio/comprovantes}'
    if ($comprovantes) { $comprovantes = [IO.Path]::GetFullPath($comprovantes) }
    if ($comprovantes -and (Test-Path -LiteralPath $comprovantes -PathType Container)) {
        $falha = Executar-Robocopy $comprovantes (Join-Path $raiz 'comprovantes') @('/E', '/XO')
        if ($falha) { return "os PDFs dos comprovantes não foram copiados para $raiz ($falha)." }
    }
    return $null
}

function Cmd-ListarBackups {
    Titulo "Backups em $PastaBackups"
    $feitos = @(Backups-DoComando)
    if ($feitos.Count -eq 0) { Info 'Nenhum backup feito pelo comando ainda. Para fazer um agora: expedio backup' }
    foreach ($pasta in $feitos) {
        try {
            $meta = Get-Content -LiteralPath (Join-Path $pasta.FullName 'backup.json') -Raw -Encoding UTF8 | ConvertFrom-Json
            $quando = ([datetime] $meta.data).ToString('dd/MM/yyyy HH:mm')
            $extras = if (@($meta.arquivos).Count -gt 0) { " + $(@($meta.arquivos) -join ', ')" } else { '' }
            $versao = if ($meta.projeto) { "  ($($meta.projeto.ramo) $($meta.projeto.commit))" } else { '' }
            $banco = if ($meta.banco -and $meta.banco.versao) { "  [banco V$($meta.banco.versao)]" } else { '' }
            Info ('{0}  {1,9}  {2}{3}{4}{5}' -f $quando, (Tamanho-Legivel $meta.tamanho), $pasta.Name, $extras, $banco, $versao)
        } catch {
            Aviso "$($pasta.Name): backup.json ilegível"
        }
    }
    $manuais = @(Get-ChildItem -LiteralPath $PastaBackups -File -Filter '*.dump' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending)
    if ($manuais.Count -gt 0) {
        Write-Host ''
        Info 'Feitos à mão (arquivos .dump soltos na pasta; o comando nunca os apaga):'
        foreach ($arquivo in $manuais) {
            Info ('{0}  {1,9}  {2}' -f $arquivo.LastWriteTime.ToString('dd/MM/yyyy HH:mm'), (Tamanho-Legivel $arquivo.Length), $arquivo.Name)
        }
    }
    Write-Host ''
    Info ("Ficam os $BackupsGuardados mais recentes, o mais recente de cada um dos últimos $DiasComBackup dias e o " +
          'mais recente de cada versão do banco (o de antes de cada migração).')
    $copia = Configuracao-Servico 'expedio.backup.copia.pasta' '${EXPEDIO_BACKUP_COPIA:}'
    if ($copia -and $copia.Trim()) {
        $raizCopia = Join-Path ($copia.Trim().Trim('"')) 'Expedio'
        Info "Cópia fora do PC: $raizCopia"
    } else {
        Info 'Cópia fora do PC: desligada (expedio.backup.copia.pasta na configuração; README, seção Backup).'
    }
    Info 'Para voltar a um deles: expedio restaurar'
}

# Código de saída: 0 = feito; $SaidaSemCopiaFora = feito, sem a cópia fora do PC; 1 = não feito (a aplicação, que
# roda o "expedio backup diario" todo dia, usa o código para avisar no sino).
function Cmd-Backup([string] $alvo) {
    if ($alvo -in 'lista', 'listar') { Cmd-ListarBackups; exit 0 }
    Titulo 'Backup do Expedio'
    try { $feito = Fazer-Backup $alvo }
    catch { Falhar "Backup não feito: $($_.Exception.Message)" }
    $conteudo = 'banco'
    if ($feito.Arquivos.Count -gt 0) { $conteudo += ' + ' + ($feito.Arquivos -join ', ') }
    Ok "Backup feito: $($feito.Pasta)"
    Info "Conteúdo: $conteudo · $(Tamanho-Legivel $feito.Tamanho) · $($feito.Itens) itens conferidos pelo pg_restore"
    $semCopia = Copiar-ForaDoPc
    if ($semCopia) {
        Aviso "Cópia fora do PC não feita: $semCopia"
        exit $SaidaSemCopiaFora
    }
    Info 'Todos os backups: expedio backup lista'
    exit 0
}

# ------------------------------------------------------------------------------------------ restaurar
# "expedio restaurar [pasta]": o banco volta a ser exatamente o do backup (o que foi feito depois se perde). Antes, um
# backup de agora ("antes-de-restaurar"), que desfaz a restauração se for preciso. O banco é esvaziado e restaurado
# numa transação só (psql --single-transaction): se algo falhar no meio, nada muda. As imagens da base e a chave
# mestra do backup voltam junto; as de agora ficam ao lado, com o sufixo .antes-de-restaurar-<data>.

# O backup pedido (nome na pasta dos backups ou caminho, como o da cópia fora do PC) ou o escolhido numa lista
function Escolher-Backup([string] $alvo) {
    if ($alvo) {
        $caminho = if (Test-Path -LiteralPath $alvo -PathType Container) { (Resolve-Path -LiteralPath $alvo).Path }
                   else { Join-Path $PastaBackups $alvo }
        if (-not (Test-Path -LiteralPath (Join-Path $caminho 'banco.dump') -PathType Leaf)) {
            Falhar "Não há backup em $caminho (falta o banco.dump). Os backups: expedio backup lista"
        }
        return (Get-Item -LiteralPath $caminho)
    }
    $feitos = @(Backups-DoComando | Select-Object -First 20)
    if ($feitos.Count -eq 0) {
        Falhar 'Nenhum backup feito pelo comando. Para restaurar um .dump feito à mão: README, seção Backup.'
    }
    Info 'Backups, do mais novo para o mais antigo:'
    for ($i = 0; $i -lt $feitos.Count; $i++) {
        $meta = Meta-DoBackup $feitos[$i]
        $quando = if ($meta -and $meta.data) { ([datetime] $meta.data).ToString('dd/MM/yyyy HH:mm') } else { '?' }
        $banco = if ($meta -and $meta.banco -and $meta.banco.versao) { "  [banco V$($meta.banco.versao)]" } else { '' }
        Info ('{0,3}. {1}  {2}{3}' -f ($i + 1), $quando, $feitos[$i].Name, $banco)
    }
    Write-Host ''
    $resposta = Read-Host '  Número do backup (Enter cancela)'
    $numero = 0
    if (-not [int]::TryParse("$resposta".Trim(), [ref] $numero) -or $numero -lt 1 -or $numero -gt $feitos.Count) {
        Info 'Cancelado: nada mudou.'
        exit 0
    }
    return $feitos[$numero - 1]
}

# As imagens da base e a chave mestra do backup no lugar das de agora (que ficam ao lado). O que o backup não
# tem fica como está.
function Restaurar-Arquivos($backup) {
    $pastaAtendimentos = Configuracao-Servico 'atendimento.armazenamento.diretorio' '${EXPEDIO_ATENDIMENTOS_DIR:${user.home}/.expedio/atendimentos}'
    $sufixo = ".antes-de-restaurar-$(Get-Date -Format 'yyyy-MM-dd_HHmmss')"
    $itens = @(
        [pscustomobject]@{ Nome = 'atendimentos-base'; Destino = (Join-Path $pastaAtendimentos 'base'); Rotulo = 'Imagens da base de conhecimento' },
        [pscustomobject]@{ Nome = 'chave-mestra'; Rotulo = 'Chave mestra das chaves do Gemini'
                           Destino = (Configuracao-Servico 'atendimento.chave-mestra.arquivo' (Join-Path $pastaAtendimentos 'chave-mestra')) }
    )
    foreach ($item in $itens) {
        $doBackup = Join-Path $backup.FullName $item.Nome
        if (-not (Test-Path -LiteralPath $doBackup)) {
            Passo "$($item.Rotulo): o backup não tem; a de agora fica"
            continue
        }
        if (Test-Path -LiteralPath $item.Destino) { Rename-Item -LiteralPath $item.Destino -NewName ((Split-Path -Leaf $item.Destino) + $sufixo) }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $item.Destino) | Out-Null
        Copy-Item -LiteralPath $doBackup -Destination $item.Destino -Recurse
        Passo "$($item.Rotulo): voltou a do backup (a de agora ficou com o sufixo $sufixo)"
    }
}

function Cmd-Restaurar([string] $alvo) {
    $teste = Banco-DeTeste
    Titulo 'Restaurar um backup do Expedio'
    if ($teste) {
        Aviso "Banco de teste ($env:EXPEDIO_BANCO_URL): só ele muda; a aplicação e os arquivos não são tocados."
        if (-not $alvo) { Falhar 'No teste, diga qual backup: expedio restaurar <pasta>' }
    } elseif ([Console]::IsInputRedirected) {
        Falhar 'Rode "expedio restaurar" num terminal: ele pergunta qual backup e pede confirmação.'
    }
    $backup = Escolher-Backup $alvo
    $dump = Join-Path $backup.FullName 'banco.dump'
    $pgRestore = Resolver-PgFerramenta 'pg_restore'
    $psql = Resolver-PgFerramenta 'psql'
    if (-not $pgRestore -or -not $psql) {
        Falhar ('O pg_restore e o psql não foram encontrados. Eles vêm com o PostgreSQL; se estiverem em outra ' +
                'pasta, indique a pasta bin deles na variável de ambiente EXPEDIO_PG_BIN.')
    }
    Passo 'Conferindo o backup (pg_restore --list)'
    $lista = Executar-Pg $pgRestore @('--list', $dump) $null
    if ($lista.Codigo -ne 0) { Falhar "Este backup não pode ser lido, nada mudou: $($lista.Mensagem)" }
    $banco = Conexao-Banco
    $meta = Meta-DoBackup $backup
    $quando = if ($meta -and $meta.data) { ([datetime] $meta.data).ToString('dd/MM/yyyy HH:mm') } else { $backup.Name }

    $tabelas = Tabelas-DoBanco $banco
    if ($tabelas.Quantas -lt 0) {
        Falhar "Não deu para conectar ao banco $($banco.Banco) em $($banco.Servidor):$($banco.Porta), nada mudou: $($tabelas.Mensagem)"
    }
    $vazio = $tabelas.Quantas -eq 0
    $versaoDoBackup = if ($meta -and $meta.banco -and $meta.banco.versao) { [string] $meta.banco.versao } else { $null }
    $versaoDeAgora = if ($vazio) { $null } else { Versao-DoBanco $banco }

    Write-Host ''
    Aviso "O banco $($banco.Banco) vai voltar a ser exatamente o do backup de $quando ($($backup.Name))."
    $numeroBackup = 0.0; $numeroAgora = 0.0
    if ($versaoDoBackup -and $versaoDeAgora -and [double]::TryParse($versaoDoBackup, [ref] $numeroBackup) -and
        [double]::TryParse($versaoDeAgora, [ref] $numeroAgora) -and $numeroBackup -lt $numeroAgora) {
        Aviso ("O backup é do banco na V$versaoDoBackup e o de agora está na V${versaoDeAgora}: ao subir, a versão " +
               'instalada aplica de novo as migrações que faltam. Para ficar na versão antiga do sistema, cancele e rode ' +
               '"expedio voltar" antes.')
    }
    if ($vazio) { Aviso 'O banco de agora está vazio (um PC novo, por exemplo): não há o que guardar antes.' }
    else { Aviso 'Tudo o que foi feito depois disso se perde. Antes, é feito um backup de agora ("antes-de-restaurar").' }
    if (-not $teste) { Aviso 'A aplicação para durante a restauração e sobe de novo no fim.' }
    if ($teste) {
        if ($env:EXPEDIO_RESTAURAR_CONFIRMACAO -ne $backup.Name) { Falhar 'Teste sem a confirmação (EXPEDIO_RESTAURAR_CONFIRMACAO = nome do backup).' }
    } else {
        $digitado = Read-Host '  Para confirmar, digite RESTAURAR (Enter cancela)'
        if ("$digitado".Trim() -cne 'RESTAURAR') { Info 'Cancelado: nada mudou.'; exit 0 }
    }

    $antes = $null
    if (-not $vazio) {
        Passo 'Backup de agora'
        try { $antes = Fazer-Backup 'antes-de-restaurar' @($backup.Name) }
        catch { Falhar "O backup de agora não foi feito, então nada mudou: $($_.Exception.Message)" }
        Passo "Backup feito: $($antes.Pasta)"
    }

    if (-not $teste) {
        Passo 'Parando a aplicação'
        Parar-Aplicacao -Silencioso
    }
    $carimbo = Get-Date -Format 'yyyyMMdd_HHmmss'
    $doBackup = Join-Path $env:TEMP "expedio-restaurar-$carimbo-backup.sql"
    $inteiro = Join-Path $env:TEMP "expedio-restaurar-$carimbo.sql"
    $falha = $null
    try {
        Passo 'Preparando a restauração (pg_restore)'
        $sql = Executar-Pg $pgRestore @('--no-owner', "--file=$doBackup", $dump) $null
        if ($sql.Codigo -ne 0) { throw "o pg_restore não leu o backup ($($sql.Mensagem))" }
        # o que existe hoje sai (inclusive tabelas e schemas criados depois do backup) e o backup entra, juntos
        $esvaziar = @'
SET client_min_messages = warning;
DO $$
DECLARE s record;
BEGIN
  FOR s IN SELECT nspname FROM pg_namespace WHERE nspname NOT LIKE 'pg\_%' AND nspname <> 'information_schema' LOOP
    EXECUTE format('DROP SCHEMA %I CASCADE', s.nspname);
  END LOOP;
END $$;
CREATE SCHEMA public;

'@
        [IO.File]::WriteAllText($inteiro, $esvaziar, (New-Object Text.UTF8Encoding $false))
        $saida = [IO.File]::Open($inteiro, 'Append', 'Write')
        try {
            $entrada = [IO.File]::OpenRead($doBackup)
            try { $entrada.CopyTo($saida) } finally { $entrada.Dispose() }
        } finally {
            $saida.Dispose()
        }
        Passo "Restaurando o banco $($banco.Banco) numa transação só (psql)"
        $restauracao = Executar-Pg $psql ((Argumentos-Conexao $banco) + @("--dbname=$($banco.Banco)", '--single-transaction',
            '--set=ON_ERROR_STOP=1', '--quiet', "--file=$inteiro")) $banco.Senha
        if ($restauracao.Codigo -ne 0) { throw "o banco não foi restaurado e continua como estava ($($restauracao.Mensagem))" }
    } catch {
        $falha = $_.Exception.Message
    } finally {
        Remove-Item -LiteralPath $doBackup, $inteiro -Force -ErrorAction SilentlyContinue
    }
    if ($falha) {
        if (-not $teste) { Iniciar-Aplicacao }
        $onde = if ($antes) { " O backup de agora ficou em $($antes.Pasta)." } else { '' }
        Falhar "Restauração não feita: $falha.$onde"
    }
    if (-not $teste) {
        try { Restaurar-Arquivos $backup }
        catch { Aviso "O banco foi restaurado, mas as imagens da base ou a chave mestra não: $($_.Exception.Message). Copie-as do backup à mão (README, seção Backup)." }
    }
    Ok "Restaurado: o banco voltou ao backup de $quando."
    if ($antes) { Info "Para desfazer: expedio restaurar $(Split-Path -Leaf $antes.Pasta)" }
    if (-not $teste) { Iniciar-Aplicacao }
    exit 0
}

# ------------------------------------------------------------------------------------------ comandos
# "expedio atualizar" roda o expedio.ps1 instalado; se o do projeto mudou, é ele que faz a atualização (o backup, a
# guarda e a tarefa agendada com os argumentos da JVM seguem a versão que entra, não a que sai).
function Atualizar-PeloScriptDoProjeto([string] $repositorio) {
    if ($env:EXPEDIO_PELO_PROJETO) { return }
    $doProjeto = Join-Path $repositorio 'deploy\windows\expedio.ps1'
    if (-not (Test-Path -LiteralPath $doProjeto)) { return }
    $doProjeto = (Resolve-Path -LiteralPath $doProjeto).Path
    if ($doProjeto -eq $PSCommandPath) { return }
    if ((Get-FileHash -LiteralPath $doProjeto).Hash -eq (Get-FileHash -LiteralPath $PSCommandPath).Hash) { return }
    Passo 'O comando expedio mudou no projeto: a atualização segue pelo novo'
    $env:EXPEDIO_PELO_PROJETO = '1'
    $argumentos = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $doProjeto, 'atualizar')
    if ($Testes) { $argumentos += '-Testes' }
    if ($Porta -gt 0) { $argumentos += @('-Porta', "$Porta") }
    $ErrorActionPreference = 'Continue'
    & powershell.exe @argumentos
    exit $LASTEXITCODE
}

function Cmd-Instalar([switch] $Atualizacao) {
    $repositorio = Resolver-Repositorio
    if ($Atualizacao) { Atualizar-PeloScriptDoProjeto $repositorio }
    $estado = Ler-Estado
    $porta = if ($Porta -gt 0) { $Porta } elseif ($estado -and $estado.porta) { [int] $estado.porta } else { $PortaPadrao }

    if ($Atualizacao) { Titulo "Atualizando o Expedio a partir de $repositorio" }
    else { Titulo "Instalando o Expedio a partir de $repositorio" }

    $java = Resolver-Java
    Passo "Java $($java.Versao): $($java.Javaw)"
    New-Item -ItemType Directory -Force -Path $Raiz, $PastaApp, $PastaLogs | Out-Null
    $versao = Proxima-Versao $repositorio
    Passo "Versão $($versao.versao)"
    $gerado = Compilar $repositorio $versao.versao

    # Já instalada: o banco é copiado antes de a versão nova subir (é ela que aplica as migrações).
    # Sem backup, sem troca de versão.
    if ($Atualizacao -or (Test-Path -LiteralPath $Jar)) {
        Passo 'Backup antes de trocar a versão'
        $versaoNova = Versao-Do-Projeto
        $motivo = if ($versaoNova -and $versaoNova.ramo) { "antes-de-atualizar-$($versaoNova.ramo)" } else { 'antes-de-atualizar' }
        try { $feito = Fazer-Backup $motivo }
        catch {
            Falhar ("Backup não feito: $($_.Exception.Message)`r`n" +
                    '  A atualização foi cancelada e a versão instalada continua a mesma. Resolva e rode "expedio atualizar" de novo.')
        }
        Passo "Backup feito: $($feito.Pasta)"
        $semCopia = Copiar-ForaDoPc
        if ($semCopia) { Aviso "Cópia fora do PC não feita: $semCopia (a atualização segue)" }
    }

    Passo "Instalando em $Raiz"
    Parar-Aplicacao -Silencioso
    # A versão que sai fica guardada ("expedio voltar"), e a nova entra inteira: copiada ao lado e só então trocada,
    # para um disco cheio no meio da cópia não deixar a aplicação sem jar nenhum.
    try {
        if (Test-Path -LiteralPath $Jar) { Copy-Item -LiteralPath $Jar -Destination $JarAnterior -Force }
        $novo = "$Jar.novo"
        Copy-Item -LiteralPath $gerado -Destination $novo -Force
        Move-Item -LiteralPath $novo -Destination $Jar -Force
    } catch {
        Aviso "A versão nova não pôde ser copiada: $($_.Exception.Message)"
        Remove-Item -LiteralPath "$Jar.novo" -Force -ErrorAction SilentlyContinue
        if (Test-Path -LiteralPath $Jar) {
            Aviso 'A versão instalada continua a mesma; subindo de novo.'
            Iniciar-Aplicacao
        }
        exit 1
    }
    # Instalada: o número passa a contar (a próxima atualização continua dele)
    try { Registrar-Versao $versao }
    catch { Aviso "A versão $($versao.versao) foi instalada mas não ficou anotada em ${ArquivoVersoes}: $($_.Exception.Message)" }
    Copiar-Scripts $repositorio
    Preparar-Config $repositorio $porta
    Alterar-Path
    Registrar-Tarefa $java.Javaw $porta
    Salvar-Estado $repositorio $porta $java.Javaw

    Iniciar-Aplicacao
    Titulo 'Pronto.'
    Info "Versão:         $($versao.versao)"
    Info "Endereço:       http://localhost:$porta"
    Info 'Início:         automático quando você entra no Windows (tarefa agendada "Expedio")'
    Info "Configuração:   $(Join-Path $PastaConfig 'application.yml')"
    Info "Logs:           $ArquivoLog"
    Info "Logs por pessoa: $PastaLogsUsuarios  (um arquivo por usuário e por hora)"
    Mostrar-Ffmpeg
    Mostrar-IaLocal
    if (-not $Atualizacao) {
        Write-Host ''
        Info 'Abra um terminal NOVO e use:  expedio  |  expedio abrir  |  expedio logs  |  expedio parar  |  expedio atualizar'
    }
}

# O ffmpeg tira o quadro de cada passo dos vídeos dos atendimentos. É opcional: sem ele, os vídeos são analisados
# e os passos ficam sem imagem. Procura como a aplicação: atendimento.ffmpeg.caminho, o PATH e os lugares do winget.
function Resolver-Ffmpeg {
    $configurado = Configuracao-Servico 'atendimento.ffmpeg.caminho' ''
    $candidatos = @()
    if ($configurado) {
        $configurado = $configurado.Trim('"')
        if (Test-Path -LiteralPath $configurado -PathType Container) {
            $candidatos += (Join-Path $configurado 'ffmpeg.exe'), (Join-Path $configurado 'bin\ffmpeg.exe')
        } else { $candidatos += $configurado }
    } else {
        $comando = Get-Command ffmpeg.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($comando) { $candidatos += $comando.Source }
        if ($env:LOCALAPPDATA) {
            $winget = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet'
            $candidatos += (Join-Path $winget 'Links\ffmpeg.exe')
            $pacotes = Join-Path $winget 'Packages'
            if (Test-Path -LiteralPath $pacotes) {
                $candidatos += @(Get-ChildItem -LiteralPath $pacotes -Filter 'ffmpeg.exe' -Recurse -Depth 4 -File -ErrorAction SilentlyContinue |
                    Where-Object { $_.FullName -match 'ffmpeg' } | Sort-Object FullName | ForEach-Object { $_.FullName })
            }
        }
        if ($env:ProgramFiles) { $candidatos += (Join-Path $env:ProgramFiles 'ffmpeg\bin\ffmpeg.exe') }
        $candidatos += 'C:\ffmpeg\bin\ffmpeg.exe'
    }
    foreach ($candidato in $candidatos) {
        if (-not $candidato -or -not (Test-Path -LiteralPath $candidato -PathType Leaf)) { continue }
        $versao = $null
        try {
            $linha = & $candidato -hide_banner -version 2>$null | Select-Object -First 1
            if ($linha -match '(?i)^ffmpeg version (\S+)') { $versao = $Matches[1] }
        } catch { }
        if ($versao) { return [pscustomobject]@{ Caminho = $candidato; Versao = $versao } }
    }
    return $null
}

function Mostrar-Ffmpeg {
    $ffmpeg = Resolver-Ffmpeg
    if ($ffmpeg) {
        Info "Vídeos:         ffmpeg $($ffmpeg.Versao) ($($ffmpeg.Caminho)): os passos ganham quadros"
    } else {
        Aviso 'Vídeos:         ffmpeg não encontrado: os vídeos são analisados, mas os passos ficam sem quadros.'
        Aviso '                Para instalar:  winget install Gyan.FFmpeg   (o sistema encontra sozinho em até 1 minuto)'
    }
}

# A IA local (o padrão de atendimento.ia.motor): o Ollama roda o modelo neste computador e o Whisper transcreve os
# áudios. Sem eles, os atendimentos ficam esperando, com o motivo na tela. Só mostra; não instala nada.
function Mostrar-IaLocal {
    $motor = Configuracao-Servico 'atendimento.ia.motor' 'local'
    if ($motor -and $motor.Trim('"') -ne 'local') {
        Info "IA:             $($motor.Trim('"')) (atendimento.ia.motor)"
        return
    }
    $url = (Configuracao-Servico 'atendimento.ia.local.url' 'http://localhost:11434').Trim('"').TrimEnd('/')
    $modelo = (Configuracao-Servico 'atendimento.ia.local.modelo' 'gemma4:12b').Trim('"')
    $vetor = (Configuracao-Servico 'atendimento.ia.local.modelo-vetor' 'embeddinggemma').Trim('"')
    $pasta = Join-Path $env:USERPROFILE '.expedio\ia-local'
    $whisper = (Configuracao-Servico 'atendimento.ia.local.whisper.executavel' '').Trim('"')
    if (-not $whisper) { $whisper = Join-Path $pasta 'whisper\Release\whisper-cli.exe' }
    $modeloWhisper = (Configuracao-Servico 'atendimento.ia.local.whisper.modelo' '').Trim('"')
    if (-not $modeloWhisper) { $modeloWhisper = Join-Path $pasta 'modelos\ggml-large-v3-turbo.bin' }
    $instalados = $null
    try {
        $instalados = @((Invoke-RestMethod -Uri "$url/api/tags" -TimeoutSec 5).models | ForEach-Object { $_.name })
    } catch { }
    if ($null -eq $instalados) {
        Aviso "IA local:       o Ollama não respondeu em $url. Abra o Ollama (menu Iniciar); os atendimentos esperam por ele."
    } else {
        $faltam = @($modelo, $vetor | Where-Object { $nome = $_; -not ($instalados | Where-Object { $_ -eq $nome -or $_ -eq "$($nome):latest" }) })
        if ($faltam.Count -eq 0) {
            Info "IA local:       Ollama com $modelo e $vetor"
        } else {
            Aviso "IA local:       falta(m) no Ollama: $($faltam -join ', ')  (ollama pull <modelo>)"
        }
    }
    if ((Test-Path -LiteralPath $whisper -PathType Leaf) -and (Test-Path -LiteralPath $modeloWhisper -PathType Leaf)) {
        Info "Áudios:         Whisper ($whisper)"
    } else {
        Aviso "Áudios:         Whisper não encontrado ($whisper e $modeloWhisper): os áudios e a fala dos vídeos ficam sem transcrição."
    }
}

# O último backup e o automático (a aplicação faz um por dia, depois de expedio.backup.horario)
function Mostrar-Backup {
    $ultimo = @(Backups-DoComando | Select-Object -First 1)
    $automatico = Configuracao-Servico 'expedio.backup.automatico' 'true'
    $horario = Configuracao-Servico 'expedio.backup.horario' '12:15'
    $quando = if ($automatico -eq 'false') { 'automático desligado (expedio.backup.automatico)' } else { "automático todo dia depois das $horario" }
    if ($ultimo.Count -eq 0) { Aviso "Backup:         nenhum ainda ($quando)"; return }
    $meta = Meta-DoBackup $ultimo[0]
    $data = if ($meta -and $meta.data) { [datetime] $meta.data } else { $ultimo[0].LastWriteTime }
    $texto = "Backup:         último em $($data.ToString('dd/MM HH:mm')) ($($ultimo[0].Name)); $quando"
    if ($automatico -ne 'false' -and $data -lt (Get-Date).AddDays(-2)) { Aviso "$texto. Há mais de 2 dias sem backup: rode expedio backup" }
    else { Info $texto }
}

# Só aparece quando há o que ver. O .hprof é a memória da aplicação no momento da queda (tem dados do ponto e dos
# atendimentos): não envie para ninguém e apague depois de olhar, porque enquanto ele existir o próximo não é gravado.
function Mostrar-Diagnostico {
    $dump = Get-Item -LiteralPath $ArquivoMemoriaEsgotada -ErrorAction SilentlyContinue
    if ($dump) {
        Aviso ('Memória:        acabou em {0}; o diagnóstico ({1:N0} MB) está em {2}' -f
            $dump.LastWriteTime.ToString('dd/MM HH:mm'), ($dump.Length / 1MB), $dump.FullName)
        Aviso '                Ele tem dados da aplicação: não envie para ninguém. Apague depois de olhar (senão o próximo não é gravado).'
    }
    $erros = @(Get-ChildItem -LiteralPath $PastaLogs -Filter 'erro-da-jvm-*.log' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending)
    if ($erros.Count -gt 0) {
        Aviso ('Erro da JVM:    {0} arquivo(s); o último em {1}: {2}' -f
            $erros.Count, $erros[0].LastWriteTime.ToString('dd/MM HH:mm'), $erros[0].FullName)
    }
}

function Cmd-Status {
    Titulo 'Expedio'
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
        Aviso 'Situação:       parada (expedio iniciar para subir)'
    } else {
        $info = Get-ScheduledTaskInfo -TaskName $NomeTarefa
        Aviso "Situação:       parada; tenta subir de novo a cada 5 min (última saída: código $($info.LastTaskResult))"
        $dono = Dono-Da-Porta $porta
        if ($dono) { Aviso "                a porta $porta está ocupada por $($dono.ProcessName) (PID $($dono.Id)) — Eclipse?" }
    }
    Info "Endereço:       http://localhost:$porta"
    $instalada = Versao-DoJar $Jar
    if ($instalada) { Info "Versão:         $instalada  (expedio versao: as anteriores)" }
    $automatico =if ($tarefa.State -eq 'Disabled') { 'desligado (expedio iniciar religa)' } else { 'no logon do Windows' }
    Info "Início:         $automatico"
    Info "Configuração:   $(Join-Path $PastaConfig 'application.yml')"
    Info "Logs:           $ArquivoLog"
    $estado = Ler-Estado
    if ($estado -and $estado.repositorio) { Info "Projeto:        $($estado.repositorio)" }
    Mostrar-Diagnostico
    Mostrar-Backup
    Mostrar-Ffmpeg
    Mostrar-IaLocal
    Write-Host ''
    Info 'Comandos: expedio abrir | iniciar | parar | reiniciar | logs [usuario] [-Erros] | config | atualizar [-Testes] | backup [lista] | restaurar | console | desinstalar'
}

# Uma linha do log geral:
#   2026-10-02T22:00:58.300-03:00  INFO 3511 --- [expedio] [  thread] [maria|K7M2QX] acesso  : GET ... -> 200
# vira, na tela:   22:00:58 INFO  maria K7M2QX acesso  GET ... -> 200     (colorida pela gravidade)
$PadraoLinhaLog = '^\d{4}-\d\d-\d\dT(?<hora>\d\d:\d\d:\d\d)\S*\s+(?<nivel>[A-Z]+)\s+\d+\s+---\s+(?:\[[^\]]*\]\s+)*\[(?<usuario>[^|\]]*)\|(?<protocolo>[^\]]*)\]\s+(?<origem>\S+)\s*:\s?(?<mensagem>.*)$'

function Escrever-LinhaDeLog([string] $linha, [string] $usuario, [bool] $soErros, [ref] $mostrando) {
    $m = [regex]::Match($linha, $PadraoLinhaLog)
    if (-not $m.Success) {
        # continuação da linha anterior (a pilha de um erro): acompanha a decisão tomada para ela
        if ($mostrando.Value) { Write-Host "         $linha" -ForegroundColor DarkGray }
        return
    }
    $nivel = $m.Groups['nivel'].Value
    $quem = $m.Groups['usuario'].Value
    $mostrando.Value = (-not $usuario -or $quem -eq $usuario) -and (-not $soErros -or $nivel -in 'WARN', 'ERROR')
    if (-not $mostrando.Value) { return }
    $cor = switch ($nivel) { 'ERROR' { 'Red' } 'WARN' { 'Yellow' } 'DEBUG' { 'DarkGray' } default { 'Gray' } }
    $rotulo = switch ($nivel) { 'ERROR' { 'ERRO ' } 'WARN' { 'AVISO' } default { $nivel.PadRight(5) } }
    $protocolo = $m.Groups['protocolo'].Value
    $origem = ($m.Groups['origem'].Value -split '\.')[-1]
    Write-Host "$($m.Groups['hora'].Value) " -NoNewline -ForegroundColor DarkGray
    Write-Host "$rotulo " -NoNewline -ForegroundColor $cor
    Write-Host "$($quem.PadRight(12)) " -NoNewline -ForegroundColor Cyan
    if ($protocolo -and $protocolo -ne '-') { Write-Host "$protocolo " -NoNewline -ForegroundColor DarkYellow }
    Write-Host "$origem " -NoNewline -ForegroundColor DarkGray
    Write-Host $m.Groups['mensagem'].Value -ForegroundColor $cor
}

function Cmd-Logs([string] $alvo, [bool] $soErros) {
    if ($alvo -in 'pasta', 'abrir', 'arquivos') {
        if (-not (Test-Path -LiteralPath $PastaLogsUsuarios)) { Falhar "Ainda não há logs por usuário em $PastaLogsUsuarios." }
        Info "$PastaLogsUsuarios  (uma pasta por usuário; dentro, uma por dia; dentro, um arquivo por hora)"
        Start-Process explorer.exe -ArgumentList "`"$PastaLogsUsuarios`""
        return
    }
    if (-not (Test-Path -LiteralPath $ArquivoLog)) { Falhar "Ainda não há log em $ArquivoLog." }
    $usuario = $alvo.Trim().ToLowerInvariant()
    $filtro = @()
    if ($usuario) { $filtro += "só de `"$usuario`"" }
    if ($soErros) { $filtro += 'só avisos e erros' }
    $descricao = if ($filtro) { $filtro -join ', ' } else { 'tudo' }
    Info "Log ao vivo ($descricao). Ctrl+C para sair."
    Info 'Colunas: hora, gravidade, usuário, protocolo, origem, mensagem.  Outros filtros: expedio logs <usuario> [-Erros] | expedio logs pasta'
    if ($usuario -and -not (Test-Path -LiteralPath (Join-Path $PastaLogsUsuarios $usuario))) {
        Aviso "Ainda não há nenhuma linha de `"$usuario`" guardada (confira o login). Aguardando..."
    }
    Write-Host ''
    $mostrando = $false
    # com filtro, as últimas linhas do arquivo podem não ter nada dele: olha mais para trás
    $ultimas = if ($usuario -or $soErros) { 2000 } else { 60 }
    Get-Content -LiteralPath $ArquivoLog -Tail $ultimas -Wait -Encoding UTF8 | ForEach-Object {
        Escrever-LinhaDeLog $_ $usuario $soErros ([ref] $mostrando)
    }
}

function Cmd-Console {
    Exigir-Instalacao
    $porta = Porta-Configurada
    if (Obter-Processo) { Falhar 'A aplicação já está rodando em segundo plano. Primeiro: expedio parar' }
    $dono = Dono-Da-Porta $porta
    if ($dono) { Falhar "A porta $porta está ocupada por $($dono.ProcessName) (PID $($dono.Id))." }
    $estado = Ler-Estado
    $java = if ($estado -and $estado.javaw) { Join-Path (Split-Path -Parent $estado.javaw) 'java.exe' } else { (Resolver-Java).Java }
    Info "Rodando em primeiro plano (Ctrl+C para parar). Depois: expedio iniciar"
    Push-Location -LiteralPath $Raiz
    try {
        $ErrorActionPreference = 'Continue'
        & $java @($ArgumentosJava + '--logging.threshold.console=INFO')
    } finally {
        Pop-Location
    }
}

function Cmd-Desinstalar {
    Titulo 'Removendo o Expedio'
    Parar-Aplicacao -Silencioso
    if (Get-ScheduledTask -TaskName $NomeTarefa -ErrorAction SilentlyContinue) {
        Unregister-ScheduledTask -TaskName $NomeTarefa -Confirm:$false
    }
    Alterar-Path -Remover
    Ok 'Tarefa agendada e comando "expedio" removidos.'
    Info "Mantidos: $Raiz (configuração e logs), o banco de dados e os PDFs arquivados."
}

switch ($Comando.ToLowerInvariant()) {
    'instalar'    { Cmd-Instalar }
    'atualizar'   { Cmd-Instalar -Atualizacao }
    'voltar'      { Cmd-Voltar }
    'status'      { Cmd-Status }
    'situacao'    { Cmd-Status }
    'iniciar'     { Iniciar-Aplicacao }
    'parar'       { Exigir-Instalacao; Parar-Aplicacao }
    'reiniciar'   { Exigir-Instalacao; Parar-Aplicacao -Silencioso; Iniciar-Aplicacao }
    'logs'        { Cmd-Logs $Alvo $Erros.IsPresent }
    'log'         { Cmd-Logs $Alvo $Erros.IsPresent }
    'abrir'       { Exigir-Instalacao; Start-Process "http://localhost:$(Porta-Configurada)/" }
    'config'      {
        Exigir-Instalacao
        Start-Process notepad.exe -ArgumentList "`"$(Join-Path $PastaConfig 'application.yml')`""
        Info 'Depois de salvar: expedio reiniciar'
    }
    'console'     { Cmd-Console }
    'backup'      { Cmd-Backup $Alvo }
    'restaurar'   { Cmd-Restaurar $Alvo }
    'tunel'       { Cmd-Tunel }
    'versao'      { Cmd-Versao }
    'desinstalar' { Cmd-Desinstalar }
    'path'        { Alterar-Path; Ok "$PastaBin está no PATH do usuário (vale para terminais novos)." }
    { $_ -in 'ajuda', 'help', '-h', '/?', '--help' } { Get-Help $PSCommandPath -Detailed }
    default       { Aviso "Comando desconhecido: $Comando"; Cmd-Status; exit 1 }
}
