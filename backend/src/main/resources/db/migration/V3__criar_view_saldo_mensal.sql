-- =============================================================================
-- V3 - Consolidação de saldos (mensal e anual acumulado)
--
-- Considera apenas jornadas FECHADAS (saldo_diario_minutos IS NOT NULL), de
-- modo que: saldo_mensal = minutos_trabalhados - minutos_previstos.
-- Dias em andamento são contados em dias_em_aberto para sinalização.
-- =============================================================================

CREATE VIEW vw_saldo_mensal AS
WITH mensal AS (
    SELECT
        EXTRACT(YEAR  FROM r.data_referencia)::INT                                          AS ano,
        EXTRACT(MONTH FROM r.data_referencia)::INT                                          AS mes,
        COUNT(*)::INT                                                                       AS dias_registrados,
        COUNT(*) FILTER (WHERE r.saldo_diario_minutos IS NULL)::INT                         AS dias_em_aberto,
        COALESCE(SUM(r.minutos_trabalhados)      FILTER (WHERE r.saldo_diario_minutos IS NOT NULL), 0)::INT AS minutos_trabalhados,
        COALESCE(SUM(r.jornada_prevista_minutos) FILTER (WHERE r.saldo_diario_minutos IS NOT NULL), 0)::INT AS minutos_previstos,
        COALESCE(SUM(r.saldo_diario_minutos), 0)::INT                                       AS saldo_mensal_minutos
    FROM tb_registro_jornada r
    GROUP BY 1, 2
)
SELECT
    m.ano,
    m.mes,
    m.dias_registrados,
    m.dias_em_aberto,
    m.minutos_trabalhados,
    m.minutos_previstos,
    m.saldo_mensal_minutos,
    SUM(m.saldo_mensal_minutos) OVER (PARTITION BY m.ano ORDER BY m.mes)::INT AS saldo_anual_acumulado_minutos
FROM mensal m;

COMMENT ON VIEW vw_saldo_mensal IS 'Saldo mensal e saldo anual acumulado (running total por ano) do banco de horas.';
