-- =============================================================================
-- V1 - Registro diário de jornada
--
-- Decisões:
--  * As batidas são gravadas com o horário REAL (precisão de segundos) para fins
--    de auditoria. O horário "considerado" (após tolerância) é derivado pelo
--    motor de cálculo do domínio e não é persistido.
--  * minutos_trabalhados / saldo_diario_minutos são desnormalizados (snapshot do
--    cálculo no momento da gravação) para permitir consolidação via SQL.
--  * saldo_diario_minutos é NULL enquanto a jornada está em andamento
--    (batida de entrada sem a respectiva saída).
-- =============================================================================

CREATE TABLE tb_registro_jornada (
    id                        UUID         NOT NULL DEFAULT gen_random_uuid(),
    data_referencia           DATE         NOT NULL,
    entrada_1                 TIME(0),
    saida_1                   TIME(0),
    entrada_2                 TIME(0),
    saida_2                   TIME(0),
    tipo_dia                  VARCHAR(20)  NOT NULL,
    jornada_prevista_minutos  INTEGER      NOT NULL,
    minutos_trabalhados       INTEGER      NOT NULL DEFAULT 0,
    saldo_diario_minutos      INTEGER,
    registro_manual           BOOLEAN      NOT NULL DEFAULT FALSE,
    criado_em                 TIMESTAMPTZ  NOT NULL DEFAULT now(),
    atualizado_em             TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_registro_jornada PRIMARY KEY (id),
    CONSTRAINT uk_registro_jornada_data UNIQUE (data_referencia),

    CONSTRAINT ck_registro_jornada_tipo_dia
        CHECK (tipo_dia IN ('UTIL', 'FIM_DE_SEMANA', 'FERIADO')),

    -- Dias não úteis têm carga base zero: 100% do trabalhado vira crédito.
    CONSTRAINT ck_registro_jornada_prevista
        CHECK (jornada_prevista_minutos >= 0
               AND (tipo_dia = 'UTIL' OR jornada_prevista_minutos = 0)),

    CONSTRAINT ck_registro_jornada_minutos
        CHECK (minutos_trabalhados BETWEEN 0 AND 1440),

    -- Sequência: nenhuma batida existe sem a anterior.
    CONSTRAINT ck_registro_jornada_sequencia
        CHECK ((saida_1   IS NULL OR entrada_1 IS NOT NULL)
           AND (entrada_2 IS NULL OR saida_1   IS NOT NULL)
           AND (saida_2   IS NULL OR entrada_2 IS NOT NULL)),

    -- Ordem cronológica (sem virada de meia-noite).
    CONSTRAINT ck_registro_jornada_cronologia
        CHECK ((saida_1   IS NULL OR saida_1   >  entrada_1)
           AND (entrada_2 IS NULL OR entrada_2 >= saida_1)
           AND (saida_2   IS NULL OR saida_2   >  entrada_2)),

    -- Saldo só existe quando a jornada está fechada (sem entrada pendente).
    CONSTRAINT ck_registro_jornada_saldo
        CHECK ((saldo_diario_minutos IS NULL) =
               ((entrada_1 IS NOT NULL AND saida_1 IS NULL)
             OR (entrada_2 IS NOT NULL AND saida_2 IS NULL)))
);

COMMENT ON TABLE  tb_registro_jornada IS 'Registro diário de batidas e apuração de saldo (banco de horas).';
COMMENT ON COLUMN tb_registro_jornada.entrada_1 IS 'Horário REAL da batida (auditoria). Tolerância é aplicada no domínio.';
COMMENT ON COLUMN tb_registro_jornada.jornada_prevista_minutos IS 'Carga base do dia: 528 (UTIL) ou 0 (FIM_DE_SEMANA/FERIADO).';
COMMENT ON COLUMN tb_registro_jornada.saldo_diario_minutos IS 'minutos_trabalhados - jornada_prevista_minutos. NULL = jornada em andamento.';
COMMENT ON COLUMN tb_registro_jornada.registro_manual IS 'Flag de auditoria: TRUE para lançamentos manuais (fins de semana/feriados).';
