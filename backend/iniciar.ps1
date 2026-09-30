<#
.SYNOPSIS
  Sobe o back-end da Conferência de Ponto pelo terminal (PowerShell).

.DESCRIPTION
  1. Carrega as variáveis de ambiente de ambiente.local.ps1 (criado na primeira execução,
     a partir de ambiente.exemplo.ps1, com um segredo JWT gerado na hora).
  2. Encontra o Maven: wrapper (se existir .mvn\wrapper\maven-wrapper.properties), mvn no PATH
     ou a distribuição que o wrapper já baixou em ~\.m2\wrapper\dists.
  3. Roda o Spring Boot. Ctrl+C para parar.

.EXAMPLE
  .\iniciar.ps1            # compila e sobe (mvn spring-boot:run)
.EXAMPLE
  .\iniciar.ps1 -Jar       # gera o .jar (sem rodar os testes) e sobe com java -jar
.EXAMPLE
  .\iniciar.ps1 -Testes    # roda os testes
#>
[CmdletBinding()]
param(
    [switch] $Jar,
    [switch] $Testes
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Sair-ComErro([string] $mensagem) {
    Write-Host ''
    Write-Host $mensagem -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------ variáveis de ambiente
$arquivoLocal   = Join-Path $PSScriptRoot 'ambiente.local.ps1'
$arquivoExemplo = Join-Path $PSScriptRoot 'ambiente.exemplo.ps1'

if (-not (Test-Path -LiteralPath $arquivoLocal)) {
    $bytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $modelo = [System.IO.File]::ReadAllText($arquivoExemplo)
    $conteudo = $modelo.Replace('<gerado-na-primeira-execucao>', [Convert]::ToBase64String($bytes))
    # UTF-8 com BOM: o Windows PowerShell 5.1 lê os acentos corretamente
    [System.IO.File]::WriteAllText($arquivoLocal, $conteudo, (New-Object System.Text.UTF8Encoding $true))
    Write-Host 'Criado ambiente.local.ps1 com um segredo JWT novo.' -ForegroundColor Yellow
    Write-Host 'Edite esse arquivo para mudar senhas iniciais, pasta dos PDFs ou banco.' -ForegroundColor Yellow
}
. $arquivoLocal

# ------------------------------------------------------------------ Java e Maven
if (-not (Get-Command java -CommandType Application -ErrorAction SilentlyContinue) -and -not $env:JAVA_HOME) {
    Sair-ComErro 'Java não encontrado. Instale o JDK 17 ou mais novo (ou defina JAVA_HOME).'
}

function Resolver-Maven {
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.mvn\wrapper\maven-wrapper.properties')) {
        return (Join-Path $PSScriptRoot 'mvnw.cmd')
    }
    $noPath = Get-Command mvn -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($noPath) { return $noPath.Source }

    $baixado = Get-ChildItem -Path (Join-Path $HOME '.m2\wrapper\dists') -Recurse -Filter mvn.cmd -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($baixado) { return $baixado.FullName }
    return $null
}

$maven = Resolver-Maven
if (-not $maven) {
    Sair-ComErro (@(
        'Maven não encontrado. Duas saídas:'
        '  a) Instale o Maven e coloque-o no PATH; ou'
        '  b) Crie o arquivo do Maven Wrapper (uma vez só), nesta pasta:'
        '     New-Item -ItemType Directory -Force .mvn\wrapper | Out-Null'
        '     Set-Content .mvn\wrapper\maven-wrapper.properties "wrapperVersion=3.3.2`ndistributionType=only-script`ndistributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip"'
    ) -join [Environment]::NewLine)
}

# ------------------------------------------------------------------ porta livre?
$porta = if ($env:PORT) { [int] $env:PORT } else { 8080 }
if (-not $Testes -and (Get-NetTCPConnection -LocalPort $porta -State Listen -ErrorAction SilentlyContinue)) {
    Sair-ComErro ("A porta $porta já está em uso: o back-end provavelmente já está rodando (no Eclipse?).`n" +
                  "Pare-o antes ou suba em outra porta com  `$env:PORT = '8081'  (o front-end aponta para a 8080).")
}

Write-Host "Maven: $maven" -ForegroundColor DarkGray

# Daqui em diante rodam Maven/Java: avisos que eles escrevem na saída de erro (ex.: Mockito,
# JVM) não podem interromper o script quando a saída é redirecionada (2>&1, ISE, log em arquivo).
$ErrorActionPreference = 'Continue'

# ------------------------------------------------------------------ execução
if ($Testes) {
    & $maven test
}
elseif ($Jar) {
    & $maven -q package -DskipTests
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $arquivoJar = Get-ChildItem -Path (Join-Path $PSScriptRoot 'target') -Filter *.jar | Select-Object -First 1
    Write-Host "Subindo $($arquivoJar.Name) na porta $porta (Ctrl+C para parar)" -ForegroundColor Green
    # Entre aspas: sem elas o PowerShell quebra o argumento no ponto
    & java '-Dnet.bytebuddy.experimental=true' -jar $arquivoJar.FullName
}
else {
    Write-Host "Subindo na porta $porta (Ctrl+C para parar)" -ForegroundColor Green
    & $maven spring-boot:run
}
exit $LASTEXITCODE
