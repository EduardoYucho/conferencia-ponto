package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.ConferenciaMes;
import br.com.conferenciaponto.domain.port.ConferenciaMesRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** tb_conferencia_mes: uma linha por conferência; a reabertura preenche reaberto_em/por (a linha fica). */
@Repository
class ConferenciaMesRepositoryJdbc implements ConferenciaMesRepository {

    private final JdbcClient jdbc;

    ConferenciaMesRepositoryJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void salvar(ConferenciaMes c) {
        jdbc.sql("""
                        INSERT INTO tb_conferencia_mes (id, usuario_id, mes, conferido_em, conferido_por, observacao,
                            saldo_mes_segundos, trabalhado_segundos, dias_registrados, reaberto_em, reaberto_por,
                            motivo_reabertura)
                        VALUES (:id, :usuario, :mes, :em, :por, :observacao, :saldo, :trabalhado, :dias, :reabertoEm,
                            :reabertoPor, :motivo)
                        ON CONFLICT (id) DO UPDATE SET
                            reaberto_em = EXCLUDED.reaberto_em, reaberto_por = EXCLUDED.reaberto_por,
                            motivo_reabertura = EXCLUDED.motivo_reabertura
                        """)
                .param("id", c.id()).param("usuario", c.usuarioId()).param("mes", c.mes().atDay(1))
                .param("em", RelatorioRhRepositoryJdbc.utc(c.conferidoEm())).param("por", c.conferidoPor())
                .param("observacao", c.observacao()).param("saldo", c.saldoMesSegundos())
                .param("trabalhado", c.trabalhadoSegundos()).param("dias", c.diasRegistrados())
                .param("reabertoEm", c.reabertoEm() == null ? null : RelatorioRhRepositoryJdbc.utc(c.reabertoEm()))
                .param("reabertoPor", c.reabertoPor()).param("motivo", c.motivoReabertura())
                .update();
    }

    @Override
    public Optional<ConferenciaMes> vigente(UUID usuarioId, YearMonth mes) {
        return jdbc.sql("SELECT * FROM tb_conferencia_mes WHERE usuario_id = :usuario AND mes = :mes AND reaberto_em IS NULL")
                .param("usuario", usuarioId).param("mes", mes.atDay(1))
                .query(ConferenciaMesRepositoryJdbc::conferencia).optional();
    }

    @Override
    public List<ConferenciaMes> vigentesDesde(UUID usuarioId, YearMonth desde) {
        return jdbc.sql("""
                        SELECT * FROM tb_conferencia_mes
                         WHERE usuario_id = :usuario AND mes >= :desde AND reaberto_em IS NULL
                         ORDER BY mes""")
                .param("usuario", usuarioId).param("desde", desde.atDay(1))
                .query(ConferenciaMesRepositoryJdbc::conferencia).list();
    }

    @Override
    public List<ConferenciaMes> historico(UUID usuarioId, YearMonth mes) {
        return jdbc.sql("""
                        SELECT * FROM tb_conferencia_mes WHERE usuario_id = :usuario AND mes = :mes
                         ORDER BY conferido_em DESC""")
                .param("usuario", usuarioId).param("mes", mes.atDay(1))
                .query(ConferenciaMesRepositoryJdbc::conferencia).list();
    }

    private static ConferenciaMes conferencia(ResultSet rs, int linha) throws SQLException {
        return new ConferenciaMes(rs.getObject("id", UUID.class), rs.getObject("usuario_id", UUID.class),
                YearMonth.from(rs.getObject("mes", LocalDate.class)),
                RelatorioRhRepositoryJdbc.instante(rs, "conferido_em"), rs.getString("conferido_por"),
                rs.getString("observacao"), rs.getInt("saldo_mes_segundos"), rs.getInt("trabalhado_segundos"),
                rs.getInt("dias_registrados"), RelatorioRhRepositoryJdbc.instante(rs, "reaberto_em"),
                rs.getString("reaberto_por"), rs.getString("motivo_reabertura"));
    }
}
