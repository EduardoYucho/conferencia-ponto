-- =============================================================================
-- V7 - Cálculo igual ao do sistema de ponto do RH
--
--  * Durações em SEGUNDOS (o RH apura com segundos: 08:05:22 = 5 min 22 s de atraso).
--    As colunas em minutos dão lugar às colunas em segundos. Os valores copiados aqui
--    são provisórios: ao subir, a aplicação recalcula todos os dias com a regra nova
--    (RecalculoJornadasNaInicializacao).
--  * Até 3 intervalos por dia (entrada_3/saida_3), como no RH (ex.: sair às 11:01 para
--    um compromisso e voltar às 11:15, além do almoço).
--  * Comprovantes rejeitados por "4 batidas" voltam a ser processados: a próxima
--    leitura da pasta os importa como 5ª/6ª batida.
-- =============================================================================

ALTER TABLE tb_registro_jornada
    ADD COLUMN entrada_3                 TIME(0),
    ADD COLUMN saida_3                   TIME(0),
    ADD COLUMN jornada_prevista_segundos INTEGER,
    ADD COLUMN segundos_trabalhados      INTEGER,
    ADD COLUMN saldo_diario_segundos     INTEGER;

UPDATE tb_registro_jornada SET
    jornada_prevista_segundos = jornada_prevista_minutos * 60,
    segundos_trabalhados      = minutos_trabalhados * 60,
    saldo_diario_segundos     = saldo_diario_minutos * 60;

DROP VIEW vw_saldo_mensal;

ALTER TABLE tb_registro_jornada
    DROP CONSTRAINT ck_registro_jornada_prevista,
    DROP CONSTRAINT ck_registro_jornada_minutos,
    DROP CONSTRAINT ck_registro_jornada_sequencia,
    DROP CONSTRAINT ck_registro_jornada_cronologia,
    DROP CONSTRAINT ck_registro_jornada_saldo,
    DROP COLUMN jornada_prevista_minutos,
    DROP COLUMN minutos_trabalhados,
    DROP COLUMN saldo_diario_minutos;

ALTER TABLE tb_registro_jornada
    ALTER COLUMN jornada_prevista_segundos SET NOT NULL,
    ALTER COLUMN segundos_trabalhados SET NOT NULL,
    ALTER COLUMN segundos_trabalhados SET DEFAULT 0,

    -- Dias não úteis têm carga base zero: 100% do trabalhado vira crédito.
    ADD CONSTRAINT ck_registro_jornada_prevista
        CHECK (jornada_prevista_segundos >= 0
               AND (tipo_dia = 'UTIL' OR jornada_prevista_segundos = 0)),

    ADD CONSTRAINT ck_registro_jornada_trabalhado
        CHECK (segundos_trabalhados BETWEEN 0 AND 86400),

    -- Sequência: nenhuma batida existe sem a anterior.
    ADD CONSTRAINT ck_registro_jornada_sequencia
        CHECK ((saida_1   IS NULL OR entrada_1 IS NOT NULL)
           AND (entrada_2 IS NULL OR saida_1   IS NOT NULL)
           AND (saida_2   IS NULL OR entrada_2 IS NOT NULL)
           AND (entrada_3 IS NULL OR saida_2   IS NOT NULL)
           AND (saida_3   IS NULL OR entrada_3 IS NOT NULL)),

    -- Ordem cronológica (sem virada de meia-noite).
    ADD CONSTRAINT ck_registro_jornada_cronologia
        CHECK ((saida_1   IS NULL OR saida_1   >  entrada_1)
           AND (entrada_2 IS NULL OR entrada_2 >= saida_1)
           AND (saida_2   IS NULL OR saida_2   >  entrada_2)
           AND (entrada_3 IS NULL OR entrada_3 >= saida_2)
           AND (saida_3   IS NULL OR saida_3   >  entrada_3)),

    -- Saldo só existe quando a jornada está fechada (número par de batidas).
    ADD CONSTRAINT ck_registro_jornada_saldo
        CHECK ((saldo_diario_segundos IS NULL) =
               (num_nonnulls(entrada_1, saida_1, entrada_2, saida_2, entrada_3, saida_3) % 2 = 1));

COMMENT ON COLUMN tb_registro_jornada.segundos_trabalhados IS
    'Tempo bruto entre as batidas (intervalos fechados), como "Hr. Trabalhadas" no relatório do RH.';
COMMENT ON COLUMN tb_registro_jornada.saldo_diario_segundos IS
    'Saldo com a tolerância aplicada ("Hr. Extra/Falta" no RH). NULL = jornada em andamento/incompleta.';

ALTER TABLE tb_comprovante
    DROP CONSTRAINT ck_comprovante_tipo_batida,
    ADD CONSTRAINT ck_comprovante_tipo_batida
        CHECK (tipo_batida IN ('ENTRADA_1', 'SAIDA_1', 'ENTRADA_2', 'SAIDA_2', 'ENTRADA_3', 'SAIDA_3'));

-- Consolidação mensal em segundos (apenas jornadas fechadas entram no saldo).
CREATE VIEW vw_saldo_mensal AS
WITH mensal AS (
    SELECT
        EXTRACT(YEAR  FROM r.data_referencia)::INT                                              AS ano,
        EXTRACT(MONTH FROM r.data_referencia)::INT                                              AS mes,
        COUNT(*)::INT                                                                           AS dias_registrados,
        COUNT(*) FILTER (WHERE r.saldo_diario_segundos IS NULL)::INT                            AS dias_em_aberto,
        COALESCE(SUM(r.segundos_trabalhados)      FILTER (WHERE r.saldo_diario_segundos IS NOT NULL), 0)::INT AS segundos_trabalhados,
        COALESCE(SUM(r.jornada_prevista_segundos) FILTER (WHERE r.saldo_diario_segundos IS NOT NULL), 0)::INT AS segundos_previstos,
        COALESCE(SUM(r.saldo_diario_segundos), 0)::INT                                          AS saldo_mensal_segundos
    FROM tb_registro_jornada r
    GROUP BY 1, 2
)
SELECT
    m.ano,
    m.mes,
    m.dias_registrados,
    m.dias_em_aberto,
    m.segundos_trabalhados,
    m.segundos_previstos,
    m.saldo_mensal_segundos,
    SUM(m.saldo_mensal_segundos) OVER (PARTITION BY m.ano ORDER BY m.mes)::INT AS saldo_anual_acumulado_segundos
FROM mensal m;

COMMENT ON VIEW vw_saldo_mensal IS 'Saldo mensal e anual acumulado do banco de horas, em segundos.';

-- Até 6 batidas com segundos no texto ("HH:MM:SS" x 6 + espaços = 53 caracteres).
ALTER TABLE tb_registro_jornada ALTER COLUMN horarios_ajustados TYPE VARCHAR(60);
ALTER TABLE tb_ajuste_jornada
    ALTER COLUMN batidas_antes  TYPE VARCHAR(60),
    ALTER COLUMN batidas_depois TYPE VARCHAR(60);

-- Batidas que antes excediam o limite de 4 por dia: a próxima leitura da pasta as importa.
DELETE FROM tb_comprovante_ponto
 WHERE status = 'REJEITADO'
   AND mensagem LIKE '%4 batidas%';
