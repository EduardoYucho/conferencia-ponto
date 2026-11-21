# Variáveis de ambiente do back-end para rodar pelo terminal (iniciar.ps1).
#
# Na primeira execução, o iniciar.ps1 copia este arquivo para ambiente.local.ps1 (fora do
# controle de versão) e gera o segredo JWT. Edite o ambiente.local.ps1, não este.

# Assina os tokens de login. Mantenha fixo: se mudar, todos precisam entrar de novo.
# Dica: use o mesmo valor no Eclipse (Run Configurations > Environment).
$env:EXPEDIO_JWT_SEGREDO = '<gerado-na-primeira-execucao>'

# Senhas iniciais: só valem quando o usuário ainda NÃO existe no banco.
# Em branco, a senha é gerada e aparece uma única vez no log.
# $env:EXPEDIO_ADMIN_SENHA  = ''
# $env:EXPEDIO_VIEWER_SENHA = ''

# Pasta dos comprovantes: cada usuário escolhe a sua em "Minha conta". Esta variável só vale na primeira
# subida da versão com vários usuários (vira a pasta do administrador, se ninguém tiver pasta).
# $env:EXPEDIO_PDF_DIR = "$HOME\Downloads\Ponto"

# Onde os PDFs ficam arquivados (padrão: .expedio\comprovantes na pasta do usuário)
# $env:EXPEDIO_ARMAZENAMENTO_DIR = "$HOME\.expedio\comprovantes"

# Logs (padrão: .expedio\logs na pasta do usuário): expedio.log com tudo e, em
# usuarios\<login>\<aaaa-mm-dd>\<hh>h.log, um arquivo por usuário e por hora (guardados por 30 dias)
# $env:EXPEDIO_LOGS_DIR = "$HOME\.expedio\logs"

# Banco (padrão: localhost:5432/expedio, usuário e senha "expedio")
# $env:DB_URL      = 'jdbc:postgresql://localhost:5432/expedio'
# $env:DB_USER     = 'expedio'
# $env:DB_PASSWORD = 'expedio'

# Porta HTTP (padrão 8080; o front-end em desenvolvimento aponta para a 8080)
# $env:PORT = '8080'
