-- =============================================================================
-- V18: módulo Monitor fiscal (schema fiscal)
-- -----------------------------------------------------------------------------
-- A situação dos serviços dos documentos fiscais (NF-e, NFC-e, CT-e, MDF-e), lida
-- a cada 5 minutos dos painéis públicos da SVRS e por teste de conexão nos outros
-- autorizadores. Nenhuma tabela do ponto muda; só as permissões novas do módulo
-- entram nos perfis Usuário e Coordenador (o Administrador já tem todas).
--
-- * situacao_atual: a última leitura de cada alvo (documento + autorizador +
--   serviço; o serviço "geral" é o resumo do autorizador, o que a tela mostra).
-- * mudanca:        cada troca de situação de um alvo.
-- * incidente:      período em que um autorizador ficou instável ou fora.
-- * resumo_hora:    leituras por hora de cada autorizador (disponibilidade).
-- * rodada:         cada coleta (quando, quantas leituras, sem internet?).
-- * fonte:          a última tentativa de cada fonte (painel ou testes).
-- * aviso:          o aviso de manutenção publicado em cada painel.
-- =============================================================================

CREATE SCHEMA fiscal;

CREATE TABLE fiscal.situacao_atual (
    chave           VARCHAR(140)  NOT NULL,
    documento       VARCHAR(8)    NOT NULL,
    autorizador     VARCHAR(20)   NOT NULL,
    servico         VARCHAR(80)   NOT NULL,
    origem          VARCHAR(20)   NOT NULL,
    situacao        VARCHAR(16)   NOT NULL,
    detalhe         VARCHAR(300),
    cstat           INTEGER,
    tempo_ms        INTEGER,
    verificado_em   TIMESTAMPTZ,
    lida_em         TIMESTAMPTZ   NOT NULL,
    desde           TIMESTAMPTZ   NOT NULL,
    ruins_seguidas  INTEGER       NOT NULL DEFAULT 0,

    CONSTRAINT pk_situacao_atual PRIMARY KEY (chave),
    CONSTRAINT ck_situacao_atual_documento CHECK (documento IN ('NFE', 'NFCE', 'CTE', 'MDFE')),
    CONSTRAINT ck_situacao_atual_origem CHECK (origem IN ('painel_svrs', 'teste_conexao')),
    CONSTRAINT ck_situacao_atual_situacao CHECK (situacao IN ('NORMAL', 'INSTAVEL', 'FORA', 'SEM_INFORMACAO'))
);

CREATE TABLE fiscal.mudanca (
    id           BIGINT        GENERATED ALWAYS AS IDENTITY,
    chave        VARCHAR(140)  NOT NULL,
    documento    VARCHAR(8)    NOT NULL,
    autorizador  VARCHAR(20)   NOT NULL,
    servico      VARCHAR(80)   NOT NULL,
    de           VARCHAR(16),
    para         VARCHAR(16)   NOT NULL,
    detalhe      VARCHAR(300),
    em           TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_mudanca PRIMARY KEY (id)
);
CREATE INDEX ix_mudanca_em ON fiscal.mudanca (em);
CREATE INDEX ix_mudanca_chave_em ON fiscal.mudanca (chave, em);

CREATE TABLE fiscal.incidente (
    id           BIGINT        GENERATED ALWAYS AS IDENTITY,
    chave        VARCHAR(140)  NOT NULL,
    documento    VARCHAR(8)    NOT NULL,
    autorizador  VARCHAR(20)   NOT NULL,
    inicio       TIMESTAMPTZ   NOT NULL,
    fim          TIMESTAMPTZ,
    pior         VARCHAR(16)   NOT NULL,
    detalhe      VARCHAR(300),
    leituras     INTEGER       NOT NULL DEFAULT 1,

    CONSTRAINT pk_incidente PRIMARY KEY (id),
    CONSTRAINT ck_incidente_pior CHECK (pior IN ('INSTAVEL', 'FORA')),
    CONSTRAINT ck_incidente_periodo CHECK (fim IS NULL OR fim >= inicio)
);
CREATE INDEX ix_incidente_inicio ON fiscal.incidente (inicio);
-- um incidente aberto por autorizador
CREATE UNIQUE INDEX uk_incidente_aberto ON fiscal.incidente (chave) WHERE fim IS NULL;

