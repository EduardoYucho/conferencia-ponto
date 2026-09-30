-- =============================================================================
-- V10 - Conciliação com o relatório de banco de horas do RH
--
--  * tb_relatorio_rh: cada PDF enviado. O arquivo NÃO é guardado (tem CPF): ficam o nome,
--    o hash (para não conferir o mesmo relatório duas vezes) e os totais impressos.
--  * tb_relatorio_rh_dia: as linhas do relatório (batidas como texto, na ordem do RH, porque
--    o RH pode ter número ímpar de batidas ou mais de 6).
--  * tb_divergencia: no máximo uma por data, com a "fotografia" dos dois lados e a decisão
--    (aceitar o RH, manter a conferência ou resolvida por outro caminho). Nada é sobrescrito
--    automaticamente: só a ação do usuário altera a conferência.
-- =============================================================================

CREATE TABLE tb_relatorio_rh (
    id                        UUID          NOT NULL,
    nome_arquivo              VARCHAR(255)  NOT NULL,
    hash_sha256               CHAR(64)      NOT NULL,
    funcionario               VARCHAR(120),
    emitido_em                TIMESTAMP(0)  NOT NULL,
    periodo_inicio            DATE          NOT NULL,
    periodo_fim               DATE          NOT NULL,
    total_previsto_segundos   INTEGER,
    total_trabalhado_segundos INTEGER,
    total_saldo_segundos      INTEGER,
    dias_lidos                INTEGER       NOT NULL DEFAULT 0,
    status                    VARCHAR(20)   NOT NULL,
    mensagem                  VARCHAR(500),
    divergencias              INTEGER       NOT NULL DEFAULT 0,
    enviado_em                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    enviado_por               VARCHAR(60),
    processado_em             TIMESTAMPTZ,

    CONSTRAINT pk_relatorio_rh PRIMARY KEY (id),
    CONSTRAINT uk_relatorio_rh_hash UNIQUE (hash_sha256),
    CONSTRAINT ck_relatorio_rh_status CHECK (status IN ('PROCESSANDO', 'CONCLUIDO', 'ERRO')),
    CONSTRAINT ck_relatorio_rh_periodo CHECK (periodo_fim >= periodo_inicio)
);

CREATE TABLE tb_relatorio_rh_dia (
    relatorio_id              UUID          NOT NULL,
    data                      DATE          NOT NULL,
    horarios                  VARCHAR(200)  NOT NULL DEFAULT '',
    ocorrencia                VARCHAR(80),
    jornada_prevista_segundos INTEGER       NOT NULL,
    segundos_trabalhados      INTEGER       NOT NULL,
    saldo_segundos            INTEGER       NOT NULL,

    CONSTRAINT pk_relatorio_rh_dia PRIMARY KEY (relatorio_id, data),
    CONSTRAINT fk_relatorio_rh_dia_relatorio FOREIGN KEY (relatorio_id)
        REFERENCES tb_relatorio_rh (id) ON DELETE CASCADE
);

CREATE INDEX ix_relatorio_rh_dia_data ON tb_relatorio_rh_dia (data);

COMMENT ON COLUMN tb_relatorio_rh_dia.horarios IS 'Batidas na ordem do relatório, "HH:mm:ss" separadas por espaço.';

CREATE TABLE tb_divergencia (
    id                    UUID          NOT NULL,
    data                  DATE          NOT NULL,
    relatorio_id          UUID          NOT NULL,
    tipo                  VARCHAR(30)   NOT NULL,
    descricao             VARCHAR(500)  NOT NULL,
    aceitavel             BOOLEAN       NOT NULL,
    motivo_nao_aceitavel  VARCHAR(300),
    horarios_rh           VARCHAR(200)  NOT NULL DEFAULT '',
    horarios_local        VARCHAR(200)  NOT NULL DEFAULT '',
    ocorrencia_rh         VARCHAR(80),
    tipo_dia_local        VARCHAR(20),
    saldo_rh_segundos     INTEGER,
    saldo_local_segundos  INTEGER,
    status                VARCHAR(20)   NOT NULL,
    detectada_em          TIMESTAMPTZ   NOT NULL,
    resolvida_em          TIMESTAMPTZ,
    resolvida_por         VARCHAR(60),
    observacao            VARCHAR(300),

    CONSTRAINT pk_divergencia PRIMARY KEY (id),
    CONSTRAINT uk_divergencia_data UNIQUE (data),
    CONSTRAINT fk_divergencia_relatorio FOREIGN KEY (relatorio_id)
        REFERENCES tb_relatorio_rh (id) ON DELETE CASCADE,
    CONSTRAINT ck_divergencia_tipo CHECK (tipo IN ('TIPO_DIA', 'SOMENTE_RH', 'SOMENTE_LOCAL', 'BATIDA_FALTANDO',
        'BATIDA_SOBRANDO', 'HORARIO_DIFERENTE', 'SALDO', 'DIFERENCA_SEGUNDOS')),
    CONSTRAINT ck_divergencia_status CHECK (status IN ('PENDENTE', 'ACEITO_RH', 'MANTIDO_LOCAL', 'RESOLVIDA')),
    CONSTRAINT ck_divergencia_resolucao CHECK ((status = 'PENDENTE') = (resolvida_em IS NULL))
);

CREATE INDEX ix_divergencia_status ON tb_divergencia (status, data);
