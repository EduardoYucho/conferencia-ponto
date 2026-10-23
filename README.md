# Conferência de Ponto

Controle de jornada e banco de horas: **Spring Boot 3 + PostgreSQL + Vue 3**.
**Vários usuários**, cada um com o próprio acesso, **horário de trabalho editável** (por dia da semana, com
vigência) e **pasta de comprovantes** (ou envio dos PDFs pela tela). Cálculo **igual ao do sistema de ponto
do RH** (com segundos, tolerância por horário da grade, até 6 batidas; horário padrão 08:00–12:00 e
13:00–17:48 = 08:48), lançamento manual de dias sem expediente/feriados (100% crédito),
**banco de horas semestral** com botão de fechamento e avisos de prazo, **lançamentos no banco** (abater ou
creditar horas), **carga semanal** comparada com o horário (o fim de semana compensa o que falta) e a tela **Banco de
horas** com a origem de cada batida, os avisos da lei e a **conferência do mês**, **feriados, férias, folgas,
atestados e abonos**,
**importação automática dos comprovantes em PDF** com atualização em tempo real (SSE), **arquivo seguro dos
PDFs**, **controle de acessos por perfis e permissões (JWT)** com auditoria para a coordenação, **conciliação com o relatório de
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
        ├── stores/             auth.js (sessão, permissões, "dados de") · ponto.js (usePontoStore, tempo real)
        ├── views/              Login · TrocarSenha · Dashboard · BancoDeHoras · Auditoria · Conciliacao · Ausencias ·
        │                       MinhaConta (senha, pasta, horário, planilha) · Usuarios · Perfis (acessos)
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
.\iniciar.ps1 -Testes    # 491 testes (domínio, casos de uso, PDFs, monitor, arquivo, RBAC, ajuste, ciclo, conciliação, usuários, horários, planilha, presença, logs, mensagens de erro, migrações num PostgreSQL temporário, isolamento dos módulos)
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

