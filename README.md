# Conferência de Ponto

Controle de jornada e banco de horas: **Spring Boot 3 + PostgreSQL + Vue 3**.
**Vários usuários**, cada um com o próprio acesso, **horário de trabalho editável** (por dia da semana, com
vigência) e **pasta de comprovantes** (ou envio dos PDFs pela tela). Cálculo **igual ao do sistema de ponto
do RH** (com segundos, tolerância por horário da grade, até 6 batidas; horário padrão 08:00–12:00 e
13:00–17:48 = 08:48), lançamento manual de dias sem expediente/feriados (100% crédito),
**banco de horas semestral** com botão de fechamento e avisos de prazo, **lançamentos no banco** (abater ou
creditar horas), **feriados, férias, folgas, atestados e abonos**,
**importação automática dos comprovantes em PDF** com atualização em tempo real (SSE), **arquivo seguro dos
PDFs**, **acesso por perfil (JWT)** com auditoria para a coordenação, **conciliação com o relatório de
banco de horas do RH** (tela dividida conferência × RH) e **planilha de conferência** — em Excel ou numa
planilha do **Google Sheets atualizada sozinha**, para compartilhar com quem confere.

> **Licença:** [PolyForm Strict 1.0.0](LICENSE) — uso **não comercial** apenas. Veja [Licença](#licença).

```
conferencia-ponto/
├── instalar.cmd                instala como serviço do Windows + comando "ponto" (veja abaixo)
├── deploy/windows/             ponto.ps1 (instalar, iniciar, parar, logs, atualizar...) e ponto.cmd
├── docker-compose.yml          PostgreSQL 16 para desenvolvimento
├── exemplos/comprovantes/      PDFs de teste para a importação automática
├── backend/                    Java 17 · Spring Boot 3.5 · JPA · Flyway · Spring Security
│   ├── iniciar.ps1 / .cmd      sobe o back-end pelo terminal (variáveis em ambiente.local.ps1)
│   └── src/main/java/br/com/conferenciaponto/
│       ├── domain/             regras puras (sem Spring): motor de cálculo, agregado, portas
│       ├── application/        casos de uso (batidas, manual, importação, auditoria, download, login, planilha)
│       └── infrastructure/     JPA, REST/SSE, monitor de PDFs, armazenamento, segurança (JWT), Excel e
│                               Google Sheets
└── frontend/                   Vue 3 · Vite · Pinia · Axios · Tailwind 4
    └── src/
        ├── api/                http.js (Bearer), eventos.js (SSE autenticado), pontoApi, authApi
        ├── stores/             auth.js (sessão, perfil, "dados de") · ponto.js (usePontoStore, tempo real)
        ├── views/              Login · TrocarSenha · Dashboard · Auditoria · Conciliacao · Ausencias ·
        │                       MinhaConta (senha, pasta, horário, planilha) · Usuarios (administrador)
        └── components/         TimelineDiaria, CartaoMensal, EditorHorario, EditorPasta, EditorPlanilha,
                                IntegracaoGoogle, EnvioComprovantes, ...
```

## Como rodar

**1. Banco** (Docker):

```bash
docker compose up -d
```

Sem Docker (ex.: PostgreSQL instalado como serviço no Windows), crie o usuário e o banco uma vez,
conectado como `postgres`:

```sql
CREATE ROLE ponto LOGIN PASSWORD 'ponto';
CREATE DATABASE conferencia_ponto OWNER ponto;
```

Ou ajuste `DB_URL`, `DB_USER` e `DB_PASSWORD`. O Flyway cria as tabelas na primeira subida.

**2. Back-end** (porta 8080) — pelo terminal (PowerShell):

```powershell
cd backend
.\iniciar.ps1            # compila e sobe (mvn spring-boot:run) — Ctrl+C para parar
.\iniciar.ps1 -Jar       # gera o .jar sem rodar os testes e sobe com java -jar
.\iniciar.ps1 -Testes    # 340 testes (domínio, casos de uso, PDFs, monitor, arquivo, RBAC, ajuste, ciclo, conciliação, usuários, horários, planilha, presença, logs, mensagens de erro, migrações num PostgreSQL temporário, isolamento dos módulos)
```

No `cmd`, use `iniciar.cmd` com os mesmos parâmetros. No Linux/macOS: `./mvnw spring-boot:run`.

O script:

- na **primeira execução** cria `backend/ambiente.local.ps1` (fora do Git) a partir de
  `ambiente.exemplo.ps1`, já com um segredo JWT aleatório. É nesse arquivo que ficam as variáveis de
  ambiente do terminal: senhas iniciais, pasta do arquivo, banco e porta;
- procura o Maven nesta ordem: Maven Wrapper (`mvnw.cmd`, se existir
  `.mvn/wrapper/maven-wrapper.properties`), `mvn` no PATH ou a distribuição que o wrapper já baixou em
  `~/.m2/wrapper/dists`;
- avisa e não sobe se a porta já estiver ocupada (ex.: o back-end rodando no Eclipse).

**Configuração desta máquina (Eclipse e terminal):** `backend/config/application.yml`, fora do Git. O
Spring Boot lê esse arquivo sozinho quando o back-end roda a partir da pasta `backend` — o que vale tanto
para o Eclipse quanto para o `iniciar.ps1` — e os valores dele têm prioridade sobre o `application.yml`
do projeto. É o lugar da pasta dos comprovantes (ex.: a pasta de rede, veja abaixo).

**Pelo Eclipse** (*Run › Run Configurations › Java Application*): main class
`br.com.conferenciaponto.ConferenciaPontoApplication`; em *VM arguments*,
`-Dnet.bytebuddy.experimental=true` (necessário em JDKs mais novos que o suportado pelo Spring Boot 3.5,
como o 27 — o Maven lê isso do `pom.xml`, o Eclipse não); em *Environment*, as mesmas variáveis do
`ambiente.local.ps1`. Use o **mesmo** `PONTO_JWT_SEGREDO` nos dois lugares para o login valer em ambos.

Usuários iniciais: `admin` (perfil `ADMIN`) e `coordenacao` (perfil `VIEWER`), criados na primeira subida.
Para usar outros logins e nomes, redefina a lista `ponto.seguranca.usuarios-iniciais` em
`backend/config/application.yml`. As senhas `PONTO_ADMIN_SENHA` e `PONTO_VIEWER_SENHA` só são usadas quando o
usuário ainda não existe no banco. Se não forem definidas, a senha gerada aparece **uma única vez** no log
(`Usuário 'coordenacao' criado com perfis [...]. Senha gerada: ...`). As demais pessoas são cadastradas pelo
administrador na tela **Usuários** (veja [Vários usuários](#vários-usuários)).

> Para usar o `mvnw.cmd` diretamente, crie uma vez o arquivo do Maven Wrapper (a ferramenta que gerou o
> projeto não pode gravar em pastas `.mvn`):
>
> ```powershell
> New-Item -ItemType Directory -Force backend\.mvn\wrapper | Out-Null
> Set-Content backend\.mvn\wrapper\maven-wrapper.properties "wrapperVersion=3.3.2`ndistributionType=only-script`ndistributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip"
> ```

**3. Front-end** (porta 5173, com proxy de `/api` para o 8080):

```bash
cd frontend
npm install
npm run dev
```

Os passos acima são para **desenvolver**. Para só **usar** o sistema, instale como serviço (abaixo).

## Rodar como serviço no Windows (sem abrir a pasta)

Uma vez, na pasta do projeto (precisa de Java 17+, Node.js e Maven — os mesmos do desenvolvimento — e do
PostgreSQL rodando):

```powershell
.\instalar.cmd                 # ou: .\instalar.cmd -Porta 8090   /   .\instalar.cmd -Testes
```

O instalador compila o front-end (`npm run build`) e o back-end com o perfil Maven `app`, que coloca as telas
**dentro do jar** (`mvn -Papp package` → `backend/target-app/conferencia-ponto.jar`): API e telas sobem juntas
num único processo, em **http://localhost:8080**. Depois:

- instala em `%USERPROFILE%\.conferencia-ponto\servico` (`app\`, `config\`, `logs\`, `bin\`), ao lado do arquivo
  dos PDFs (`comprovantes\`);
- copia a configuração desta máquina (`backend/config/application.yml`) para `config\application.yml` e gera
  `config\servico.yml` (porta, o mesmo `PONTO_JWT_SEGREDO` do `ambiente.local.ps1` e o log com rotação);
- cria a tarefa agendada **ConferenciaPonto**, que sobe a aplicação **quando você entra no Windows**, com o seu
  usuário (acessa a pasta de rede com as suas credenciais), sem janela, e a sobe de novo em até 5 minutos se
  ela cair — não precisa de administrador;
- coloca o comando **`ponto`** no PATH do usuário (abra um terminal novo).

| Comando | O que faz |
|---|---|
| `ponto` | Situação: rodando?, endereço, configuração e logs |
| `ponto abrir` | Abre no navegador |
| `ponto iniciar` · `ponto parar` · `ponto reiniciar` | Sobe · para (e não sobe sozinho até `iniciar`) · reinicia |
| `ponto logs` | Log **ao vivo** no terminal, colorido por gravidade (`Ctrl+C` para sair) |
| `ponto logs maria` · `ponto logs -Erros` | Ao vivo só de um usuário (ou `sistema`) · só avisos e erros (combinam: `ponto logs maria -Erros`) |
| `ponto logs pasta` | Abre a pasta dos logs por usuário e por hora |
| `ponto config` | Abre `config\application.yml` (pasta dos PDFs, usuários...); depois, `ponto reiniciar` |
| `ponto atualizar [-Testes]` | Recompila a partir da pasta do projeto, faz o **backup** do banco e troca a versão (a configuração é mantida) |
| `ponto backup [motivo]` · `ponto backup lista` | Backup do banco agora · os backups guardados (veja [Backup](#backup)) |
| `ponto console` | Roda no próprio terminal, com a saída na tela — para diagnosticar um erro de subida |
| `ponto desinstalar` | Remove a tarefa e o comando (mantém configuração, logs, banco e PDFs) |

A porta é a mesma do desenvolvimento de propósito: **não rode o serviço e o Eclipse ao mesmo tempo** (os dois
importariam os mesmos PDFs). Para desenvolver, `ponto parar`; ao terminar, `ponto iniciar`. Se o Eclipse
estiver com a porta, o serviço espera e sobe sozinho quando ela liberar. O front-end em modo de desenvolvimento
(`npm run dev`) funciona com qualquer um dos dois.

## Backup

`ponto backup` copia o banco inteiro (`pg_dump`, formato *custom*) para `%USERPROFILE%\.conferencia-ponto\backups\`,
uma pasta por backup: `<aaaa-mm-dd_hhmmss>[_motivo]\banco.dump` e `backup.json` (data, motivo, tamanho, ramo e
commit do projeto). Antes de terminar, o arquivo é conferido com `pg_restore --list`: um backup que não pode ser
lido não conta como feito.

- `ponto backup` · `ponto backup antes-da-mudanca` (o motivo vai no nome da pasta) · `ponto backup lista`.
- **O `ponto atualizar` faz o backup sozinho**, depois de compilar e antes de trocar a versão (é a versão nova que
  aplica as migrações do banco). Se o backup falhar (PostgreSQL parado, senha errada, disco cheio), a atualização
  é cancelada e a versão instalada continua a mesma.
- Ficam os **10** backups mais recentes feitos pelo comando; os mais antigos são apagados. Arquivos `.dump` soltos
  na pasta (backups feitos à mão) nunca são apagados.
- Junto com o banco vão as imagens da base de conhecimento (`atendimentos\base\`) e a chave mestra que protege as
  chaves do Gemini, quando existirem — por isso, **não compartilhe a pasta de backups**. Os PDFs arquivados
  (`comprovantes\`) não entram: copie essa pasta à parte, se quiser.
- Banco, usuário e senha vêm da configuração do serviço (`spring.datasource.*` ou `DB_URL`, `DB_USER`,
  `DB_PASSWORD`); a senha vai para o `pg_dump` só pelo ambiente do processo (`PGPASSWORD`), nunca na tela. O
  `pg_dump` e o `pg_restore` são procurados em `PONTO_PG_BIN`, no PATH e na versão mais nova em
  `C:\Program Files\PostgreSQL\`.

**Restaurar um backup** — só por decisão sua: o banco volta a ser exatamente o do backup (o que foi feito depois
dele se perde).

```powershell
ponto parar
$env:PGPASSWORD = 'ponto'    # a senha do banco (padrão: ponto)
& 'C:\Program Files\PostgreSQL\18\bin\pg_restore.exe' --clean --if-exists --no-owner --single-transaction `
    --host=localhost --username=ponto --dbname=conferencia_ponto "$HOME\.conferencia-ponto\backups\<pasta>\banco.dump"
Remove-Item Env:PGPASSWORD
ponto iniciar
```

O `--single-transaction` faz a restauração inteira ou nada. Se o backup tiver `atendimentos-base\` e
`chave-mestra`, copie-os de volta para `%USERPROFILE%\.conferencia-ponto\atendimentos\` (`base\` e
`chave-mestra`). Se o backup for de **antes** da migração de um módulo, desfaça o módulo primeiro (abaixo): o
`--clean` só recria o que está no backup e deixaria os schemas do módulo para trás.

**Desfazer a migração de um módulo ainda não aprovado** — apaga só os schemas dos módulos (`atendimento` e
`conhecimento`) e as linhas dessas migrações no histórico do Flyway; nenhuma tabela do ponto é tocada:

```powershell
ponto parar
$env:PGPASSWORD = 'ponto'
& 'C:\Program Files\PostgreSQL\18\bin\psql.exe' --host=localhost --username=ponto --dbname=conferencia_ponto `
    --command "BEGIN; DROP SCHEMA IF EXISTS conhecimento CASCADE; DROP SCHEMA IF EXISTS atendimento CASCADE; DELETE FROM flyway_schema_history WHERE version IN ('14', '15'); COMMIT;"
Remove-Item Env:PGPASSWORD
```

(troque `'14', '15'` pelas versões da migração desfeita). Depois, instale a versão sem a migração, ou com ela
corrigida (`ponto atualizar`). As extensões `unaccent` e `pg_trgm`, se já tiverem sido criadas, ficam: não
atrapalham o ponto e são reaproveitadas.

## Vários usuários

Cada pessoa tem o próprio acesso e os próprios dados: batidas, horário, banco de horas (ciclo), ausências,
lançamentos, notificações, relatórios do RH e divergências. **Feriados valem para todos.**

- **Cadastro (administrador, tela *Usuários*):** nome, login, perfil e uma **senha provisória** (gerada na
  tela). No primeiro acesso a pessoa cria a própria senha. Usuários não são excluídos — são **desativados**
  (não entram mais, a pasta deixa de ser monitorada e o histórico fica). Sempre sobra um administrador ativo.
- **Perfis:** *Usuário* registra e confere o próprio ponto; *Administrador* também cadastra usuários e
  feriados e consulta o ponto de todos; *Coordenação* só consulta o ponto de todos.
- **"Dados de" (administrador e coordenação):** escolhe de quem são os dados em tela. Vendo outra pessoa,
  todas as telas ficam **somente leitura** — ninguém altera o ponto de outra pessoa. Na API, as leituras
  aceitam `?usuario=login`; as alterações são sempre do usuário do token.
- **Horário de trabalho (*Minha conta*):** cada dia da semana com até 3 períodos (dia sem período = sem
  expediente, todo o tempo é crédito) e a tolerância por marcação. Mudar o horário vale **a partir de uma
  data**: os dias dali em diante são recalculados e os anteriores continuam com o horário que valia para eles.
  Quem trabalha de terça a sábado, por exemplo, tem o sábado como dia útil. O próprio usuário ou o
  administrador alteram; o horário não muda dentro de um ciclo do banco já fechado.
- **Comprovantes (*Minha conta*):** cada pessoa escolhe a **pasta** onde os PDFs chegam — no computador onde
  o sistema roda, uma pasta compartilhada na rede (`\\NOME-DO-PC\Ponto`) ou uma pasta sincronizada da
  nuvem. O botão *Testar acesso* confere se o servidor enxerga a pasta, e duas pessoas não podem usar a mesma.
  Também dá para **enviar os PDFs pela tela** (botão *Enviar PDFs* no painel ou arrastando os arquivos para a
  página), com as mesmas regras de duplicidade.
- **Atualização a partir da versão de um usuário só:** a migração `V12` passa todos os dados existentes para
  o primeiro administrador, grava o horário padrão para ele e a pasta de `ponto.importacao-pdf.diretorio`
  passa a ser a pasta dele (só na primeira subida, se ninguém tiver pasta). Os saldos não mudam.

## Equipe agora (quem está online)

A tela **Equipe** (`/equipe`, para todos os perfis) lista **todos os usuários ativos** e diz quem está
trabalhando agora. A regra fica no servidor (`ConsultarPresencaUseCase`); a tela só mostra:

| Situação hoje | Aparece como |
|---|---|
| Dia marcado como férias, folga, licença, atestado ou abono | **Offline** · o motivo (e "até dd/mm"), mesmo que haja batida |
| Entrada batida e saída ainda não | **Online** · "Trabalhando desde 08:02" (avisa quando é fora do horário cadastrado) |
| Saída batida e o horário ainda tem período pela frente | Offline · "Em intervalo desde 12:01" |
| Saída do último período (ou depois do fim do horário) | Offline · "Encerrou o expediente às 17:50" |
| Sem batida: feriado · dia sem expediente · antes do horário | Offline · "Feriado: …" · "Sem expediente hoje" · "O expediente começa às 08:00" |
| Sem batida com o horário já começado · já terminado | Offline, em destaque · "Ainda não bateu o ponto" · "Não registrou ponto hoje" |
| Coordenação (perfil `VIEWER`) | Offline · "não registra ponto" |

Todos veem a situação de todos. O detalhe é reservado: para colegas, **atestado e abono aparecem só como
"Ausência justificada"**; a justificativa, as batidas e o atalho "ver o ponto de…" ficam com o administrador, a
coordenação e a própria pessoa. A tela se atualiza sozinha quando alguém bate o ponto (evento
`presenca-atualizada`, que não carrega dado nenhum) e a cada minuto. Quem importa o ponto por comprovante (PDF)
aparece online assim que o comprovante da entrada chega ao sistema.

## Atendimentos e base de conhecimento (em construção)

Dois módulos que vivem dentro do sistema sem fazer parte do ponto: o **gerador de textos de atendimento** (lê o
PDF da conversa do Digisac, analisa os anexos com o Gemini e redige o resumo ou o chamado para o
desenvolvimento) e a **base de conhecimento** (pesquisa nos textos confirmados). Têm schemas, rotas, telas e
regras de segurança próprios (`br.com.conferenciaponto.modulos`, `frontend/src/modulos`); o ponto não depende
deles. Chegam em etapas; por enquanto existem os acessos e a chave do Gemini:

- **Acessos** (menu do administrador): quem usa o gerador e quem pesquisa ou cura a base (curar = editar
  qualquer registro e mudar a situação; inclui pesquisar). Vale pessoa por pessoa e **não depende do perfil do
  ponto**: a coordenação pode ser liberada, e o administrador também precisa se liberar.
- Os itens **Atendimentos** e **Base de conhecimento** só aparecem no menu de quem foi liberado. Toda rota dos
  módulos confere a liberação a cada requisição (`403 MODULO_NAO_LIBERADO`); retirar o acesso vale na hora.
- **Minha chave do Gemini** (Atendimentos → Abrir): cada pessoa cadastra a própria chave da API do Gemini
  (Google AI Studio, projeto com faturamento) e confirma que ela é do nível pago — no gratuito o Google pode usar
  o conteúdo enviado. O servidor testa a chave com o Google antes de guardar (listando um modelo, sem gastar cota),
  guarda cifrada (AES-256-GCM) e nunca a devolve: a tela mostra só os 4 últimos caracteres. A chave vai ao Google
  só no cabeçalho `x-goog-api-key`, nunca na URL nem no log. A **chave mestra** que cifra as chaves fica em
  `%USERPROFILE%\.conferencia-ponto\atendimentos\chave-mestra` (`atendimento.chave-mestra.arquivo`), fora do
  banco, legível só pelo usuário do serviço, e entra no `ponto backup`. Se ela se perder, as chaves guardadas
  deixam de abrir (`CHAVE_MESTRA_TROCADA`) e cada pessoa cadastra a sua de novo.

| Método | Rota | Descrição |
|---|---|---|
| GET | `/atendimentos/meu-acesso` · `/conhecimento/meu-acesso` | Qualquer perfil: `{gerador, administrador}` · `{pesquisar, curar, administrador}` |
| GET | `/atendimentos/acessos` · `/conhecimento/acessos` | (ADMIN) usuários ativos com o acesso de cada um |
| PUT | `/atendimentos/acessos/{usuarioId}` · `/conhecimento/acessos/{usuarioId}` | (ADMIN) `{gerador}` · `{pesquisar, curar}` |
| GET | `/atendimentos/chave-gemini` | A minha chave: `{cadastrada, ultimosCaracteres, nivelPagoConfirmado, situacao, testadaEm, atualizadaEm, exigirNivelPago}` |
| PUT | `/atendimentos/chave-gemini` | `{chave, nivelPagoConfirmado}`: testa com o Google e guarda cifrada (`CHAVE_GEMINI_RECUSADA`, `NIVEL_PAGO_NAO_CONFIRMADO`, `GEMINI_INDISPONIVEL`, `SEM_INTERNET`...) |
| POST · DELETE | `/atendimentos/chave-gemini/testar` · `/atendimentos/chave-gemini` | Testa de novo (situação: `valida`, `sem_cota`, `recusada`) · apaga |

## Erros, protocolo e logs

**Mensagens.** Nenhuma falha chega à tela como texto técnico. O servidor responde sempre com uma frase em
português dizendo o que aconteceu e o que fazer (`GlobalExceptionHandler`): recusas previsíveis (regra,
validação, permissão, formato de data/horário, opção inexistente) dizem o campo e o formato esperado; problemas
de ambiente (banco fora do ar, sistema ocupado) pedem para tentar de novo; e o que não era esperado vira
"Aconteceu um erro inesperado… informe ao administrador o protocolo **K7M2QX**".

**Protocolo.** Toda requisição da API recebe um código de 6 caracteres (cabeçalho `X-Protocolo`; nos erros,
também o campo `protocolo` do envelope) que aparece em todas as linhas de log daquela requisição. Com ele o
administrador acha a linha exata: tela **Logs → Buscar**.

**Telas.** O front-end tem uma rede de segurança única (`utils/erros.js`, `AvisosGlobais.vue`,
`EstadoDaTela.vue`): aviso de "sem conexão com o sistema" que some sozinho quando ele volta; "a alteração foi
salva, mas a tela não pôde ser atualizada" (nunca um erro que leve a pessoa a repetir a operação); "carregando",
"não foi possível carregar · Tentar de novo" e "vazio" como estados distintos (nada de zeros inventados);
sessão vencida leva ao login com a explicação e volta para a tela em que a pessoa estava; depois de uma
atualização do sistema, a aba aberta se recarrega sozinha. Um defeito da própria tela mostra uma frase genérica
e é relatado ao servidor (`POST /erros-de-tela`), caindo no log do usuário.

**Logs.** Três lugares, a mesma informação:

| Onde | O quê |
|---|---|
| Console | Ao vivo: `ponto logs [usuario] [-Erros]` (serviço) ou a própria saída do `.\iniciar.ps1` / Eclipse |
| `logs/conferencia-ponto.log` | Tudo, com `[usuario\|protocolo]` em cada linha |
| `logs/usuarios/<login>/<aaaa-mm-dd>/<hh>h.log` | **Um arquivo por usuário e por hora** (`sistema` = o que não é de uma pessoa: subida, tarefas agendadas); guardados por `ponto.logs.dias` (30) dias |
| Tela **Logs** (ADMIN) | "Ao vivo" (atualiza a cada 2 s; filtros por pessoa, gravidade e texto/protocolo; pausar) e "Arquivos por pessoa e hora" |

Cada requisição da API vira uma linha de acesso (`GET /api/v1/jornadas?ano=…  -> 200 (12 ms)`) no arquivo de
quem a fez; entradas no sistema e tentativas de login recusadas também ficam registradas (a senha, nunca). O
que roda em segundo plano por causa de uma pessoa (leitura da pasta de PDFs, conferência do relatório do RH,
gravação da planilha do Google) cai no arquivo dela. A pasta padrão é `~/.conferencia-ponto/logs`
(`PONTO_LOGS_DIR`); no serviço do Windows, `%USERPROFILE%\.conferencia-ponto\servico\logs`.

## Planilha de conferência (Excel e Google Sheets)

A conferência de cada pessoa numa planilha, para quem confere não precisar entrar no sistema:

- **Resumo:** o banco de horas (ciclo atual e os encerrados), uma linha por mês com dias registrados, horas
  trabalhadas, previstas, saldo dos dias, lançamentos no banco, **saldo do mês** e o banco de horas no fim do
  mês, o total geral e o horário de trabalho.
- **Uma aba por mês** (do primeiro ao último mês com registro, o mais recente primeiro): **todos os dias do
  calendário** — inclusive fim de semana, feriado, férias e dia útil *sem registro* —, as batidas, trabalhado,
  previsto, o ajuste da **tolerância** (saldo do dia = trabalhado + tolerância − previsto, a regra do RH), saldo
  do dia, lançamento no banco, situação, quantos PDFs comprovam o dia e observações (feriado,
  justificativa do abono, ajuste manual com motivo e autor, lançamento no banco). A linha **Total do mês** fica
  fixa no topo e é feita de **fórmulas** (`=SUM(...)`), assim como o resumo, que aponta para os totais de cada
  aba: quem confere vê de onde vem cada número.
- Durações e horários são **números** (somam, filtram e ordenam); débito aparece negativo, em vermelho. Como no
  saldo do sistema, só entram nos totais os dias fechados: dia em andamento ou incompleto fica com as horas em
  branco e o que foi trabalhado até a última batida vai nas observações.

**Excel:** botão *Exportar Excel* na Auditoria (`GET /api/v1/planilha/exportar`) baixa o `.xlsx` com todas as
abas — o arquivo é gerado pelo próprio sistema, sem bibliotecas. A pasta de trabalho usa o sistema de datas de
1904, o único em que o Excel mostra horas negativas.

**Google Sheets (atualização automática):**

1. **Uma vez, o administrador** cria uma *conta de serviço* no Google Cloud (gratuito) e envia a chave em
   *Usuários → Planilhas no Google Sheets* (a tela traz o passo a passo): criar um projeto em
   `console.cloud.google.com`, ativar a **Google Sheets API**, criar a conta em *IAM e administrador → Contas de
   serviço* e baixar uma chave **JSON** na aba *Chaves*. O sistema confere a chave com o Google e a grava em
   `~/.conferencia-ponto/google/conta-de-servico.json` (`ponto.google.credencial`) — fora do banco de dados e
   nunca devolvida pela API.
2. **Cada pessoa**, em *Minha conta → Planilha no Google*: cria uma planilha (`sheets.new`), compartilha com o
   e-mail da conta de serviço como **Editor** e cola o link. A planilha é gravada na hora.
3. Depois é só compartilhar a planilha, como **Leitor**, com quem confere.

A cada mudança no ponto (PDF importado, ajuste, lançamento no banco, feriado, férias, horário, fechamento do
ciclo) as abas dos meses afetados e o resumo são regravados em alguns segundos — mudanças seguidas viram uma
gravação só. Na subida do sistema e uma vez por dia tudo é regravado. Sem internet ou no limite do Google, o
sistema tenta de novo sozinho; se a planilha deixar de estar compartilhada, o motivo aparece em *Minha conta*.

- As abas do sistema são reconhecidas por um identificador próprio, não pelo nome; **as outras abas da planilha
  ficam intactas** (quem confere pode anotar numa aba à parte). O que for digitado numa aba do sistema é apagado
  na gravação seguinte — por isso elas têm o aviso de edição do Google.
- Cada pessoa usa a própria planilha (duas pessoas não podem apontar para a mesma). O administrador pode
  vincular, atualizar ou desconectar a planilha de outra pessoa escolhendo-a em "Dados de".
- Desligar a integração (*Usuários*) apaga a chave do computador; as planilhas ficam como estão no Google.

## Regras de cálculo (domínio)

Implementadas em `MotorCalculoJornadaService` (sem dependência de framework):

A regra foi deduzida dos relatórios de banco de horas do RH e reproduz **ao segundo**
todos os dias de nov/2025 a ago/2026:

| Regra | Comportamento |
|---|---|
| Precisão | **Segundos contam**: `08:05:22` é 5 min 22 s de atraso. Durações e saldos em segundos (`HH:mm:ss`). |
| Batidas | Até **6 por dia** (3 intervalos, ex.: saída às 11:01 e volta às 11:15 além do almoço). |
| Trabalhado | Soma bruta dos intervalos fechados (como "Hr. Trabalhadas" do RH). |
| Grade | A do dia da semana no horário de trabalho da pessoa (vigente na data). Padrão: 08:00–12:00 e 13:00–17:48. |
| Tolerância | Cada horário da grade (ex.: 08:00 e 13:00 de entrada, 12:00 e 17:48 de saída) é casado com a batida mais próxima do mesmo tipo; se a diferença for **até a tolerância do horário (padrão 5:00, inclusive)**, vale a grade. Batidas extras não têm horário de grade: o tempo fora conta integralmente. |
| Dia útil | Saldo = trabalhado + ajustes da tolerância − carga do dia (padrão 08:48:00). |
| Dia sem expediente / feriado / ausência | Base 0: todo o tempo é crédito. |
| Jornada em andamento | Número ímpar de batidas: saldo = `null` (fora das consolidações até fechar). |

Na subida, todos os dias gravados são recalculados com a regra atual (`RecalculoJornadasNaInicializacao`).
`MotorCalculoJornadaServiceTest` confere 25 dias reais do relatório do RH (inclusive com 6 batidas).

## API (`/api/v1`)

Toda resposta JSON usa o envelope `{ sucesso, dados, erros[{codigo, mensagem, campo}], timestamp }` (nos erros,
também `protocolo`); toda resposta leva o cabeçalho `X-Protocolo`.
Todas as rotas, exceto o login, exigem `Authorization: Bearer <token>`.

| Método | Rota | Descrição |
|---|---|---|
| POST | `/auth/login` | `{login, senha}` → `{token, tipo, expiraEm, usuario{login, nome, perfis}}` |
| GET | `/auth/me` | Usuário da sessão (`titular`, `podeVerTodos`, `admin`, `trocarSenha`, `pastaComprovantes`) |
| PUT | `/auth/senha` | `{senhaAtual, novaSenha}` (mín. 8 caracteres) — qualquer perfil; encerra a senha provisória |
| GET | `/usuarios/titulares` | Pessoas com dados de ponto que o usuário pode consultar |
| GET/POST | `/usuarios` | (ADMIN) lista · cria `{login, nome, perfil, senhaProvisoria, pastaComprovantes?}` |
| PUT | `/usuarios/{id}` · `/{id}/senha` · `/{id}/pasta` | (ADMIN) `{nome, perfil, ativo}` · `{senhaProvisoria}` · `{pasta}` |
| PUT | `/conta/pasta` · POST `/conta/pasta/verificar` | Pasta dos meus comprovantes `{pasta}` (vazia = sem monitoramento) · só confere o acesso |
| GET/POST | `/horarios` | Vigências do horário · novo horário `{vigenteDesde, toleranciaMinutos, dias: {SEG: "08:00-12:00 13:00-17:48", ...}}` (ADMIN: `?usuario=login`) |
| DELETE | `/horarios/{id}` | Remove uma vigência (os dias voltam ao horário anterior) |
| GET | `/configuracao` | Horário da pessoa que vale hoje (grade, tolerância, jornada), data/hora do servidor |
| GET | `/jornadas?ano=&mes=` | Dias do mês + resumo (saldo mensal e anual acumulado) |
| GET | `/jornadas/{data}` | Um dia |
| POST | `/jornadas/batidas` | Próxima batida `{data?, horario?}` (vazio = relógio do servidor) |
| POST | `/jornadas/manual` | `{data, intervalos:[{entrada, saida}]}` — só fim de semana/feriado |
| DELETE | `/jornadas/{data}` | Exclui o registro do dia (bloqueado se o dia tiver PDFs arquivados) |
| PUT | `/jornadas/{data}/batidas` | Ajuste manual: `{horarios: ["08:05:53","12:00",...], justificativa}` (lista final do dia) |
| GET | `/jornadas/{data}/ajustes` | Batidas com PDF (travadas) + histórico de ajustes do dia |
| GET | `/saldos?ano=&mes=` | Saldo mensal, anual acumulado, série dos 12 meses e o **ciclo aberto** do banco de horas |
| GET | `/ciclos` · `/ciclos/atual` | Ciclos do banco de horas (saldo, previsão, dias restantes, meses do ciclo) |
| PUT | `/ciclos/atual` | Corrige `{dataInicio, dataFimPrevista?}` do ciclo aberto |
| POST | `/ciclos/fechar` | `{ultimoDia?, observacao?}` congela o saldo e recomeça do zero (também em `/api/ciclos/fechar`) |
| POST | `/ciclos/desfazer-fechamento` | O ciclo anterior volta a ser o aberto |
| GET | `/notificacoes` | Avisos (prazo do banco, conciliação) + não lidos · `POST /notificacoes/{id}/lida` · `POST /notificacoes/lidas` |
| GET/POST/DELETE | `/ausencias` | Férias, atestados, licenças, folgas e abonos `{dataInicio, dataFim, tipo, descricao}` (`ABONO` exige a justificativa) |
| GET/POST/DELETE | `/feriados` · `/feriados/{data}` | Feriados `{data, descricao, abrangencia: NACIONAL\|ESTADUAL\|MUNICIPAL\|EMPRESA}` |
| GET/POST/DELETE | `/lancamentos-banco` · `/{id}` | Lançamentos avulsos no banco `{data, duracao: "04:00", sentido: DEBITO\|CREDITO, descricao}` |
| POST | `/conciliacoes` | Envia o PDF do relatório do RH (multipart, campo `arquivo`) → `202`, confere em segundo plano |
| GET | `/conciliacoes/resumo` | Relatórios enviados com comparativo de saldo + pendências por tipo |
| GET | `/conciliacoes/divergencias?status=` | `PENDENTE` (padrão), `ACEITO_RH`, `MANTIDO_LOCAL`, `RESOLVIDA` ou `TODAS`, com os dois lados |
| POST | `/conciliacoes/divergencias/{id}/aceitar` · `/manter` · `/reabrir` | Decisões por dia |
| POST | `/conciliacoes/divergencias/aceitar-lote` | `{tipos[], inicio?, fim?}` |
| POST | `/conciliacoes/reconferir` · DELETE `/conciliacoes/relatorios/{id}` | Reconfere tudo · remove um relatório |
| GET | `/auditoria?ano=&mes=` | Dias do mês + comprovantes de cada batida (com `urlDownload`) + resumo |
| GET | `/planilha/exportar` | Planilha de conferência em Excel (`.xlsx`): resumo + uma aba por mês |
| GET | `/planilha` | Planilha do Google da pessoa: `{situacao, emailServico, url, titulo, sincronizadaEm, erro}` |
| PUT | `/planilha` | `{link}` vincula a planilha do Google e grava tudo nela (ADMIN: `?usuario=login`) |
| POST | `/planilha/sincronizar` · DELETE `/planilha` | Regrava agora · deixa de gravar (ADMIN: `?usuario=login`) |
| GET/PUT/DELETE | `/integracoes/google` | (ADMIN) conta de serviço: consulta · envia a chave `{chave: "<conteúdo do .json>"}` · desliga |
| GET | `/comprovantes/{id}/download` | PDF original (`attachment`); também em `/api/comprovantes/{id}/download` |
| GET | `/presenca` | Equipe agora: todos os usuários ativos com `{online, situacao, situacaoRotulo, motivo, desde, horario, ...}` |
| GET | `/logs/ao-vivo?depois=&nivel=&login=&texto=&semConsultas=` | (ADMIN) últimas linhas de log, para a tela consultar a cada 2 s |
| GET | `/logs/arquivos` · `/logs/arquivo?login=&data=&hora=` | (ADMIN) pastas por usuário → dias → horas · o log daquela hora |
| POST | `/erros-de-tela` | `{mensagem, tela, detalhe}` — a tela relata um defeito dela (qualquer perfil) → `202` |
| GET | `/eventos` | Stream SSE (`text/event-stream`) com as atualizações em tempo real |
| GET | `/importacoes?limite=` | Monitor da pasta da pessoa (`situacao`: ATIVO, INDISPONIVEL, SEM_PASTA...) + últimos comprovantes |
| POST | `/importacoes/reprocessar` | Relê a pasta da pessoa (`202 Accepted`, roda em segundo plano) |
| POST | `/importacoes/enviar` | Comprovantes PDF enviados pela tela (multipart, campo `arquivos`, até 50) |

Nos `GET`, `?usuario=login` consulta outra pessoa (ADMIN e coordenação). Dias, mês e saldos trazem a grade
(`grade` do dia e `expedientes` do mês) pelo horário da pessoa.

Erros: `400` validação/formato · `401` sem sessão/credenciais inválidas (`ACESSO_DESATIVADO` quando o
administrador desativou o usuário) · `403` perfil sem permissão (`ACESSO_NEGADO`, `ALTERAR_DADOS_DE_OUTRO`,
`TROCAR_SENHA`) · `404` não encontrado · `409` conflito (`REGISTRO_REPETIDO`, `REGISTRO_JA_ALTERADO`,
`SISTEMA_OCUPADO`) · `422` regra de negócio · `503 BANCO_INDISPONIVEL` · `500 ERRO_INTERNO` (com o protocolo na
mensagem) e `500 COMPROVANTE_CORROMPIDO` (arquivo alterado em disco). Ver "Erros, protocolo e logs".

## Perfis de acesso (RBAC)

Autenticação **stateless** com JWT HS256 (claims `sub`, `nome`, `roles`; validade de 8 h), senhas com
BCrypt. As regras ficam em `SecurityConfig` e são decididas **pelo método HTTP**, então uma rota nova de
escrita já nasce protegida:

| Perfil | Leitura (`GET`) | Escrita (`POST`/`PUT`/`PATCH`/`DELETE`) | Tela inicial |
|---|---|---|---|
| `ROLE_ADMIN` | ✔ os próprios dados e os de todos, e os **logs** | ✔ os próprios dados + usuários, feriados, conta do Google e horário, pasta e planilha de qualquer pessoa | Painel |
| `ROLE_USER` | ✔ só os próprios dados | ✔ os próprios dados | Painel |
| `ROLE_VIEWER` | ✔ os de todos, inclusive download dos PDFs | ✘ `403 ACESSO_NEGADO` | Auditoria |

Fora de `/api/**`, só `GET`/`HEAD` são liberados: são as telas empacotadas no jar (HTML/JS/CSS, sem dados —
`FrontendConfig` devolve o `index.html` para as rotas do Vue); o resto é negado. No front-end, o perfil `VIEWER` vê o selo "somente leitura" e
os botões de bater ponto, lançamento manual e exclusão nem são renderizados — mas a garantia é do
back-end, não da tela.

**Tela de auditoria (`/auditoria`).** Pensada para a coordenação (perfil `VIEWER`):
filtro por mês/ano, tipo de dia e situação (débito, crédito, fora da tolerância, em andamento,
com/sem comprovante);
para cada batida, o horário real e o considerado, minutos abonados pela tolerância, trabalhado,
previsto e saldo do dia; botão **PDF ⤓** por batida e **n PDFs** por dia; totais do período;
**Exportar Excel** (a planilha de conferência completa) e o atalho para a planilha do Google da pessoa, quando
há uma; e atualização ao vivo quando um comprovante novo chega.

## Banco de dados

Migrações em `backend/src/main/resources/db/migration`:

- `V1` — `tb_registro_jornada` (batidas reais com segundos, saldo desnormalizado, `CHECK` de sequência/cronologia/saldo)
- `V2` — `tb_feriado` com os feriados **nacionais** de 2026 (inclua os estaduais/municipais)
- `V3` — `vw_saldo_mensal` (saldo mensal + acumulado anual via window function)
- `V4` — `tb_comprovante_ponto` (auditoria dos PDFs: hash único + índice único parcial por data/hora importada)
- `V5` — `tb_comprovante` (PDF arquivado, 1:N com `tb_registro_jornada`, `ON DELETE RESTRICT`,
  `UNIQUE(registro_jornada_id, tipo_batida)` *deferrable*), `tb_role` (ADMIN/USER/VIEWER),
  `tb_usuario` e `tb_usuario_role`
- `V6` — ajuste manual: `tb_registro_jornada.horarios_ajustados` (quais batidas atuais foram incluídas/corrigidas
  à mão) e `tb_ajuste_jornada` (histórico: antes, depois, justificativa, usuário, data)
- `V7` — cálculo igual ao do RH: colunas em **segundos**, `entrada_3/saida_3`, `vw_saldo_mensal` em segundos; os
  comprovantes rejeitados por "4 batidas" voltam a ser importados na próxima leitura da pasta
- `V8` — `tb_ausencia` (férias/atestado/licença/folga, sem sobreposição) e tipo de dia `AUSENCIA`
- `V9` — `tb_ciclo_banco` (no máximo um `ABERTO`, sem sobreposição) e `tb_notificacao`
- `V10` — `tb_relatorio_rh`, `tb_relatorio_rh_dia` e `tb_divergencia` (uma por data, com a decisão)
- `V11` — `tb_lancamento_banco` (débitos/créditos avulsos, até 300 h, com justificativa), ausência `ABONO` e os
  feriados nacionais de 2027
- `V12` — vários usuários: `usuario_id` em todas as tabelas de dados (os existentes vão para o primeiro
  administrador), `tb_horario_trabalho` (períodos por dia da semana, tolerância e vigência), pasta dos
  comprovantes e troca de senha obrigatória em `tb_usuario`, `vw_saldo_mensal` por usuário
- `V13` — `tb_planilha_google`: a planilha do Google de cada usuário, com a última gravação e o último erro
- `V14` — módulo de atendimentos, no schema próprio `atendimento` (nenhuma tabela do ponto muda): `acesso`,
  `chave_gemini` (cifrada), `atendimento`, `arquivo`, `analise`, `saida`, `imagem` e `tarefa` (a fila)
- `V15` — base de conhecimento, no schema `conhecimento`: `acesso`, `registro` (busca em português sem acento,
  mantida por gatilho, e trigramas), `registro_imagem`, `registro_situacao` e `vetor`; ativa as extensões
  `unaccent` e `pg_trgm` (confiáveis: o dono do banco cria sem superusuário)

Os schemas dos módulos só se ligam ao ponto por `tb_usuario`; para desfazê-los sem tocar no ponto, veja
[Backup](#backup). `MigracoesPostgresTest` aplica todas as migrações num PostgreSQL temporário (o mesmo 18.6,
baixado pelo Maven, criado numa pasta temporária e apagado no fim do teste), com o usuário `ponto` sem
superusuário, e confere que nada do schema `public` muda; `ArquiteturaModulosTest` confere que o código do
ponto não depende dos módulos e vice-versa.

## Banco de horas semestral (ciclo)

O RH zera o banco a cada 6 meses. O saldo do painel é o do **ciclo aberto** (card "Banco de horas · ciclo
atual", com o gráfico mês a mês do ciclo):

- **Fechar banco de horas**: escolhe o último dia incluído (sugestão: a previsão, se já passou; senão, ontem), o
  saldo exato fica congelado no ciclo `FECHADO` e um novo ciclo começa **do zero** no dia seguinte, com previsão de
  6 meses. Dá para **desfazer** o último fechamento.
- **Período**: corrige o início/previsão do ciclo aberto (o primeiro ciclo nasce de
  `ponto.banco-horas.inicio-primeiro-ciclo`, padrão `2026-05-25` — dia seguinte ao zeramento de 24/05/2026).
- **Avisos**: todo dia à 01:00 (`@Scheduled`, `ponto.banco-horas.cron-alertas`) e na subida, o sistema confere o
  prazo e cria avisos no sino a **30** e **15 dias** da previsão e quando ela passa. Cada aviso sai uma vez; os do
  ciclo anterior são arquivados ao fechar.

## Lançamentos no banco de horas (abater ou creditar)

Botão **Lançar no banco** no Painel: abate horas do banco (ex.: as horas a mais compensadas com uma folga ou uma
saída antecipada, horas pagas pela empresa) ou credita uma correção, numa data e com motivo obrigatório. As batidas
do dia **não mudam**: o lançamento entra no saldo do mês e do ciclo (`ConsolidacaoBancoHoras` soma as jornadas
consolidadas no banco de dados com os lançamentos). No cartão o dia ganha o selo `banco −04:00`, e a lista do mês
fica abaixo do cartão (com "Remover"). Regras: até 300 h por lançamento, só a partir do início do ciclo aberto (o
saldo de um ciclo fechado já foi congelado) e no máximo 1 ano à frente. A conciliação com o RH continua comparando
só as jornadas. Folga que deve **descontar** do banco = lançamento de −08:48 no dia (atalho "dia inteiro").

## Feriados, férias, atestados, licenças, folgas e abonos

Tela **Folgas e feriados** (`/ausencias`), ou direto pelo dia no Painel (ícone de calendário, ou clique num dia útil
sem registro): **feriado** (municipal, estadual, nacional ou da empresa/ponto facultativo — ex.: Corpus Christi),
**férias**, **atestado**, **licença**, **folga** ou **outra justificativa** (abono, com o motivo obrigatório). Nesses dias
a jornada base é **zero** — não há débito e o dia não aparece como "sem registro" (o cartão mostra o rótulo). Dias já
registrados são reclassificados e, se houver trabalho num desses dias, o tempo vira crédito. Clicando num dia
marcado, dá para ver e remover a marcação. Os feriados nacionais de 2026 e 2027 vêm cadastrados.

## Conciliação com o relatório do RH

Tela **Conciliação RH** (`/conciliacao`): arraste o PDF do "Relatório de Banco de Horas". O leitor (PDFBox, pela
posição das colunas) extrai emissão, período, totais e, por dia, batidas, ocorrência (Feriado, Férias, folga) e
"Hr. Trabalho / Trabalhadas / Extra-Falta". **O PDF não é guardado** (tem CPF): ficam só esses dados e o hash (o
mesmo arquivo não é enviado duas vezes). Dias a partir da data de emissão não são conferidos (estavam em andamento).

Em segundo plano, cada data é comparada com a conferência usando o relatório **mais recente** que a cobre. As
divergências ficam numa lista com os dois lados (conferência × RH), por tipo: tipo do dia, só no RH, só na
conferência, batida faltando/a mais, horário diferente, saldo diferente (regra) e diferença de segundos (o PDF do
comprovante costuma marcar 1 s depois do RH — só vira divergência se mudar o saldo). **Nada é alterado sozinho**:

- **Aceitar dados do RH** — o dia fica igual ao RH, com histórico "Conforme relatório do RH". Batidas com PDF só têm
  os segundos alinhados (até 1 min), nunca são apagadas; feriado vira feriado da empresa; férias/folga viram um
  período de ausência (o RH marca também sábados e domingos, então o período sai inteiro).
- **Manter dados locais** — a diferença fica registrada (com observação) e só volta se algum lado mudar.
- **Ajuste manual** — abre o ajuste de batidas já preenchido com as batidas do RH.
- **Aceitar em lote** — por tipo (padrão: só no RH, tipo do dia e diferença de segundos).

A lista acompanha a conferência em tempo real: um PDF novo, um ajuste ou uma ausência reconferem o dia. O quadro
de relatórios compara o saldo do RH com o da conferência nos mesmos dias ("igual ✓" quando bate).

## Ajuste manual de batidas (correção do RH)

Quando o relógio falha (batida não registrada, comprovante não gerado) e o RH corrige no sistema dele, o dia fica
"incompleto" aqui — e a conferência deixa de bater. O **ajuste manual** resolve:

- No **Painel**, o lápis ✎ no fim de cada linha do cartão (sempre visível nos dias **incompletos**) ou o botão
  **Ajustar batidas** na linha do tempo abrem a tela de ajuste. Dá para incluir a batida esquecida, corrigir ou
  remover batidas sem comprovante e, num dia sem nenhum registro, preencher com a grade oficial.
- **Batidas com PDF ficam travadas**: o comprovante é a prova da marcação real e não pode ser alterado nem apagado.
- A lista é reordenada automaticamente (ex.: `08:05 · 12:58 · 18:03` + `12:00` vira E1 08:05, S1 12:00, E2 12:58,
  S2 18:03) e os PDFs acompanham a nova posição.
- **Justificativa obrigatória** (há sugestões prontas). Cada ajuste fica no histórico com antes → depois, usuário e
  data; o usuário vem do token, não da tela.
- Batidas ajustadas aparecem marcadas (`aj` no cartão, "ajustada" na auditoria) e a coordenação vê o histórico em
  "ajustado à mão", com filtro próprio; na planilha de conferência a batida vai em itálico e o motivo, nas
  observações do dia. Perfil `VIEWER` não ajusta (`403`).
- Regras: de 1 a 6 batidas (com segundos), pelo menos 1 minuto entre elas, nada no futuro e alguma coisa precisa mudar.
- "Incompleto" = dia que já passou com entrada sem saída (antes aparecia como "em andamento").

## Arquivo dos comprovantes (File Storage)

Cada PDF importado é **copiado** (o original na pasta de Downloads fica intacto) para a pasta gerenciada:

```
~/.conferencia-ponto/comprovantes/        (ou PONTO_ARMAZENAMENTO_DIR)
└── 2026/09/comprovante_<uuid>.pdf        uuid = id da linha em tb_comprovante
```

- **Gravação atômica:** escreve em arquivo temporário, força o `fsync` e renomeia; o arquivo final fica
  somente leitura. Se a transação do banco falhar, o arquivo recém-copiado é apagado.
- **Vínculo com a batida:** `tipo_batida` (ENTRADA_1…SAIDA_2) acompanha a posição real — se um PDF de
  horário anterior chegar depois, os tipos são reorganizados na mesma transação.
- **Integridade:** o SHA-256 gravado no banco é conferido a cada download; divergência retorna
  `500 COMPROVANTE_CORROMPIDO` em vez de entregar um arquivo adulterado. O `ETag` da resposta é o hash.
- **Nome no download:** `comprovante_2026-09-28_ENTRADA_1_080231.pdf` (data, batida, horário real).
- **Evidência de batidas já registradas:** se o PDF chega de uma batida já feita pelo botão (≤ 1 min), ele
  é arquivado como comprovante dela. PDFs importados antes deste módulo são arquivados quando reaparecem
  na pasta (use *reprocessar*).
- **Proteção:** caminhos são sempre resolvidos dentro da raiz (sem *path traversal*), e um dia com
  comprovantes arquivados não pode ser excluído (`409 REGISTRO_COM_COMPROVANTES`).
- **Trocar por MinIO/S3:** basta outra implementação da porta `ArmazenamentoComprovantes`
  (`armazenar`, `ler`, `uriDeAcesso`); casos de uso e controller não mudam.

## Importação automática de comprovantes (PDF)

Fluxo: **navegador baixa o PDF → `DiretorioPontoWatcher` (WatchService, `ENTRY_CREATE`) →
`ProcessadorComprovantePdf` (espera o download terminar) → `PdfParserService` (trava o arquivo,
PDFBox + regex) → `ImportarComprovanteUseCase` (aloca a batida) → `EmissorEventosSse` (após o commit) →
`usePontoStore` atualiza a tela.**

1. **Pasta monitorada:** cada pessoa escolhe a sua em *Minha conta* (`GerenciadorMonitoresPdf` mantém um
   monitor por pessoa, numa thread própria, e reorganiza os monitores quando o cadastro muda). Configure o
   navegador para salvar os comprovantes nela (no Chrome/Edge: *Configurações › Downloads*). Se apontar para
   a pasta de Downloads inteira, PDFs que não são comprovantes ficam registrados como `INVALIDO` e não
   afetam a jornada. Sem pasta, os PDFs podem ser **enviados pela tela**.
2. **Roteamento:** a data/hora do PDF define `data_referencia`; o horário entra na primeira coluna livre
   em ordem cronológica (`entrada_1` → `saida_1` → `entrada_2` → `saida_2`). PDFs baixados fora de ordem
   são encaixados na posição certa.
3. **Sem duplicidade:** o mesmo arquivo (hash SHA-256) nunca é reprocessado; a mesma data/hora não gera
   duas batidas; e uma batida a menos de 1 min de outra já registrada (ex.: pelo botão "Bater ponto")
   é tratada como a mesma marcação (`DUPLICADO`).
4. **Ao iniciar**, os PDFs que já estavam na pasta são processados (útil se o serviço estava desligado).
   Arquivos com mais de 30 s dispensam a espera de "download terminando", então centenas de PDFs antigos
   entram em segundos.
5. **Teste rápido:** com o back-end e o front-end rodando, copie um arquivo de `exemplos/comprovantes/`
   para a pasta monitorada: a linha do dia 28/09 muda na tela em menos de 1 segundo.

Status de cada PDF (tabela `tb_comprovante_ponto` e `GET /importacoes`):
`IMPORTADO`, `DUPLICADO`, `REJEITADO` (ex.: 5ª batida, data futura) ou `INVALIDO` (padrão não encontrado).

### Pasta de rede (comprovantes salvos em outro computador)

Quando o ponto é batido em outro PC e os PDFs caem numa pasta compartilhada, informe o caminho de rede em
*Minha conta* (ex.: `\\192.168.0.10\Ponto` ou `//192.168.0.10/Ponto`).

- Evite letra de unidade mapeada (`Z:`), que depende da sessão do Windows.
- O back-end acessa a pasta com o usuário do Windows que o executa. Se a pasta pedir senha, abra-a uma vez
  no Explorer marcando *Lembrar minhas credenciais* (ou `cmdkey /add:192.168.0.10 /user:USUARIO /pass`).
- **Queda da conexão** (VPN caiu, outro PC desligado): o painel mostra *Pasta dos PDFs inacessível ·
  tentando reconectar*, o monitor tenta de novo a cada 15 s (`intervalo-reconexao`) e, quando a pasta
  volta, confere e importa o que chegou nesse meio-tempo.
- **Varredura de segurança** a cada 30 s (`varredura-periodica`): em pasta de rede o aviso do Windows sobre
  arquivo novo pode se perder. A varredura lista a pasta numa única ida ao servidor e só lê arquivos novos
  ou alterados (nome + tamanho + data), então não pesa na VPN.
- A cada subida do back-end os PDFs da pasta são relidos para conferir o hash — nada é duplicado. Medido
  numa VPN: 381 PDFs (48 MB) em ~27 s, em segundo plano.
- Os PDFs são **copiados** para o arquivo local; o download na auditoria funciona mesmo com a pasta de rede
  fora do ar.

**Formato do comprovante (validado com PDFs reais do Ponto Fácil).** Os comprovantes são gerados com
TCPDF e trazem no cabeçalho o texto `Comprovante de Ponto - dd/MM/yyyy HH:mm:ss` (ex.:
`Comprovante de Ponto - 28/09/2026 08:02:31`); o arquivo baixado se chama
`comprovanteponto - 2026-09-28T080231.262.pdf`. A regex padrão lê exatamente esse cabeçalho e ainda
tolera quebras de linha, traços `–`/`—`, maiúsculas e hora sem segundos. O teste gera um PDF no mesmo
formato (fonte embutida, cabeçalho em pedaços); o comprovante real não fica no repositório (a assinatura
digital dele tem dados pessoais). Se o layout mudar, ajuste
`ponto.importacao-pdf.regex` no `application.yml` (mantendo os grupos `(?<data>…)` e `(?<hora>…)`),
sem recompilar.

### Eventos SSE (`GET /api/v1/eventos`)

| Evento | Quando | `data` |
|---|---|---|
| `conectado` | ao abrir a conexão | monitor da pasta do usuário: `{habilitado, ativo, situacao, diretorio, mensagem, ultimaVarredura}` |
| `jornada-atualizada` | PDF importado, "bater ponto", lançamento manual ou exclusão | `{origem, data, registro, mensagem}` |
| `comprovante-nao-importado` | PDF lido que não gerou batida | `{nomeArquivo, status, dataHoraBatida, mensagem}` |
| `monitor-atualizado` | a pasta dos PDFs ficou inacessível ou voltou | `{situacao, ativo, diretorio, mensagem, ultimaVarredura}` |
| `ciclo-atualizado` | banco de horas fechado, fechamento desfeito ou período corrigido | ciclo aberto |
| `notificacao` | novo aviso no sino | `{notificacao, naoLidas}` |
| `conciliacao-atualizada` | relatório do RH conferido ou divergência resolvida | `{descricao, pendentes}` |
| `calendario-atualizado` | feriado, ausência ou horário alterado | `{inicio, fim}` |
| `banco-atualizado` | lançamento no banco de horas criado ou removido | `{data, descricao}` |
| `usuarios-atualizados` | usuário cadastrado ou alterado (perfil, situação, pasta) | `{alterado}` (só o id; as conexões de quem mudou são reabertas) |
| `planilha-atualizada` | a planilha do Google foi gravada, falhou, foi vinculada ou desvinculada | `{situacao, url, titulo, sincronizadaEm, erro}` |
| `presenca-atualizada` | alguém bateu o ponto hoje, ou o dia de hoje mudou para alguém (folga, feriado, cadastro) | vazio: vai para **todos**, só para a tela Equipe consultar de novo |

Todo evento leva `usuarioId`, o dono dos dados (`null` = vale para todos, como um feriado). Cada pessoa
recebe os eventos dos próprios dados; administrador e coordenação recebem os de todos, e a tela ignora o que
não é da pessoa em tela.

No front-end, `usePontoStore().conectarTempoReal()` abre o stream assim que há sessão, substitui o dia
afetado direto no estado (sem recarregar a página) e recarrega só os saldos — uma vez só depois de uma
rajada de eventos, como na primeira leitura de uma pasta cheia. Como o `EventSource` nativo
não envia cabeçalhos, o stream usa `@microsoft/fetch-event-source` com o `Authorization: Bearer`,
reconexão com *backoff* e parada definitiva só em `401` (sessão vencida → login) e `403 TROCAR_SENHA`; as
demais chamadas seguem pelo Axios. Ao reconectar depois de uma queda, a tela recarrega o que pode ter perdido.

## Observações

- O `pom.xml` passa `-Dnet.bytebuddy.experimental=true` para o Hibernate rodar em JDKs mais novos
  que o suportado oficialmente pelo Spring Boot 3.5 (ex.: Java 27).
- Com PostgreSQL 18 o Flyway registra o aviso `Flyway upgrade recommended ... latest supported version
  of PostgreSQL is 17`. É só um aviso: as migrações validam e rodam normalmente.
- Feriados e ausências aceitos na conciliação (ou cadastrados na tela de ausências) reclassificam os dias já
  registrados. Um feriado inserido direto no banco só vale para dias novos (ou após reclassificar pela conciliação).

## Licença

Copyright (c) 2026 Eduardo Yucho. Distribuído sob a **[PolyForm Strict License 1.0.0](LICENSE)**
(<https://polyformproject.org/licenses/strict/1.0.0>). Resumo, sem valor legal — o que vale é o texto do
arquivo `LICENSE`:

- **Pode:** usar para fins não comerciais — uso pessoal, estudo, testes, hobby — e por organizações sem fins
  lucrativos, instituições de ensino e órgãos públicos.
- **Não pode:** usar com finalidade comercial (vender, cobrar pelo uso, oferecer como serviço, usar para ganhar
  dinheiro), **modificar** o código ou criar obras derivadas, nem **redistribuir** o software.
- O software é fornecido "no estado em que se encontra", sem garantias.

Para qualquer uso fora desses termos (inclusive comercial), é preciso autorização por escrito do autor —
entre em contato pelo GitHub ([@EduardoYucho](https://github.com/EduardoYucho)).
