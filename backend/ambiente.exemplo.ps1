# Variáveis de ambiente do back-end para rodar pelo terminal (iniciar.ps1).
#
# Na primeira execução, o iniciar.ps1 copia este arquivo para ambiente.local.ps1 (fora do
# controle de versão) e gera o segredo JWT. Edite o ambiente.local.ps1, não este.

# Assina os tokens de login. Mantenha fixo: se mudar, todos precisam entrar de novo.
# Dica: use o mesmo valor no Eclipse (Run Configurations > Environment).
$env:PONTO_JWT_SEGREDO = '<gerado-na-primeira-execucao>'

# Senhas iniciais: só valem quando o usuário ainda NÃO existe no banco.
# Em branco, a senha é gerada e aparece uma única vez no log.
# $env:PONTO_ADMIN_SENHA  = ''
# $env:PONTO_VIEWER_SENHA = ''

# Pasta monitorada dos comprovantes: prefira definir em backend\config\application.yml, que vale
# também para o Eclipse (e tem prioridade sobre esta variável).
# $env:PONTO_PDF_DIR = "$HOME\Downloads\Ponto"

# Onde os PDFs ficam arquivados (padrão: .conferencia-ponto\comprovantes na pasta do usuário)
# $env:PONTO_ARMAZENAMENTO_DIR = "$HOME\.conferencia-ponto\comprovantes"

# Banco (padrão: localhost:5432/conferencia_ponto, usuário e senha "ponto")
# $env:DB_URL      = 'jdbc:postgresql://localhost:5432/conferencia_ponto'
# $env:DB_USER     = 'ponto'
# $env:DB_PASSWORD = 'ponto'

# Porta HTTP (padrão 8080; o front-end em desenvolvimento aponta para a 8080)
# $env:PORT = '8080'