Usuários iniciais: `admin` (perfil *Administrador*, `ROLE_ADMIN` na configuração) e `coordenacao` (perfil
*Coordenador*, `ROLE_VIEWER`), criados na primeira subida.
Para usar outros logins e nomes, redefina a lista `ponto.seguranca.usuarios-iniciais` em
`backend/config/application.yml`. As senhas `PONTO_ADMIN_SENHA` e `PONTO_VIEWER_SENHA` só são usadas quando o
usuário ainda não existe no banco. Se não forem definidas, a senha é gerada, é **provisória** (precisa ser trocada
no primeiro acesso) e aparece só no console e no log geral, `ponto logs` (`Senha provisória gerada para
'coordenacao': ...`); ela não vai para a tela *Logs* nem para os logs por pessoa. As demais pessoas são cadastradas na tela
**Usuários** (veja [Vários usuários](#vários-usuários) e [Controle de acessos](#controle-de-acessos)).

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
| `ponto voltar` | Volta para a versão de antes do último `ponto atualizar` (rodar de novo desfaz) — veja abaixo |
| `ponto backup [motivo]` · `ponto backup lista` | Backup do banco agora · os backups guardados (veja [Backup](#backup)) |
| `ponto console` | Roda no próprio terminal, com a saída na tela — para diagnosticar um erro de subida |
| `ponto desinstalar` | Remove a tarefa e o comando (mantém configuração, logs, banco e PDFs) |

A porta é a mesma do desenvolvimento de propósito: **não rode o serviço e o Eclipse ao mesmo tempo** (os dois
importariam os mesmos PDFs). Para desenvolver, `ponto parar`; ao terminar, `ponto iniciar`. Se o Eclipse
estiver com a porta, o serviço espera e sobe sozinho quando ela liberar. O front-end em modo de desenvolvimento
(`npm run dev`) funciona com qualquer um dos dois.

### Voltar para a versão anterior

O `ponto atualizar` guarda a versão que sai em `app\conferencia-ponto.anterior.jar` e só troca o jar depois que a
versão nova foi copiada inteira. Se a versão nova não subir (ou subir com um problema), **`ponto voltar`** põe a
anterior no lugar e a sobe; a que saiu fica guardada, e `ponto voltar` de novo desfaz a volta.

O banco não volta junto. Se a versão que saiu aplicou uma migração (o `ponto atualizar` mostra o backup
`antes-de-atualizar` que fez), a versão anterior pode não funcionar com o banco migrado: restaure esse backup (veja
[Backup](#backup)) antes de usar o sistema.

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
dele se perde). A restauração é feita num banco esvaziado: as tabelas e os schemas criados depois do backup (por
uma atualização, por exemplo) também saem. Restaurar por cima, com `pg_restore --clean`, não serve: o `--clean` só
apaga o que está no backup, e uma tabela nova que aponta para as antigas (a `tb_conferencia_mes` aponta para
`tb_usuario`, por exemplo) faz a restauração parar no meio.

```powershell
ponto parar
$env:PGPASSWORD = Read-Host 'Senha do banco (padrão: ponto)'   # digitada aqui, não fica no histórico
$bin = 'C:\Program Files\PostgreSQL\18\bin'
# 1. esvazia o banco (o usuário ponto é o dono dele)
& "$bin\psql.exe" --host=localhost --username=ponto --dbname=conferencia_ponto -v ON_ERROR_STOP=1 `
    -c 'DROP SCHEMA IF EXISTS atendimento, conhecimento CASCADE; DROP SCHEMA public CASCADE; CREATE SCHEMA public;'
# 2. restaura (inteiro ou nada)
& "$bin\pg_restore.exe" --no-owner --single-transaction --exit-on-error `
    --host=localhost --username=ponto --dbname=conferencia_ponto "$HOME\.conferencia-ponto\backups\<pasta>\banco.dump"
Remove-Item Env:PGPASSWORD
```

Se o passo 2 falhar, o banco fica vazio: corrija o que a mensagem disser e rode o passo 2 de novo (o passo 1 também,
se algo chegou a ser restaurado). Se o backup tiver `atendimentos-base\` e `chave-mestra`, copie-os de volta para
`%USERPROFILE%\.conferencia-ponto\atendimentos\` (`base\` e `chave-mestra`). Se o backup é de antes de uma
atualização, volte também para a versão do sistema daquela época (veja [Voltar para a versão
anterior](#voltar-para-a-versão-anterior)) antes de `ponto iniciar`: a versão nova aplicaria de novo as migrações.

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

## Menu lateral

As telas ficam num **menu lateral**, agrupadas por área: **Ponto** (Painel, Auditoria, Conciliação RH, Folgas e
feriados, Equipe), **Atendimentos** (Meus atendimentos, Novo atendimento; Minha chave do Gemini só com o motor
`gemini`), **Base de
conhecimento** (Pesquisar) e **Administração** (Usuários, Perfis de acesso, Logs). Cada item só aparece para quem
tem a permissão da tela (veja [Controle de acessos](#controle-de-acessos)). Cada grupo abre
e fecha, e o botão no pé do menu o **recolhe** para uma faixa só com os ícones (aí o grupo abre os itens ao lado,
e *Esc* ou um clique fora fecha). O navegador lembra o menu recolhido e os grupos fechados. No alto fica uma barra
fina com o caminho da tela (*Ponto › Painel*), o "Dados de", o nome (*Minha conta*), o sino e *Sair*. No celular o
menu vira uma gaveta, aberta pelo botão ☰.

## Vários usuários

Cada pessoa tem o próprio acesso e os próprios dados: batidas, horário, banco de horas (ciclo), ausências,
lançamentos, notificações, relatórios do RH e divergências. **Feriados valem para todos.**

- **Cadastro (tela *Usuários*, permissão "Cadastrar usuários"):** nome, login, perfis e uma **senha
  provisória** (gerada na tela). No primeiro acesso a pessoa cria a própria senha. Usuários não são excluídos —
  são **desativados** (não entram mais, a pasta deixa de ser monitorada e o histórico fica). Ninguém se desativa.
- **Perfis:** cada pessoa tem um ou mais (veja [Controle de acessos](#controle-de-acessos)). De fábrica:
  *Usuário* registra e confere o próprio ponto; *Coordenador* só consulta o ponto de todos; *Administrador* tem
  tudo.
- **"Dados de" (quem consulta o ponto da equipe):** escolhe de quem são os dados em tela. Vendo outra pessoa,
  todas as telas ficam **somente leitura** — ninguém altera o ponto de outra pessoa. Na API, as leituras
  aceitam `?usuario=login`; as alterações são sempre do usuário do token.
- **Horário de trabalho (*Minha conta*):** cada dia da semana com até 3 períodos (dia sem período = sem
  expediente, todo o tempo é crédito) e a tolerância por marcação. Mudar o horário vale **a partir de uma
  data**: os dias dali em diante são recalculados e os anteriores continuam com o horário que valia para eles.
  Quem trabalha de terça a sábado, por exemplo, tem o sábado como dia útil. A própria pessoa ou quem configura o
  ponto da equipe alteram; o horário não muda dentro de um ciclo do banco já fechado.
- **Comprovantes (*Minha conta*):** cada pessoa escolhe a **pasta** onde os PDFs chegam — no computador onde
  o sistema roda, uma pasta compartilhada na rede (`\\NOME-DO-PC\Ponto`) ou uma pasta sincronizada da
  nuvem. O botão *Testar acesso* confere se o servidor enxerga a pasta, e duas pessoas não podem usar a mesma.
  Também dá para **enviar os PDFs pela tela** (botão *Enviar PDFs* no painel ou arrastando os arquivos para a
  página), com as mesmas regras de duplicidade.
- **Atualização a partir da versão de um usuário só:** a migração `V12` passa todos os dados existentes para
  o primeiro administrador, grava o horário padrão para ele e a pasta de `ponto.importacao-pdf.diretorio`
  passa a ser a pasta dele (só na primeira subida, se ninguém tiver pasta). Os saldos não mudam.

## Controle de acessos

O que cada pessoa pode fazer vem de **permissões**, uma por tela e por ação, juntadas em **perfis**:

- **Permissões** (a lista fica no código; cada módulo declara as dele): *Ponto* — ter o próprio ponto, lançar no
  próprio ponto (requer o anterior), consultar o ponto da equipe, configurar o ponto da equipe (horário, pasta e
  planilha de outras pessoas; requer consultar), conferir o mês (requer consultar), cadastrar feriados; *Atendimentos* — usar os atendimentos, ver o
  espaço em disco; *Base de conhecimento* — pesquisar, curar (requer pesquisar); *Administração* — cadastrar
  usuários, perfis e acessos, integração com o Google, logs. Uma permissão sem o requisito não vale.
- **Perfis** (tela *Perfis de acesso*): um nome, uma descrição e as permissões que libera. Vêm três de fábrica:
  *Administrador* (tudo, inclusive o que vier depois; não muda), *Usuário* e *Coordenador* (podem ser editados,
  não excluídos). Dá para criar outros (ex.: *Atendente*). Mudar um perfil vale na hora para todos que o têm.
- **Cada pessoa** (tela *Usuários* → *Acesso*): um ou mais perfis — ela pode a **soma** do que eles liberam — e
  **ajustes só dela**, permissão por permissão: *pelo perfil*, *liberar* (a mais) ou *retirar* (mesmo que um perfil
  dê). A tela mostra na hora o que vale e por quê ("pelo perfil Usuário", "liberada só para esta pessoa", "não vale
  sem Pesquisar na base").
- **Vale na hora:** o token leva só o login; a cada requisição o servidor relê a pessoa e confere as permissões dela
  (rotas do ponto e dos módulos). As telas abertas de quem mudou se atualizam sozinhas; quem ganhou o próprio ponto
  recebe o horário padrão e o ciclo do banco.
- **Proteções:** sempre sobra pelo menos uma pessoa ativa com *Perfis e acessos* e ninguém tira essa permissão de si
  mesmo (`SEM_QUEM_CUIDE_DOS_ACESSOS`, `ALTERAR_PROPRIO_ACESSO`). Desativar ou trocar a senha de alguém só pode quem
  tem pelo menos os mesmos acessos que a pessoa (`ACESSO_MAIOR_QUE_O_SEU`). Quem tem *Perfis e acessos* consegue dar
  a si mesmo qualquer outra permissão: é só para quem administra o sistema.
- **Histórico:** cada mudança (perfil criado, editado ou excluído; perfis e ajustes de uma pessoa) fica em
  `tb_historico_acesso` com quem fez e quando, e aparece em *Perfis de acesso* e no acesso de cada pessoa.
- **Migração `V16`:** cria os perfis e passa cada pessoa para o perfil equivalente ao que tinha (ADMIN →
  Administrador, USER → Usuário, VIEWER → Coordenador); as liberações antigas dos módulos viram ajustes (menos para o
  administrador, que já tem tudo). Ninguém ganha nem perde acesso. As tabelas antigas (`tb_role`, `tb_usuario_role`,
  `atendimento.acesso`, `conhecimento.acesso`) ficam como estavam, sem uso.

| Método | Rota (`/api/v1/acessos`, com *Perfis e acessos*) | |
|--------|------|---|
| GET | `/permissoes` | as permissões, em grupos: `[{nome, permissoes: [{codigo, nome, descricao, requer}]}]` |
| GET · POST | `/perfis` | lista (com quem tem cada um) · cria `{nome, descricao?, permissoes}` |
| PUT · DELETE | `/perfis/{id}` | altera `{nome, descricao?, permissoes}` · exclui (sai de quem tinha) |
| GET · PUT | `/usuarios/{id}` | perfis, ajustes e o que vale (e por quê) · troca `{perfis: [id], ajustes: [{permissao, liberada}]}` |
| GET | `/historico?usuario=&limite=` | as últimas mudanças |

## Equipe agora (quem está online)

A tela **Equipe** (`/equipe`, para quem tem o próprio ponto ou consulta o da equipe) lista **todos os usuários ativos** e diz quem está
trabalhando agora. A regra fica no servidor (`ConsultarPresencaUseCase`); a tela só mostra:

| Situação hoje | Aparece como |
|---|---|
| Dia marcado como férias, folga, licença, atestado ou abono | **Offline** · o motivo (e "até dd/mm"), mesmo que haja batida |
| Entrada batida e saída ainda não | **Online** · "Trabalhando desde 08:02" (avisa quando é fora do horário cadastrado) |
| Saída batida e o horário ainda tem período pela frente | Offline · "Em intervalo desde 12:01" |
| Saída do último período (ou depois do fim do horário) | Offline · "Encerrou o expediente às 17:50" |
| Sem batida: feriado · dia sem expediente · antes do horário | Offline · "Feriado: …" · "Sem expediente hoje" · "O expediente começa às 08:00" |
| Sem batida com o horário já começado · já terminado | Offline, em destaque · "Ainda não bateu o ponto" · "Não registrou ponto hoje" |
| Sem o próprio ponto (ex.: só a coordenação) | Offline · "não registra ponto aqui" |

Todos veem a situação de todos. O detalhe é reservado: para colegas, **atestado e abono aparecem só como
"Ausência justificada"**; a justificativa, as batidas e o atalho "ver o ponto de…" ficam com quem consulta o ponto
da equipe e com a própria pessoa. A tela se atualiza sozinha quando alguém bate o ponto (evento
`presenca-atualizada`, que não carrega dado nenhum) e a cada minuto. Quem importa o ponto por comprovante (PDF)
aparece online assim que o comprovante da entrada chega ao sistema.

## Atendimentos e base de conhecimento (em construção)

Dois módulos que vivem dentro do sistema sem fazer parte do ponto: o **gerador de textos de atendimento** (lê o
PDF da conversa do Digisac, analisa os anexos com a IA e redige o resumo ou o chamado para o
desenvolvimento) e a **base de conhecimento** (pesquisa nos textos confirmados). Têm schemas, rotas, telas e
regras de segurança próprios (`br.com.conferenciaponto.modulos`, `frontend/src/modulos`); o ponto não depende
deles. Chegam em etapas; por enquanto existem as permissões dos módulos, a chave do Gemini, a leitura do PDF do Digisac, o
download dos anexos, o envio das ligações, do vídeo e dos prints, a análise de cada arquivo pelo Gemini (inclusive os
passos e os quadros dos vídeos), a redação do resumo e dos chamados para o desenvolvimento e a base de conhecimento
com a pesquisa exata:

- **IA local (o padrão, `atendimento.ia.motor: local`)**: o modelo roda **neste computador**, sem chave, sem cota,
  sem custo e sem nada sair daqui. O **Ollama** (`http://localhost:11434`) roda o `gemma4:12b`, que lê as imagens,
  os documentos e os quadros dos vídeos e escreve os textos, e o `embeddinggemma` (vetores da pesquisa por
  significado); o **Whisper** (whisper.cpp, `whisper-cli.exe` com o modelo `ggml-large-v3-turbo.bin` em
  `%USERPROFILE%\.conferencia-ponto\ia-local\`) transcreve os áudios e a fala dos vídeos. O que o modelo não lê
  sozinho chega pronto: o vídeo vira 16 quadros espalhados do começo ao fim (pelo ffmpeg, que passa a ser necessário
  para os vídeos), o PDF vai em texto e nas 4 primeiras páginas como imagem (PDFBox), WebP e HEIC viram PNG. As
  análises saem no mesmo esquema JSON (o Ollama obriga o formato), e os textos em **texto corrido, com as regras da
  habilidade** (as mesmas do app do Gemini), lidos pelo `TextoColadoDoGemini` e conferidos como o texto colado (o
  modelo grava "gemma4:12b (IA local)", sem destaque de trecho sem fonte). Uma tarefa de IA de cada vez (a placa é
  uma só). **Placa de vídeo ocupada** (um jogo aberto): pelo `nvidia-smi`, quando sobra menos que
  `atendimento.ia.local.placa.memoria-necessaria` (8 GB) para a IA, a tarefa nem começa: espera
  (`PLACA_OCUPADA`, "Aguardando a placa de vídeo ficar livre"), sem gastar tentativa, e confere de novo a cada 2
  min; o modelo sai da placa na hora e, depois do último uso, em 2 min (`manter-carregado`). Ollama parado:
  `IA_LOCAL_PARADA` (novas tentativas; esgotadas, pausa com o que fazer). Modelo não instalado: pausa
  (`IA_LOCAL_SEM_MODELO`). Com a IA local, a tela não mostra a chave do Gemini, o painel do app nem os tokens
  (`/meu-acesso` informa `iaLocal`). As análises e os textos feitos pelo Gemini antes continuam; uma falha do
  Gemini volta a ser tentada pela IA local no Processar ou no Retomar. O `ponto` mostra a situação ("IA local:
  Ollama com gemma4:12b e embeddinggemma"; "Áudios: Whisper"). **Instalação** (uma vez, ~10 GB): `winget install
  Ollama.Ollama`; `ollama pull gemma4:12b` e `ollama pull embeddinggemma` (numa rede em que o download do Ollama
  trava no IPv6, os mesmos arquivos podem ser baixados pelo IPv4 com o `curl -4` e conferidos pelo SHA-256 do
  manifesto); o whisper.cpp para Windows com CUDA (`whisper-cublas-12.4.0-bin-x64.zip`, das versões do
  ggml-org/whisper.cpp) em `ia-local\whisper\` e o `ggml-large-v3-turbo.bin` (huggingface.co/ggerganov/whisper.cpp)
  em `ia-local\modelos\`. Para voltar à API do Gemini: `atendimento.ia.motor: gemini` no `config/application.yml`.
- **Acesso**: as permissões *Usar os atendimentos*, *Ver o espaço em disco dos atendimentos*, *Pesquisar na base*
  e *Curar a base* (editar qualquer registro e mudar a situação; requer pesquisar), dadas por um perfil ou por um
  ajuste da pessoa (veja [Controle de acessos](#controle-de-acessos)). Não dependem das permissões do ponto: quem só
  consulta o ponto pode usar os atendimentos. Os módulos declaram as permissões deles (`PermissoesDoAtendimento`,
  `PermissoesDaBase`), que aparecem na tela de perfis do ponto.
- Os grupos **Atendimentos** e **Base de conhecimento** só aparecem no menu lateral de quem tem a permissão. Toda rota
  dos módulos confere a permissão a cada requisição (`403 MODULO_NAO_LIBERADO`); retirar vale na hora.
- **Minha chave do Gemini** (só com `atendimento.ia.motor: gemini`; botão na tela Atendimentos): cada pessoa cadastra a própria chave da API do Gemini
  (Google AI Studio, projeto com faturamento) e confirma que ela é do nível pago — no gratuito o Google pode usar
  o conteúdo enviado. O servidor testa a chave com o Google antes de guardar (listando um modelo, sem gastar cota),
  guarda cifrada (AES-256-GCM) e nunca a devolve: a tela mostra só os 4 últimos caracteres. A chave vai ao Google
  só no cabeçalho `x-goog-api-key`, nunca na URL nem no log. A **chave mestra** que cifra as chaves fica em
  `%USERPROFILE%\.conferencia-ponto\atendimentos\chave-mestra` (`atendimento.chave-mestra.arquivo`), fora do
  banco, legível só pelo usuário do serviço, e entra no `ponto backup`. Se ela se perder, as chaves guardadas
  deixam de abrir (`CHAVE_MESTRA_TROCADA`) e cada pessoa cadastra a sua de novo.
- **Novo atendimento**: a pessoa solta o PDF da conversa exportada do Digisac e vê na hora o que foi lido —
  contato, chamado, período, mensagens (lado, remetente e horário), eventos do sistema e anexos (imagem, áudio,
  documento, vídeo), cada anexo ligado à mensagem em que foi enviado, e até quando os links valem (o Digisac
  assina por 24 h a partir da exportação). A leitura é feita pelo servidor, sem IA (`LeitorConversaDigisac`, com
  PDFBox); a versão do leitor fica gravada em cada atendimento. **Antes de guardar**, a chave temporária do bot e os
  dados de acesso remoto (IDs e senhas de UltraViewer, AnyDesk, TeamViewer...) viram `[omitido]`; o telefone do
  contato não é guardado. O PDF chega em fluxo (sem multipart, até `atendimento.pdf.tamanho-maximo`, 50 MB) e fica
  em `%USERPROFILE%\.conferencia-ponto\atendimentos\<id>\conversa.pdf`. Cada pessoa vê só os próprios
  atendimentos (o de outra pessoa responde 404, inclusive para o administrador). Enviar de novo o PDF de um chamado
  que já tem atendimento não cria outro: a tela oferece abrir o existente (e, se o PDF novo trouxe links novos, os
  dos anexos que faltam são renovados). Recusas: `PDF_INVALIDO`, `PDF_PROTEGIDO`, `PDF_NAO_E_DIGISAC`, `PDF_LAYOUT_DESCONHECIDO` (o formato do
  Digisac mudou: a tela mostra o protocolo para avisar o administrador), `PDF_GRANDE_DEMAIS`, `PDF_SEM_MENSAGENS`.
- **Download dos anexos** (botão *Baixar os anexos*, na tela do atendimento ou logo depois de enviar o PDF): cada
  anexo vira uma tarefa na fila do módulo (`atendimento.tarefa`), que roda em segundo plano — a tela pode ser
  fechada e o serviço reiniciado: ao subir, o que estava em execução volta para a fila e o download **continua de
  onde parou** (`Range`). São até 8 tarefas ao mesmo tempo, 4 por pessoa e 4 downloads (`atendimento.fila.*`,
  `atendimento.download.paralelos`); uma falha passageira espera 10 s, depois 20 s, 40 s... até 10 min, e na 5ª
  tentativa o anexo fica como *falhou*, com o motivo. O link vem de um arquivo enviado pela pessoa, então o
  servidor só baixa por HTTPS, dos hosts de `atendimento.digisac.hosts-permitidos`, sem seguir redirecionamento e
  recusando endereço de rede interna; o link sai do banco depois do download e nunca vai para o log nem para a
  tela. O tipo é conferido pelos bytes (não pela extensão): Word, Excel, GIF e outros que o Gemini não lê ficam
  como *não suportado*. A tela acompanha ao vivo pelo canal SSE do módulo (`GET /atendimentos/eventos`, evento
  `atendimento-progresso`, só para o dono). Por anexo há **Tentar de novo**, **Enviar o arquivo à mão** (quando o
  link venceu ou o download falhou) e **Tirar**. Com links vencidos, soltar na tela do atendimento um PDF novo do
  mesmo chamado renova os links que faltam. Com o disco abaixo de `atendimento.armazenamento.espaco-minimo`
  (200 MB), o atendimento fica **pausado** (`DISCO_CHEIO`) até a pessoa retomar.
- **Ligações, vídeo e prints**: a pessoa escolhe o que são e solta os arquivos (vários de uma vez); cada um vai em
  fluxo com a data de modificação, que entra na linha do tempo. Limites por tipo em `atendimento.envio.tamanho-maximo`
  (imagem e documento 50 MB, áudio 500 MB, vídeo 2 GB). Ficam em `atendimentos\<id>\arquivos\` com nomes como
  `ligacao_001_<nome>`.
- **Análise de cada arquivo** (Gemini, com a chave da própria pessoa): depois de baixado ou enviado, cada imagem,
  áudio, documento e vídeo vira uma tarefa "analisar" na mesma fila (o vídeo, abaixo). É uma chamada
  por arquivo, com a resposta em JSON num esquema por tipo: do print, a tela (nome, código, menu), os campos, as
  mensagens literais e se o texto está legível; do áudio, a transcrição completa com quem fala e o instante (MM:SS),
  sem resumir; do documento, o tipo, o conteúdo e os dados. A instrução comum manda descrever só o que está visível
  ou audível, escrever "não identificado" no que não der para ler e nunca reproduzir dados de acesso. Imagem até
  `atendimento.gemini.inline-maximo` (15 MB) vai no próprio pedido, em alta resolução; áudio, documento e imagem
  maior vão pela Files API (envio retomável), e o arquivo é apagado do Google ao fim. A resposta passa pela
  mesma máscara da conversa antes de gravar (`atendimento.analise`, com o modelo, a versão do prompt e os tokens) e
  **não é refeita**: o mesmo arquivo — ou outro com o mesmo conteúdo, da mesma pessoa — na mesma versão e no mesmo
  modelo reaproveita a análise; trocar o modelo em `atendimento.gemini.modelos` faz analisar de novo no próximo
  "Processar". Na tela, cada arquivo mostra a análise (aberta ali mesmo, com "copiar a transcrição" no áudio), e o
  processamento mostra os tokens gastos. Erros: sem chave (`SEM_CHAVE_GEMINI`, com o caminho para cadastrar), chave
  recusada (`CHAVE_GEMINI_RECUSADA`, pausa as análises), limite por minuto (`GEMINI_LIMITE`, espera o tempo pedido),
  cota do dia (`GEMINI_COTA_DIARIA`, pausa as análises — os downloads seguem — até a pessoa clicar em Retomar),
  `GEMINI_INDISPONIVEL` (Gemini sobrecarregado ou fora: novas tentativas), `GEMINI_BLOQUEOU` (o item falha, os
  outros seguem), `GEMINI_RESPOSTA_INVALIDA` (uma nova tentativa na hora; depois, falha do item) e `GEMINI_MODELO`.
  **Nenhum texto é escrito sem as análises por uma falha passageira**: se o Gemini continua sobrecarregado (ou o
  limite por minuto não libera, ou cai a internet) depois das tentativas, o atendimento **pausa** com o motivo e o
  arquivo que faltou, os textos esperam, e o **Retomar** recomeça as tentativas. "Processar", "Retomar" e "Gerar de
  novo" pedem antes as análises que falharam por motivo passageiro (e as de prompt anterior) e só depois escrevem; o
  Retomar também pede de novo os textos que falharam. Texto escrito sem o conteúdo de um arquivo (análise que falhou
  de vez, arquivo não baixado ou não suportado) diz isso em "Confira antes de usar", e, quando a análise desse arquivo
  chega depois (Tentar de novo, Retomar), **o texto é gerado de novo sozinho** — menos o editado à mão e o confirmado.
- **Vídeo** (o vídeo de reprodução enviado pela pessoa ou um vídeo da conversa): vai pela Files API (até
  `atendimento.gemini.video.tamanho-maximo`, 2 GB; maior: `VIDEO_GRANDE_DEMAIS`), e o Gemini olha
  `atendimento.gemini.video.quadros-por-segundo` quadros por segundo (1, o padrão; 2 ou 3 não perdem ações rápidas,
  mas custam mais; mudar a taxa faz o vídeo ser analisado de novo no próximo "Processar"). A resposta diz se o vídeo
  reproduz um problema ou explica um **pedido** (com o resumo do pedido, cada item pedido — colunas, campos, filtros,
  totais, formato — e o modelo mostrado, como uma planilha) e traz os **passos** com o instante (MM:SS), a tela, as
  ações (cliques, atalhos, o que foi digitado), as mensagens literais, o que aparece na tela e importa (colunas,
  campos e valores, título do relatório), o que acontece e o melhor instante para a imagem do passo, e a narração
  transcrita. Logo depois, o **ffmpeg** tira o
  quadro de cada passo naquele instante (PNG na resolução original, em `atendimentos\<id>\arquivos\quadros\`); os
  que faltarem são tirados de novo antes de cada redação. Os quadros entram nas imagens que o texto pode usar
  (`V1@02:15`, ou `A4@02:15` no vídeo da conversa) e, citados, viram "Imagem N" como os prints. O ffmpeg é opcional
  (`atendimento.ffmpeg.caminho`; vazio = procurar no PATH e onde o `winget install Gyan.FFmpeg` o coloca, sem
  precisar reiniciar o sistema): sem ele (`FFMPEG_AUSENTE`), o vídeo é analisado do mesmo jeito, os passos ficam sem
  quadro e a tela e o texto avisam; um quadro que o ffmpeg não tira (`FFMPEG_FALHOU`) só fica de fora. A versão
  encontrada aparece no log da subida e no `ponto`.
- **Resumo do atendimento** (Gemini, `atendimento.gemini.modelos.redacao`): os textos são escolhidos no Novo
  atendimento (o resumo e os chamados de erro e de alteração, abaixo) ou pedidos depois, na tela do atendimento. Quando não sobra download nem análise na fila, o servidor monta a **linha do
  tempo** (sem IA): as mensagens na ordem do PDF, a análise de cada anexo logo depois da mensagem em que ele foi
  enviado e as ligações, o vídeo e os prints pela data do arquivo; o arquivo sem data, ou com data fora do
  atendimento (mais `atendimento.linha-do-tempo.margem`, 1 h), vai para "material sem horário". Cada item ganha um
  identificador (M12 para mensagens e eventos, A3 para anexos, L1 ligações, V1 vídeos, P1 prints), e a linha do tempo
  é o **único material** da redação. A resposta vem em JSON (assunto, trechos com as fontes e as imagens, campos da
  base) e passa pela **conferência do servidor**: fonte que não existe sai, imagem fora das disponíveis é
  descartada (nada de tela inventada), cada imagem entra uma vez e é numerada na ordem do texto, dados de acesso
  remoto que escaparem viram `[omitido]`, e o assunto ganha o padrão `Digisac: <contato> : <assunto>` com até
  150 caracteres (passando, uma chamada pede um mais curto; se não vier, corta na última palavra). Tudo o que a
  conferência mudou aparece na tela como aviso. O HTML é montado pelo servidor, com o texto escapado. Na tela de
  resultado: o assunto com o contador, a prévia (clicar num trecho mostra de onde ele veio; trecho sem fonte fica
  destacado), a edição do texto (limpa no servidor pelo jsoup, com lista de tags permitidas), as imagens numeradas
  (tirar, voltar, reordenar: o servidor renumera; `Imagem_01.png`... no .zip, na ordem do texto), **Gerar de novo**
  (uma versão nova, com as análises guardadas) e três formas de copiar: **com imagens** (HTML com as imagens
  embutidas), **só o texto** (HTML com "Imagem N" no lugar delas, para anexar os arquivos numerados) e o
  **código-fonte** (para o botão de código-fonte do editor do chamado). Em endereço sem conexão segura
  (`http://<ip>:8080`) a cópia usa o modo alternativo do navegador (dois cliques). Os prompts e os esquemas ficam em
  `backend/src/main/resources/atendimento/prompts` e `esquemas` (`<nome>-v<N>`; mudar um prompt é criar a versão
  seguinte). Erros: os mesmos da análise, mais `SEM_LINHA_DO_TEMPO`, `JA_EM_REDACAO` e `PROCESSE_PRIMEIRO`.
- **Chamados para o desenvolvimento** (erro e alteração): o mesmo caminho do resumo (linha do tempo, conferência,
  edição, imagens e cópia), escritos em terceira pessoa e em partes fixas, na ordem do modelo do chamado. O de
  **erro**: caminho da tela, relato, comparação, passos para reproduzir (um por parágrafo), resultado esperado e obtido,
  dados do registro afetado, desde quando ocorre, o que já foi tentado, causa e pedido ao desenvolvimento; a causa
  sem fonte sai como "Causa não identificada." e o Gemini marca as variantes que reconheceu (erro por comparação,
  sem causa identificada, após atualização), mostradas na tela. O de **alteração** abre com "Solicitação de
  Melhoria": módulo e telas, tipo de solicitação, descrição (o que criar ou mudar, concreto), contexto e objetivo,
  comportamento atual e esperado, **detalhamento** (no relatório: novo ou qual muda, cada coluna, filtros, totais,
  ordem e formato; na tela: cada campo e ação), regras de negócio sugeridas, observação comercial e **pontos a
  confirmar com o cliente** (o que o desenvolvimento precisa e o atendimento não disse, como pergunta). Parte sem
  nada nas fontes fica de fora (o servidor descarta parte que não é do modelo). O **cabeçalho** (Tela/Funcionalidade, Versão do cliente, Testado na última versão
  disponível e, se houver, Chamado relacionado) e o **rodapé** (no de erro, a data de atualização do sistema; nos
  dois, o caminho do backup) são montados pelo servidor na hora de mostrar e de copiar, com os **dados que a pessoa
  informa** (no Novo atendimento, ao marcar um chamado, ou depois, na tela do resultado; valem para os dois
  chamados): mudar um dado não exige gerar o texto de novo, e campo vazio sai como "não informado". A versão do
  cliente é a data da última atualização do sistema no cliente (um dado só, que sai no cabeçalho e no rodapé). A
  Tela/Funcionalidade vem do Gemini ou, se ele não informar, é sugerida pelo código de tela lido nas imagens
  (`tela Nome (COD)` na linha do tempo); dá para corrigir na tela. Limites: versão 40, backup 300, chamado
  relacionado e Tela/Funcionalidade 200 caracteres (`CAMPO_LONGO`, `TELA_LONGA`).
- **Gerar pelo app do Gemini** (só com o motor `gemini`; sem usar a cota da API; em Textos gerados, abaixo do texto): quando a cota da chave
  acaba ou o Gemini da API fica sobrecarregado, o texto sai pelo app (gemini.google.com), que tem limites próprios.
  **Uma vez só**, a pessoa cria no app a **habilidade** `resumo-de-atendimento` (Configurações → Habilidades → enviar
  o `SKILL.md`, baixado no rodapé do painel; as instruções ficam em `atendimento/prompts/habilidade-v<N>.txt`: os
  comandos `resumo`, `erro`, `alteração` e `base`, as regras da redação e o formato da resposta). Quando a versão
  muda, o rodapé avisa para enviar o `SKILL.md` de novo. **Em cada atendimento**: escolher **Resumo**, **Erro** ou
  **Alteração** e clicar em **Baixar os arquivos e abrir o Gemini**. O clique copia o comando (`Use a habilidade
  resumo-de-atendimento: resumo`), abre o Gemini numa aba nova e baixa, um depois do outro, os arquivos do pacote,
  montado em `arquivos\app-gemini\` do atendimento (refeito a cada vez; a retenção apaga junto):
  `Atendimento_<chamado>.pdf`, com a conversa nas primeiras páginas (a linha do tempo, com o identificador de cada
  item e onde está o conteúdo de cada arquivo) e, depois dela, as imagens e os PDFs, um por página e com o
  identificador no alto (WebP e HEIC viram PNG pelo ffmpeg); `Audios.mp3` com os áudios um depois do outro, com 1 s
  de silêncio e o mapa de tempos na conversa (sem o LAME fica `Audios.wav`; sem o ffmpeg vão um por um, `.oga` como
  `.ogg`); e os vídeos e o resto à parte, como `<id>_<nome>`. No Gemini, a pessoa arrasta os arquivos e cola o
  comando; a resposta vem em texto corrido pronto para o chamado (`Assunto: Digisac: <contato> : ...`, nos chamados
  `Tela/Funcionalidade:` e as partes com `## Título`, e `[Imagem A3: legenda]` onde a imagem entra). A tela avisa o
  que conferir: arquivo que não está no servidor ou que o Gemini não lê, mais de 10 arquivos, mais de 10 min de áudio
  ou 5 min de vídeo (os limites do app sem assinatura), arquivo de mais de 100 MB. Não há .zip: o app não lê áudio
  nem vídeo dentro de um .zip. **Colar a resposta de volta é opcional** (Colar a resposta aqui): o servidor lê o
  texto (`TextoColadoDoGemini`: tira o Markdown e os números, separa as partes pelos títulos, um passo por linha
  nas partes de passos, as marcações de imagem e os dados para a base se vierem junto), tira os quadros de vídeo
  citados e passa pela **mesma conferência** da API (imagens, assunto, dados de acesso remoto), no tipo escolhido.
  O texto preenche a versão que esperava (o atendimento pausado pela cota) ou vira a versão seguinte, com o modelo
  "habilidade do Gemini (colado)", sem tokens e sem destaque de trecho sem fonte (o app não indica as fontes); a
  fila da API não o escreve de novo. Uma resposta em JSON no esquema da API também é aceita ("app do Gemini
  (colado)"). Erros: `PACOTE_NAO_MONTADO`, `RESPOSTA_VAZIA`, `RESPOSTA_SEM_TEXTO`, `RESPOSTA_FORA_DO_FORMATO` (o
  JSON fora do esquema), `TEXTO_SENDO_ESCRITO` (a API está escrevendo esse texto agora), `TEXTO_MUDOU` e
  `ARQUIVO_DO_PACOTE_NAO_ENCONTRADO`.
- **Texto corrido e a foto do acesso remoto**: os textos não levam numeração; o passo de um passo a passo é um
  parágrafo curto com a imagem logo depois (resumo no prompt v3, regras da redação v3). A foto ou o print da tela do
  programa de acesso remoto (reconhecida pela descrição da análise da imagem: "tela do UltraViewer", "janela do
  AnyDesk"...; a tela do sistema vista pelo acesso remoto continua valendo) mostra o ID e a senha: não é oferecida ao
  texto, não vai no pacote para o Gemini e a tela avisa.
- **Confirmar e enviar para a base** (no fim da tela do texto): o autor revisa o **registro** já preenchido com o que
  o Gemini preparou para a base — título (o assunto sem o "Digisac: contato :"), tipo (dúvida, erro ou alteração),
  cliente, contato, módulo, tela e código, sintoma, mensagem de erro literal, causa, resolução, situação e, nos
  chamados, o número do chamado no desenvolvimento ("não identificado" fica em branco, para não entrar na pesquisa).
  Ao confirmar, o gerador marca o texto como confirmado e publica o evento `TextoConfirmado`; a base (que só
  conhece esse evento) cria o registro e **copia as imagens** do texto para `atendimentos\base\<id do registro>\`
  (`Imagem_01.png`...), a pasta que o `ponto backup` leva e a retenção não toca. Tudo numa transação só: se a base
  recusar (campo longo demais, `REGISTRO_INVALIDO`; base não liberada para o autor, `SEM_PERMISSAO_NA_BASE`), o texto
  continua pronto e nenhuma cópia fica. Depois de confirmado, o texto não muda mais na tela do atendimento (continua
  dando para copiar e baixar as imagens; `TEXTO_CONFIRMADO` se tentar mudar), e confirmar de novo dá
  `TEXTO_JA_CONFIRMADO`. Imagem que a retenção já apagou não vai, com aviso. Se uma versão anterior do mesmo texto já
  foi para a base, a tela avisa que confirmar a nova cria outro registro.
- **Marcações em vermelho** (experimental, `atendimento.imagens.marcacoes`, ligado por padrão; cada imagem começa
  sem): nas imagens do texto que têm análise de imagem, a caixa **Marcações em vermelho** desenha uma borda vermelha
  em volta das áreas que a análise apontou (a mensagem de erro, o campo com problema; até 5, nas coordenadas de 0 a
  1000 que o Gemini devolve) numa **cópia em PNG** em `arquivos\marcadas\` do atendimento (a retenção apaga junto);
  o original não muda. Com a marcação ligada, a cópia é a imagem que vale na miniatura, na cópia com imagens, no .zip
  (`Imagem_NN.png`) e na base, ao confirmar; desligar volta ao original e apaga a cópia. As áreas ficam guardadas na
  imagem do texto na primeira vez (ligar de novo usa as mesmas, mesmo que a análise mude). Quadro de vídeo, imagem
  sem áreas na análise (`SEM_AREAS`) e formato que o servidor não abre (só PNG, JPEG, GIF e BMP; `MARCACAO_FALHOU`)
  não marcam; texto confirmado não muda (`TEXTO_CONFIRMADO`). A precisão depende do Gemini: confira em telas reais.
- **Base de conhecimento** (menu, para quem pesquisa): a **pesquisa exata** em português e sem diferença de acento
  nem de maiúsculas, pelo índice de busca do PostgreSQL (`tsvector`, mantido por gatilho; pesos: título, código,
  tela e erro > sintoma, causa, módulo e cliente > resolução; quem procura "configurações" acha "configuração"), mais
  **trigramas** para o que a busca por palavras não acha: um pedaço do código da tela (`eqr7` acha `EQR76`), a
  mensagem de erro com uma palavra diferente e um pedaço do nome do cliente. Os resultados vêm do que mais bate para
  o que menos bate, com o que bateu **destacado** no título, no código e num trecho (`ts_headline`, com os acentos de
  volta); sem texto, do mais novo para o mais antigo. Filtros: tipo, situação, cliente, módulo, autor, período e só
  soluções validadas; 20 por página; a pesquisa fica no endereço da página. O **registro** mostra a identificação, o
  problema e a resolução, o texto confirmado (como foi para o chamado) e as imagens. O autor e quem **cura** a base
  editam os campos, mudam a **situação** (resolvido no suporte, enviado ao desenvolvimento, corrigido na versão X,
  não será feito; com o número do chamado e **histórico** de quem mudou e quando), marcam a **solução como
  validada** e apagam; quem só pesquisa lê. Cada mudança leva a versão que a pessoa viu: se outra pessoa mudou antes,
  nada muda e a tela pede para recarregar (`409 REGISTRO_JA_ALTERADO`). O registro não depende do atendimento: a
  retenção apaga o atendimento e o registro continua inteiro, com as imagens.
- **Pesquisa por significado** (`conhecimento.vetor.*`): cada registro ganha um **vetor** (`gemini-embedding-2`, 768
  valores) calculado pelo Gemini com a chave do autor — título, sintoma, mensagem de erro, causa e resolução. Os vetores
  são gerados em segundo plano logo depois que o registro é criado ou editado (e a cada minuto, para os pendentes), com
  novas tentativas: sem chave, sem cota ou com o Gemini fora, o registro fica pendente e aparece só na pesquisa exata
  até o vetor sair. Na pesquisa, o texto digitado também vira vetor (com a chave de quem pesquisa; o mesmo texto não é
  calculado de novo) e é comparado com os dos registros na memória (cosseno); os parecidos acima de
  `similaridade-minima` entram junto com os da pesquisa exata, e a ordem funde as duas posições (quem aparece bem nas
  duas sobe). A tela diz quando a pesquisa por significado está ligada, marca os resultados que entraram só por ela
  (com o quanto são parecidos) e avisa quando ficou só a exata (sem chave, Gemini fora) e quantos registros ainda estão
  sem vetor. Os vetores ficam no banco (`conhecimento.vetor`) e são lidos para a memória na subida; trocar o modelo
  ou o tamanho na configuração faz recalcular todos aos poucos. O cálculo é do gerador (que guarda as chaves): a base
  só pede o vetor, pelo pacote que o gerador publica.
- **Aviso de caso parecido**: na tela dos chamados para o desenvolvimento, antes de copiar ou confirmar, aparecem até
  5 registros da base parecidos pelo significado (acima de `conhecimento.parecidos.similaridade-minima`) ou da mesma
  tela, com a situação, o número do chamado e o quanto são parecidos — para não abrir um chamado repetido. Os do
  próprio atendimento não entram; sem a chave do Gemini, só os da mesma tela, com aviso.
- **Retenção** (todo dia às 03:30, `atendimento.retencao.*`): os arquivos (PDF, anexos, ligações, vídeo e prints)
  são apagados 30 dias depois da criação do atendimento — a conversa lida e as análises continuam, e a tela avisa
  — e o atendimento inteiro, 180 dias depois.
- **Espaço em disco**: em *Meus atendimentos*, quem tem *Ver o espaço em disco dos atendimentos* vê quanto os
  arquivos dos atendimentos ocupam, quanto sobra no disco e quantos atendimentos estão pausados por falta de espaço.

| Método | Rota | Descrição |
|---|---|---|
| GET | `/atendimentos/meu-acesso` · `/conhecimento/meu-acesso` | Quem tem sessão: `{gerador, espaco, iaLocal}` · `{pesquisar, curar}` |
| GET | `/atendimentos/chave-gemini` | A minha chave: `{cadastrada, ultimosCaracteres, nivelPagoConfirmado, situacao, testadaEm, atualizadaEm, exigirNivelPago}` |
| PUT | `/atendimentos/chave-gemini` | `{chave, nivelPagoConfirmado}`: testa com o Google e guarda cifrada (`CHAVE_GEMINI_RECUSADA`, `NIVEL_PAGO_NAO_CONFIRMADO`, `GEMINI_INDISPONIVEL`, `SEM_INTERNET`...) |
| POST · DELETE | `/atendimentos/chave-gemini/testar` · `/atendimentos/chave-gemini` | Testa de novo (situação: `valida`, `sem_cota`, `recusada`) · apaga |
| POST | `/atendimentos` | Corpo = o PDF do Digisac (`application/pdf`), cabeçalho `X-Nome-Arquivo` (codificado): `201 {criado: true, atendimento, leitura}` · `200 {criado: false, ...}` se o chamado já tem atendimento |
| GET | `/atendimentos` · `/atendimentos/{id}` | Os meus atendimentos, do mais novo para o mais antigo · a conversa lida (já mascarada), os anexos com a validade dos links (o link em si nunca vem) e os dados dos chamados (`campos`) |
| PUT | `/atendimentos/{id}/campos` | Os dados dos chamados: `{versaoCliente, testadoUltimaVersao (true, false ou null), caminhoBackup, chamadoRelacionado}` (`CAMPO_LONGO`) |
| DELETE | `/atendimentos/{id}` | Apaga o atendimento e os arquivos dele |
| POST | `/atendimentos/{id}/processar` · `/cancelar` · `/retomar` | Põe na fila os downloads, as análises e as saídas pedidas que faltam (corpo opcional `{saidas: ["resumo", "dev_erro", "dev_alteracao"]}`, `SAIDA_DESCONHECIDA`) · cancela o que ainda não começou · retoma o pausado. Devolvem o progresso `{atendimentoId, situacao, motivoPausa, percentual, arquivos[{id, situacao, erro, tamanho, analise, analiseCodigo, analiseErro}], tokensEntrada, tokensSaida, semChaveGemini, saidas[{id, tipo, versao, situacao, erro, assunto}]}` |
| GET | `/atendimentos/{id}/progresso` · `/atendimentos/eventos` | O progresso agora · SSE do módulo (`atendimento-progresso`, só os meus atendimentos) |
| POST | `/atendimentos/{id}/arquivos/{arquivoId}/tentar-de-novo` | O download ou a análise que falhou volta para a fila (`NADA_A_TENTAR`, `SEM_LINK`, `LINK_VENCIDO`) |
| GET | `/atendimentos/{id}/arquivos/{arquivoId}/analise` | A análise do arquivo: `{categoria, modelo, versaoPrompt, atual, tokensEntrada, tokensSaida, resultado}`; no vídeo, também `quadros[{passo, instanteMs, instante, disponivel}]` e `avisoDosQuadros` (`ANALISE_NAO_ENCONTRADA`) |
| GET | `/atendimentos/{id}/arquivos/{arquivoId}/quadros/{instanteMs}` | O quadro de um passo do vídeo, em PNG (`QUADRO_NAO_ENCONTRADO`) |
| GET · POST | `/atendimentos/{id}/saidas` · `/atendimentos/{id}/saidas/{tipo}/gerar` | A versão mais recente de cada texto · gerar de novo (`JA_EM_REDACAO`, `PROCESSE_PRIMEIRO`) |
| GET · POST | `/atendimentos/app-gemini/habilidade` · `/atendimentos/{id}/app-gemini/pacote` | A habilidade do app do Gemini `{versao, nome, instrucoes, skillMd}` · monta o pacote: `{arquivos[{nome, tamanho, conteudo}], avisos, audioMs, videoMs, versaoDaHabilidade}` |
| GET | `/atendimentos/{id}/app-gemini/arquivos/{nome}` | Um arquivo do último pacote (`ARQUIVO_DO_PACOTE_NAO_ENCONTRADO`) |
| POST | `/atendimentos/{id}/app-gemini/{tipo}/resposta` | `{resposta}` colada do app do Gemini (texto da habilidade ou JSON): vira o texto e devolve o progresso (`RESPOSTA_SEM_TEXTO`, `TEXTO_SENDO_ESCRITO`...) |
| GET | `/atendimentos/{id}/linha-do-tempo` | O material da redação (as fontes de cada trecho) |
| GET | `/atendimentos/{id}/saidas/{saidaId}` | O texto: assunto, HTML (`data-fontes`, `data-imagem`), imagens, avisos e campos da base; nos chamados, `telaFuncionalidade`, `cabecalho`, `rodape` e `variantes` |
| PUT | `.../saidas/{saidaId}/tela` | `{telaFuncionalidade}` do cabeçalho do chamado (`SO_NOS_CHAMADOS`, `TELA_LONGA`) |
| PUT · POST | `.../saidas/{saidaId}/assunto` · `/texto` · `/imagens` · `/desfazer` | `{assunto}` até 150 (`ASSUNTO_LONGO`) · `{html}` editado (limpo no servidor) · `{incluidas: [ids]}` na ordem (o servidor renumera) · volta ao texto gerado |
| GET | `.../saidas/{saidaId}/copia?modo=com_imagens\|so_texto\|codigo_fonte` | `{html, texto, avisos}` para a área de transferência (nos chamados, com o cabeçalho e o rodapé) |
| GET | `.../saidas/{saidaId}/imagens/{imagemId}` · `/imagens.zip` | Uma imagem · todas, na ordem do texto (`IMAGEM_APAGADA` depois da retenção) |
| PUT | `.../saidas/{saidaId}/imagens/{imagemId}/marcacao` | `{ligada}`: liga ou desliga as marcações em vermelho da imagem; devolve o texto (`SEM_AREAS`, `MARCACAO_FALHOU`, `MARCACOES_DESLIGADAS`, `TEXTO_CONFIRMADO`) |
| POST | `.../saidas/{saidaId}/confirmar` | O registro revisado `{titulo, tipo, cliente, contato, modulo, telaNome, telaCodigo, sintoma, mensagemErro, causa, resolucao, situacao, chamadoDev}`: confirma o texto e cria o registro na base. Devolve `{saida, avisos}` (`TEXTO_JA_CONFIRMADO`, `TEXTO_MUDOU`, `REGISTRO_INVALIDO`, `SEM_PERMISSAO_NA_BASE`, `IMAGEM_NAO_COPIADA`) |
| GET | `/conhecimento/registros?q=&tipo=&situacao=&cliente=&modulo=&autor=&de=&ate=&validadas=&pagina=` | A pesquisa: `{registros[{id, tipo, situacao, titulo[], telaCodigo[], trecho[], porSignificado, similaridade, ...}], total, pagina, paginas, tamanho, comTexto, significado{ativa, aviso, pendentes}}`; os destaques vêm em partes `{texto, destaque}` (`FILTRO_INVALIDO`, `PERIODO_INVALIDO`) |
| POST | `/conhecimento/registros/parecidos` | `{titulo, texto, telaCodigo, atendimentoId}`: `{registros[{id, titulo, situacao, chamadoDev, versaoCorrecao, telaCodigo, similaridade, mesmaTela}], porSignificado, aviso}` |
| GET | `/conhecimento/autores` · `/conhecimento/registros/da-saida/{saidaId}` | Quem já escreveu registros (o filtro) · `{id}` do registro de um texto confirmado |
| GET | `/conhecimento/registros/{id}` · `/conhecimento/registros/{id}/imagens/{n}` | O registro, as imagens, o histórico e se quem vê pode mudar (`REGISTRO_NAO_ENCONTRADO`) · a imagem N |
| PUT | `/conhecimento/registros/{id}` | (autor ou quem cura) `{versao, titulo, tipo, cliente, ...}` (`REGISTRO_JA_ALTERADO`, `SEM_PERMISSAO_NA_BASE`, `REGISTRO_INVALIDO`) |
| PUT · POST | `/conhecimento/registros/{id}/situacao` · `/validar-solucao` | (autor ou quem cura) `{versao, situacao, chamadoDev, versaoCorrecao}`, com histórico · `{versao, validada}` |
| DELETE | `/conhecimento/registros/{id}` | (autor ou quem cura) apaga o registro e as imagens dele |
| PUT | `/atendimentos/{id}/arquivos?origem=ligacao\|video\|print_extra&nome=&modificadoEm=` | Corpo = o arquivo: `201` com o arquivo guardado (`ORIGEM_INVALIDA`, `TIPO_NAO_SUPORTADO`, `ARQUIVO_GRANDE_DEMAIS`, `ARQUIVO_VAZIO`, `409 DISCO_CHEIO`) |
| PUT | `/atendimentos/{id}/arquivos/{arquivoId}/conteudo` | Envio à mão do arquivo de um anexo da conversa (cabeçalho `X-Nome-Arquivo`) |
| DELETE | `/atendimentos/{id}/arquivos/{arquivoId}` | Tira o arquivo (`ARQUIVO_EM_USO` se ele estiver aberto em outro programa) |
| PUT | `/atendimentos/{id}/pdf` | PDF novo do mesmo chamado: renova os links dos anexos que faltam (`CHAMADO_DIFERENTE`) |
| GET | `/atendimentos/espaco` | (com `atendimentos.espaco`) `{ocupado, livre, pausados}` em bytes |

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
atualização do sistema, a aba aberta se recarrega sozinha, indo para a tela que a pessoa pediu (se o sistema ainda
está reiniciando, avisa e espera ele voltar; se a tela continua faltando depois de recarregar, só avisa, sem laço).
Um defeito da própria tela mostra uma frase genérica
e é relatado ao servidor (`POST /erros-de-tela`), caindo no log do usuário.

**Logs.** Três lugares, a mesma informação:

| Onde | O quê |
|---|---|
| Console | Ao vivo: `ponto logs [usuario] [-Erros]` (serviço) ou a própria saída do `.\iniciar.ps1` / Eclipse |
| `logs/conferencia-ponto.log` | Tudo, com `[usuario\|protocolo]` em cada linha |
| `logs/usuarios/<login>/<aaaa-mm-dd>/<hh>h.log` | **Um arquivo por usuário e por hora** (`sistema` = o que não é de uma pessoa: subida, tarefas agendadas); guardados por `ponto.logs.dias` (30) dias |
| Tela **Logs** (`logs.ver`) | "Ao vivo" (atualiza a cada 2 s; filtros por pessoa, gravidade e texto/protocolo; pausar) e "Arquivos por pessoa e hora" |

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
| Carga semanal | Quando a carga informada passa do horário, a diferença ÷ dias com expediente entra no banco em cada dia útil fechado (à parte do saldo do dia, como um lançamento). Ver [Carga semanal e fins de semana](#carga-semanal-e-fins-de-semana-tela-banco-de-horas). |
| Jornada em andamento | Número ímpar de batidas: saldo = `null` (fora das consolidações até fechar). |

Na subida, todos os dias gravados são recalculados com a regra atual (`RecalculoJornadasNaInicializacao`).
`MotorCalculoJornadaServiceTest` confere 25 dias reais do relatório do RH (inclusive com 6 batidas).

## API (`/api/v1`)

Toda resposta JSON usa o envelope `{ sucesso, dados, erros[{codigo, mensagem, campo}], timestamp }` (nos erros,
também `protocolo`); toda resposta leva o cabeçalho `X-Protocolo`.
Todas as rotas, exceto o login, exigem `Authorization: Bearer <token>`.

| Método | Rota | Descrição |
|---|---|---|
| POST | `/auth/login` | `{login, senha}` → `{token, tipo, expiraEm, usuario{login, nome, perfis, permissoes}}` |
| GET | `/auth/me` | Usuário da sessão (`perfis`, `permissoes`, `titular`, `podeVerTodos`, `podeEscrever`, `trocarSenha`, `pastaComprovantes`) |
| PUT | `/auth/senha` | `{senhaAtual, novaSenha}` (mín. 8 caracteres) — qualquer pessoa; encerra a senha provisória |
| GET | `/usuarios/titulares` | Pessoas com dados de ponto que o usuário pode consultar |
| GET/POST | `/usuarios` | lista (`usuarios.gerenciar` ou `acessos.gerenciar`) · cria `{login, nome, perfis?, senhaProvisoria, pastaComprovantes?}` (`usuarios.gerenciar`; os perfis, só com `acessos.gerenciar`) |
| PUT | `/usuarios/{id}` · `/{id}/senha` · `/{id}/pasta` | `{nome, ativo}` · `{senhaProvisoria}` (`usuarios.gerenciar`) · `{pasta}` (`ponto.equipe.configurar`) |
| PUT | `/conta/pasta` · POST `/conta/pasta/verificar` | Pasta dos meus comprovantes `{pasta}` (vazia = sem monitoramento) · só confere o acesso |
| GET/POST | `/horarios` | Vigências do horário (com a soma, a carga, a diferença e os `avisos` da lei) · novo horário `{vigenteDesde, toleranciaMinutos, dias: {SEG: "08:00-12:00 13:00-17:48", ...}, cargaSemanalSegundos?}` (outra pessoa, com `ponto.equipe.configurar`: `?usuario=login`) |
| POST | `/horarios/avaliacao` | O mesmo corpo, sem salvar: a soma, a carga, a diferença e os avisos da lei |
| GET | `/banco-de-horas?ano=&mes=` | A tela Banco de horas: `horario`, `compensacao`, `resumo`, `bancoDeHoras`, `dias` (com comprovantes, histórico e `diferencaDaCargaSegundos`), `semExpediente`, `semRegistro`, `excluidos`, `avisos`, `conferencia` |
| POST | `/banco-de-horas/conferencia?ano=&mes=&usuario=` · `/conferencia/reabrir` | (`ponto.conferir`) marca o mês como conferido `{observacao?}` · reabre `{motivo}` |
| DELETE | `/horarios/{id}` | Remove uma vigência (os dias voltam ao horário anterior) |
| GET | `/configuracao` | Horário da pessoa que vale hoje (grade, tolerância, jornada), data/hora do servidor |
| GET | `/jornadas?ano=&mes=` | Dias do mês + resumo (saldo mensal e anual acumulado, com `segundosLancados` e `segundosDaCarga`) + `conferencia` do mês (null = não conferido) |
| GET | `/jornadas/{data}` | Um dia |
| POST | `/jornadas/batidas` | Próxima batida `{data?, horario?}` (vazio = relógio do servidor) |
| POST | `/jornadas/manual` | `{data, intervalos:[{entrada, saida}], justificativa?}` — só fim de semana/feriado; fica no histórico do dia |
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
| GET | `/auditoria?ano=&mes=` | Dias do mês + comprovantes de cada batida (com `urlDownload`) + resumo + `bancoDeHoras` no fim do mês pelo ciclo `{saldoSegundos, cicloInicio, cicloFim, cicloAberto}` (null fora dos ciclos) |
| GET | `/planilha/exportar` | Planilha de conferência em Excel (`.xlsx`): resumo + uma aba por mês |
| GET | `/planilha` | Planilha do Google da pessoa: `{situacao, emailServico, url, titulo, sincronizadaEm, erro}` |
| PUT | `/planilha` | `{link}` vincula a planilha do Google e grava tudo nela (outra pessoa, com `ponto.equipe.configurar`: `?usuario=login`) |
| POST | `/planilha/sincronizar` · DELETE `/planilha` | Regrava agora · deixa de gravar (idem) |
| GET/PUT/DELETE | `/integracoes/google` | (`integracoes.gerenciar`) conta de serviço: consulta · envia a chave `{chave: "<conteúdo do .json>"}` · desliga |
| GET | `/comprovantes/{id}/download` | PDF original (`attachment`); também em `/api/comprovantes/{id}/download` |
| GET | `/presenca` | Equipe agora: todos os usuários ativos com `{online, situacao, situacaoRotulo, motivo, desde, horario, ...}` |
| GET | `/logs/ao-vivo?depois=&nivel=&login=&texto=&semConsultas=` | (`logs.ver`) últimas linhas de log, para a tela consultar a cada 2 s |
| GET | `/logs/arquivos` · `/logs/arquivo?login=&data=&hora=` | (`logs.ver`) pastas por usuário → dias → horas · o log daquela hora |
| POST | `/erros-de-tela` | `{mensagem, tela, detalhe}` — a tela relata um defeito dela (qualquer pessoa com sessão) → `202` |
| GET | `/eventos` | Stream SSE (`text/event-stream`) com as atualizações em tempo real |
| GET | `/importacoes?limite=` | Monitor da pasta da pessoa (`situacao`: ATIVO, INDISPONIVEL, SEM_PASTA...) + últimos comprovantes |
| POST | `/importacoes/reprocessar` | Relê a pasta da pessoa (`202 Accepted`, roda em segundo plano) |
| POST | `/importacoes/enviar` | Comprovantes PDF enviados pela tela (multipart, campo `arquivos`, até 50) |

Nos `GET`, `?usuario=login` consulta outra pessoa (com `ponto.equipe`). Dias, mês e saldos trazem a grade
(`grade` do dia e `expedientes` do mês) pelo horário da pessoa.

Erros: `400` validação/formato · `401` sem sessão/credenciais inválidas (`ACESSO_DESATIVADO` quando o
usuário foi desativado) · `403` sem a permissão (`ACESSO_NEGADO`, `SOMENTE_CONSULTA`, `SEM_PONTO`,
`ALTERAR_DADOS_DE_OUTRO`, `ACESSO_MAIOR_QUE_O_SEU`, `TROCAR_SENHA`) · `404` não encontrado · `409` conflito (`REGISTRO_REPETIDO`, `REGISTRO_JA_ALTERADO`,
`SISTEMA_OCUPADO`, `MES_CONFERIDO`, `MES_JA_CONFERIDO`) · `422` regra de negócio · `503 BANCO_INDISPONIVEL` · `500 ERRO_INTERNO` (com o protocolo na
mensagem) e `500 COMPROVANTE_CORROMPIDO` (arquivo alterado em disco). Ver "Erros, protocolo e logs".

## Permissões nas rotas

Autenticação **stateless** com JWT HS256 (claims `sub` e `nome`; validade de 8 h), senhas com BCrypt. O token não
leva as permissões: a cada requisição o conversor do Spring Security relê a pessoa e as authorities são as
permissões que valem para ela agora (veja [Controle de acessos](#controle-de-acessos)); pessoa desativada ou
apagada recebe `401 ACESSO_DESATIVADO` com o mesmo token. As regras ficam em `SecurityConfig`; a escrita é
decidida **pelo método HTTP**, então uma rota nova de escrita já nasce protegida:

| Rota | Exige |
|---|---|
| `GET /api/**` (leitura, SSE, download) | sessão; de quem são os dados é decidido em `AcessoUsuarios` (os próprios com `ponto.proprio`; os de qualquer pessoa com `ponto.equipe`) |
| `POST/PUT/PATCH/DELETE /api/**` (o resto) | `ponto.lancar` (e sempre nos próprios dados) |
| horários e planilha | `ponto.lancar` (os próprios) ou `ponto.equipe.configurar` (`?usuario=` de outra pessoa) |
| conferir e reabrir o mês (`/banco-de-horas/conferencia/**`) | `ponto.conferir` (de qualquer pessoa, com `?usuario=`) |
| feriados (gravar) · usuários · acessos · integrações · logs | `ponto.feriados` · `usuarios.gerenciar` · `acessos.gerenciar` · `integracoes.gerenciar` · `logs.ver` |

Tela inicial: o Painel para quem tem o próprio ponto; a Auditoria para quem só consulta a equipe; senão a primeira
tela liberada (Atendimentos, Base, Usuários, Logs ou Minha conta).

Fora de `/api/**`, só `GET`/`HEAD` são liberados: são as telas empacotadas no jar (HTML/JS/CSS, sem dados —
`FrontendConfig` devolve o `index.html` para as rotas do Vue); o resto é negado. No front-end, quem só consulta vê o
selo "somente leitura" e os botões de bater ponto, lançamento manual e exclusão nem são renderizados — mas a
garantia é do back-end, não da tela.

**Tela de auditoria (`/auditoria`).** Pensada para a coordenação (quem consulta o ponto da equipe):
filtro por mês/ano, tipo de dia e situação (débito, crédito, fora da tolerância, em andamento,
com/sem comprovante);
para cada batida, o horário real e o considerado, minutos abonados pela tolerância, trabalhado,
previsto e saldo do dia; botão **PDF ⤓** por batida e **n PDFs** por dia; totais do período; o **banco de horas**
pelo ciclo (no mês corrente, o mesmo número do painel; num mês passado, o do fim do mês);
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
  `ponto.banco-horas.inicio-primeiro-ciclo`, padrão `2026-05-25` — dia seguinte ao zeramento de 24/05/2026). O
  saldo do ciclo é apurado na hora, a partir do início: mudar o período muda o banco do painel, da auditoria e da
  planilha.
- **O mesmo banco em todo lugar**: o painel, a auditoria (cartão "Banco de horas") e a planilha (coluna "Banco de
  horas no fim do mês") mostram o acumulado do ciclo que cobre o mês (quando um ciclo fecha no meio do mês, vale o
  seguinte). O acumulado do ano (de janeiro em diante, em `/saldos`) não é o banco de horas: ele atravessa o
  fechamento do RH.
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

## Carga semanal e fins de semana (tela Banco de horas)

Quem trabalha de vez em quando no fim de semana para completar as horas da semana (ex.: um sábado de manhã por
mês, porque o horário de segunda a sexta não fecha a carga):

- **Carga semanal** (*Minha conta → Horário de trabalho*): o total que a pessoa deve cumprir por semana, junto do
  horário (entrada, intervalos e saída de cada dia), com a mesma vigência. Vazia = a soma do horário (o normal: nada
  muda). A tela compara na hora: "o horário soma 40:00; a carga é 42:00: faltam 02:00 por semana (00:24 por dia
  útil)".
- **No banco:** a diferença da semana ÷ dias com expediente entra no banco em **cada dia útil fechado** (débito; se o
  horário passa da carga, crédito). Feriados, férias, folgas e dias sem registro não levam a diferença, como já não
  levam a jornada do dia. O saldo de cada dia (contra a grade, como o RH) **não muda**: a diferença soma à parte,
  como um lançamento (`SaldoMensal.segundosDaCarga`, `DiferencaDaCarga`). A conciliação com o RH continua comparando
  só as jornadas.
- **O fim de semana compensa:** sábado de manhã, à tarde ou domingo, quando for melhor (pelo ponto ou pelo
  *Lançamento manual*, que agora pede um motivo opcional). As horas do mês primeiro pagam a diferença do mês; o que
  sobra fica de crédito no banco; o que falta no fim do mês fica como débito.
- **Painel:** os dias sem expediente (sábado e domingo, no horário padrão) **não aparecem no cartão**; o lançamento
  continua. O cartão do mês mostra as linhas "Fins de semana" e "Diferença da carga" com o link para a tela nova.
- **Tela Banco de horas** (`/banco-de-horas`): carga x horário, a compensação do mês (a compensar, compensado, falta,
  para o banco e os fins de semana que ainda vêm), o saldo do mês por parte, o banco de horas pelo ciclo e o
  **extrato** (fins de semana e lançamentos; o que foi lançado ou ajustado à mão; o mês inteiro). Cada batida diz a
  origem: **relógio** (com o PDF para baixar), **sem comprovante**, **ajustada à mão** ou **lançada à mão**; cada dia
  tem o histórico (quem, quando, antes → depois, o motivo), inclusive os dias excluídos, e os botões *Ajustar* e
  *Corrigir*. Dia útil que passou sem registro aparece como "ponto não batido".
- **Avisos da lei** (`LeiDaJornada`, só avisos: dá para salvar): até 15/12/2026, 44 h por semana e a folga semanal;
  a partir de 16/12/2026 (EC 141/2026, fim da escala 6x1), 42 h e 2 folgas por semana, jornada normal de 8 h por dia
  (com acordo ou convenção, até 8h24) e compensação no fim de semana só com acordo ou convenção coletiva (pelo menos
  1 folga em toda semana e média de 2 no mês); a partir de 16/12/2027, 40 h. E sempre: no máximo 10 h no dia (art.
  59), intervalo de 1 a 2 h acima de 6 h e de 15 min de 4 a 6 h (art. 71), 11 h entre um dia e outro (art. 66). O
  horário mostra os avisos ao editar e em vigor; os dias trabalhados, na tela Banco de horas.
- **Conferência do mês** (permissão `ponto.conferir`, dada ao perfil Coordenador): quem confere marca um mês que já
  terminou como **conferido** (com observação), e os números da hora ficam guardados. O mês fica **travado** para
  mudanças à mão (ajuste, lançamento, batida de dia passado, exclusão, lançamento no banco, folgas e o horário que
  valeria nele); os PDFs do relógio continuam entrando, e a tela avisa que os números mudaram depois da conferência.
  **Reabrir** pede o motivo; tudo fica no histórico e a pessoa é avisada no sino.
- **Sino:** faltando horas da carga e restando 2 fins de semana (ou 1) no mês; o mês que terminou faltando horas; e
  7 dias seguidos sem folga (a partir de 16/12/2026, semana sem nenhuma folga). Todo dia à 01:00, na subida e quando
  um dia sem expediente é lançado.
- **Planilha de conferência:** a coluna "Diferença da carga" (quando o mês tem), "Origem das batidas" (relógio,
  ajustada, sem comprovante, lançado à mão por quem), o saldo do mês com a diferença, a conferência de cada mês e a
  carga semanal de cada horário.
- **Migração `V17`:** `tb_horario_trabalho.carga_semanal_segundos` (vazia nos horários que já existiam),
  `tb_ajuste_jornada.origem` (AJUSTE, RH, MANUAL, EXCLUSAO; os registros antigos ficam AJUSTE), `tb_conferencia_mes` e a
  permissão `ponto.conferir` no perfil Coordenador. Nenhum número muda sem uma carga informada.

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
  "ajustado à mão", com filtro próprio. O lançamento à mão de um dia sem expediente e a exclusão de um dia também
  ficam no histórico (`tb_ajuste_jornada.origem` = MANUAL ou EXCLUSAO); na planilha de conferência a batida vai em itálico e o motivo, nas
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
