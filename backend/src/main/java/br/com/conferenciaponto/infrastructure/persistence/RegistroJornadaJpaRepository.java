package br.com.conferenciaponto.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface RegistroJornadaJpaRepository extends JpaRepository<RegistroJornadaEntity, UUID> {

    Optional<RegistroJornadaEntity> findByUsuarioIdAndDataReferencia(UUID usuarioId, LocalDate dataReferencia);

    List<RegistroJornadaEntity> findByUsuarioIdAndDataReferenciaBetweenOrderByDataReferenciaAsc(UUID usuarioId,
                                                                                            LocalDate inicio,
                                                                                            LocalDate fim);

    List<RegistroJornadaEntity> findByDataReferencia(LocalDate dataReferencia);

    @Query("SELECT DISTINCT r.usuarioId FROM RegistroJornadaEntity r")
    List<UUID> usuariosComRegistros();

    @Query(value = """
            SELECT v.ano                           AS "ano",
                   v.mes                           AS "mes",
                   v.dias_registrados              AS "diasRegistrados",
                   v.dias_em_aberto                AS "diasEmAberto",
                   v.segundos_trabalhados           AS "segundosTrabalhados",
                   v.segundos_previstos             AS "segundosPrevistos",
                   v.saldo_mensal_segundos          AS "saldoMensalSegundos",
                   v.saldo_anual_acumulado_segundos AS "saldoAnualAcumuladoSegundos"
              FROM vw_saldo_mensal v
             WHERE v.usuario_id = :usuarioId
               AND v.ano = :ano
             ORDER BY v.mes
            """, nativeQuery = true)
    List<SaldoMensalProjection> consolidarAno(@Param("usuarioId") UUID usuarioId, @Param("ano") int ano);

    /** Mesma consolidação da vw_saldo_mensal, restrita a um período (acumulado desde o início dele). */
    @Query(value = """
            WITH mensal AS (
                SELECT EXTRACT(YEAR  FROM r.data_referencia)::INT                                   AS ano,
                       EXTRACT(MONTH FROM r.data_referencia)::INT                                   AS mes,
                       COUNT(*)::INT                                                                AS dias_registrados,
                       COUNT(*) FILTER (WHERE r.saldo_diario_segundos IS NULL)::INT                 AS dias_em_aberto,
                       COALESCE(SUM(r.segundos_trabalhados)
                                FILTER (WHERE r.saldo_diario_segundos IS NOT NULL), 0)::INT         AS segundos_trabalhados,
                       COALESCE(SUM(r.jornada_prevista_segundos)
                                FILTER (WHERE r.saldo_diario_segundos IS NOT NULL), 0)::INT         AS segundos_previstos,
                       COALESCE(SUM(r.saldo_diario_segundos), 0)::INT                               AS saldo_mensal_segundos
                  FROM tb_registro_jornada r
                 WHERE r.usuario_id = :usuarioId
                   AND r.data_referencia BETWEEN :inicio AND :fim
                 GROUP BY 1, 2
            )
            SELECT m.ano                   AS "ano",
                   m.mes                   AS "mes",
                   m.dias_registrados      AS "diasRegistrados",
                   m.dias_em_aberto        AS "diasEmAberto",
                   m.segundos_trabalhados  AS "segundosTrabalhados",
                   m.segundos_previstos    AS "segundosPrevistos",
                   m.saldo_mensal_segundos AS "saldoMensalSegundos",
                   SUM(m.saldo_mensal_segundos) OVER (ORDER BY m.ano, m.mes)::INT AS "saldoAnualAcumuladoSegundos"
              FROM mensal m
             ORDER BY m.ano, m.mes
            """, nativeQuery = true)
    List<SaldoMensalProjection> consolidarPeriodo(@Param("usuarioId") UUID usuarioId, @Param("inicio") LocalDate inicio,
                                                  @Param("fim") LocalDate fim);
}
