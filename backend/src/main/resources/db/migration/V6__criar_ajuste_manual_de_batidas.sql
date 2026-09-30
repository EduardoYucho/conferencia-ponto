-- =============================================================================
-- V6 - Ajuste manual de batidas
--
-- Caso de uso: o relógio de ponto falhou (batida não registrada / comprovante não
-- gerado) e o RH corrigiu no sistema dele. O ajuste aqui mantém a conferência igual
-- à do RH, com trilha de auditoria:
--  * horarios_ajustados: quais batidas ATUAIS do dia foram incluídas/corrigidas à mão
--    (horários separados por espaço, ex.: '12:00:00 13:02:00'). A marca acompanha o
--    horário, então continua certa quando as batidas mudam de coluna.
--  * tb_ajuste_jornada: histórico somente-inclusão de cada ajuste (antes, depois,
--    justificativa, quem e quando). Não é apagado junto com o registro do dia.
-- =============================================================================

ALTER TABLE tb_registro_jornada
    ADD COLUMN horarios_ajustados VARCHAR(40);

COMMENT ON COLUMN tb_registro_jornada.horarios_ajustados IS
    'Batidas atuais incluídas ou corrigidas manualmente (HH:MM:SS separados por espaço). NULL = nenhuma.';

CREATE TABLE tb_ajuste_jornada (
    id                   UUID          NOT NULL,
    registro_jornada_id  UUID,
    data_referencia      DATE          NOT NULL,
    batidas_antes        VARCHAR(40)   NOT NULL,
    batidas_depois       VARCHAR(40)   NOT NULL,
    justificativa        VARCHAR(500)  NOT NULL,
    usuario_login        VARCHAR(60)   NOT NULL,
    ajustado_em          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_ajuste_jornada PRIMARY KEY (id),
    CONSTRAINT fk_ajuste_jornada_registro FOREIGN KEY (registro_jornada_id)
        REFERENCES tb_registro_jornada (id) ON DELETE SET NULL,
    CONSTRAINT ck_ajuste_jornada_justificativa CHECK (length(btrim(justificativa)) >= 5),
    CONSTRAINT ck_ajuste_jornada_mudou CHECK (batidas_antes <> batidas_depois)
);

CREATE INDEX ix_ajuste_jornada_data ON tb_ajuste_jornada (data_referencia, ajustado_em DESC);

COMMENT ON TABLE tb_ajuste_jornada IS 'Histórico dos ajustes manuais de batidas (auditoria).';
