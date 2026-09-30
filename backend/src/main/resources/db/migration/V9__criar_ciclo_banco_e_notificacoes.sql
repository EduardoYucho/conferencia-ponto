-- =============================================================================
-- V9 - Ciclo do banco de horas (semestral) e notificações
--
-- O saldo do painel passa a ser o do ciclo ABERTO: a contagem recomeça do zero quando o
-- usuário sinaliza o fechamento (o RH zera o banco a cada 6 meses).
--  * data_fim: enquanto ABERTO é a previsão de término; depois de FECHADO, o último dia
--    efetivamente incluído no saldo final.
--  * data_fim_prevista: a previsão original (usada nos alertas e para desfazer um fechamento).
--  * saldo_final_segundos: saldo exato congelado no fechamento.
-- Existe no máximo um ciclo ABERTO e os ciclos não se sobrepõem.
-- =============================================================================

CREATE TABLE tb_ciclo_banco (
    id                   UUID          NOT NULL,
    data_inicio          DATE          NOT NULL,
    data_fim             DATE          NOT NULL,
    data_fim_prevista    DATE          NOT NULL,
    status               VARCHAR(10)   NOT NULL,
    saldo_final_segundos INTEGER,
    fechado_em           TIMESTAMPTZ,
    fechado_por          VARCHAR(60),
    observacao           VARCHAR(200),
    criado_em            TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_ciclo_banco PRIMARY KEY (id),
    CONSTRAINT ck_ciclo_banco_status CHECK (status IN ('ABERTO', 'FECHADO')),
    CONSTRAINT ck_ciclo_banco_periodo CHECK (data_fim >= data_inicio AND data_fim_prevista >= data_inicio),
    CONSTRAINT ck_ciclo_banco_fechamento CHECK (
        (status = 'ABERTO'  AND saldo_final_segundos IS NULL     AND fechado_em IS NULL) OR
        (status = 'FECHADO' AND saldo_final_segundos IS NOT NULL AND fechado_em IS NOT NULL)),
    CONSTRAINT ex_ciclo_banco_sem_sobreposicao
        EXCLUDE USING gist (daterange(data_inicio, data_fim, '[]') WITH &&) DEFERRABLE INITIALLY DEFERRED
);

CREATE UNIQUE INDEX ux_ciclo_banco_um_aberto ON tb_ciclo_banco (status) WHERE status = 'ABERTO';

COMMENT ON TABLE tb_ciclo_banco IS 'Ciclos (semestrais) do banco de horas; o saldo recomeça do zero a cada fechamento.';

CREATE TABLE tb_notificacao (
    id         UUID          NOT NULL,
    tipo       VARCHAR(30)   NOT NULL,
    chave      VARCHAR(120)  NOT NULL,
    titulo     VARCHAR(120)  NOT NULL,
    mensagem   VARCHAR(500)  NOT NULL,
    link       VARCHAR(200),
    criada_em  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    lida_em    TIMESTAMPTZ,

    CONSTRAINT pk_notificacao PRIMARY KEY (id),
    CONSTRAINT uk_notificacao_chave UNIQUE (chave)
);

CREATE INDEX ix_notificacao_criada_em ON tb_notificacao (criada_em DESC);

COMMENT ON COLUMN tb_notificacao.chave IS 'Evita repetir o mesmo aviso (ex.: CICLO_30_DIAS:<id do ciclo>).';