CREATE TABLE fiscal.resumo_hora (
    chave           VARCHAR(140)  NOT NULL,
    hora            TIMESTAMPTZ   NOT NULL,
    documento       VARCHAR(8)    NOT NULL,
    autorizador     VARCHAR(20)   NOT NULL,
    leituras        INTEGER       NOT NULL DEFAULT 0,
    ruins           INTEGER       NOT NULL DEFAULT 0,
    sem_informacao  INTEGER       NOT NULL DEFAULT 0,
    tempo_total_ms  BIGINT        NOT NULL DEFAULT 0,
    tempos          INTEGER       NOT NULL DEFAULT 0,
    tempo_max_ms    INTEGER,

    CONSTRAINT pk_resumo_hora PRIMARY KEY (chave, hora)
);
CREATE INDEX ix_resumo_hora_hora ON fiscal.resumo_hora (hora);

CREATE TABLE fiscal.rodada (
    id                  BIGINT        GENERATED ALWAYS AS IDENTITY,
    inicio              TIMESTAMPTZ   NOT NULL,
    fim                 TIMESTAMPTZ   NOT NULL,
    leituras            INTEGER       NOT NULL,
    fontes_com_problema INTEGER       NOT NULL,
    sem_internet        BOOLEAN       NOT NULL,

    CONSTRAINT pk_rodada PRIMARY KEY (id)
);
CREATE INDEX ix_rodada_inicio ON fiscal.rodada (inicio);

CREATE TABLE fiscal.fonte (
    nome              VARCHAR(60)   NOT NULL,
    ultima_tentativa  TIMESTAMPTZ   NOT NULL,
    ultimo_sucesso    TIMESTAMPTZ,
    erro              VARCHAR(300),

    CONSTRAINT pk_fonte PRIMARY KEY (nome)
);

CREATE TABLE fiscal.aviso (
    documento  VARCHAR(8)    NOT NULL,
    texto      VARCHAR(500)  NOT NULL,
    desde      TIMESTAMPTZ   NOT NULL,
    visto_em   TIMESTAMPTZ   NOT NULL,

    CONSTRAINT pk_aviso PRIMARY KEY (documento)
);

COMMENT ON SCHEMA fiscal IS 'Monitor fiscal: situação dos serviços de NF-e, NFC-e, CT-e e MDF-e por autorizador.';
COMMENT ON COLUMN fiscal.situacao_atual.servico IS 'O serviço do autorizador ("Autorização", "Status"...); "geral" = o resumo do autorizador.';
COMMENT ON COLUMN fiscal.situacao_atual.verificado_em IS 'Quando a fonte verificou (o horário que o painel mostra); vazio no teste de conexão.';
COMMENT ON COLUMN fiscal.incidente.chave IS 'O alvo "geral" do autorizador (documento:autorizador:geral).';

-- -------------------------------------------------------------- permissões
INSERT INTO tb_perfil_permissao (perfil_id, permissao)
SELECT p.id, 'fiscal.ver' FROM tb_perfil_acesso p WHERE p.sistema IN ('ROLE_USER', 'ROLE_VIEWER')
ON CONFLICT DO NOTHING;

INSERT INTO tb_historico_acesso (quem, perfil_id, descricao)
SELECT 'sistema', p.id, 'Perfil ' || p.nome || ': ganhou "Ver o monitor fiscal" (permissão nova)'
  FROM tb_perfil_acesso p WHERE p.sistema IN ('ROLE_USER', 'ROLE_VIEWER');
