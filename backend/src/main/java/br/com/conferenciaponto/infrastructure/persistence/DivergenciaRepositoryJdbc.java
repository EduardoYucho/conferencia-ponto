package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;
import br.com.conferenciaponto.domain.port.DivergenciaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static br.com.conferenciaponto.infrastructure.persistence.RelatorioRhRepositoryJdbc.horarios;
import static br.com.conferenciaponto.infrastructure.persistence.RelatorioRhRepositoryJdbc.horariosTexto;
import static br.com.conferenciaponto.infrastructure.persistence.RelatorioRhRepositoryJdbc.instante;
import static br.com.conferenciaponto.infrastructure.persistence.RelatorioRhRepositoryJdbc.inteiro;
import static br.com.conferenciaponto.infrastructure.persistence.RelatorioRhRepositoryJdbc.utc;

@Repository
class DivergenciaRepositoryJdbc implements DivergenciaRepository {

    private final JdbcClient jdbc;

    DivergenciaRepositoryJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void salvar(Divergencia d) {
        jdbc.sql("""
                        INSERT INTO tb_divergencia (id, usuario_id, data, relatorio_id, tipo, descricao, aceitavel, motivo_nao_aceitavel,
                            horarios_rh, horarios_local, ocorrencia_rh, tipo_dia_local, saldo_rh_segundos,
                            saldo_local_segundos, status, detectada_em, resolvida_em, resolvida_por, observacao)
                        VALUES (:id, :usuario, :data, :relatorio, :tipo, :descricao, :aceitavel, :motivo, :hrh, :hlocal, :ocorrencia,
                            :tipoDia, :saldoRh, :saldoLocal, :status, :detectada, :resolvida, :por, :observacao)
                        ON CONFLICT (id) DO UPDATE SET
                            relatorio_id = EXCLUDED.relatorio_id, tipo = EXCLUDED.tipo, descricao = EXCLUDED.descricao,
                            aceitavel = EXCLUDED.aceitavel, motivo_nao_aceitavel = EXCLUDED.motivo_nao_aceitavel,
                            horarios_rh = EXCLUDED.horarios_rh, horarios_local = EXCLUDED.horarios_local,
                            ocorrencia_rh = EXCLUDED.ocorrencia_rh, tipo_dia_local = EXCLUDED.tipo_dia_local,
                            saldo_rh_segundos = EXCLUDED.saldo_rh_segundos,
                            saldo_local_segundos = EXCLUDED.saldo_local_segundos, status = EXCLUDED.status,
                            detectada_em = EXCLUDED.detectada_em, resolvida_em = EXCLUDED.resolvida_em,
                            resolvida_por = EXCLUDED.resolvida_por, observacao = EXCLUDED.observacao
                        """)
                .param("id", d.id()).param("usuario", d.usuarioId()).param("data", d.data()).param("relatorio", d.relatorioId())
                .param("tipo", d.tipo().name()).param("descricao", limitar(d.descricao(), 500))
                .param("aceitavel", d.aceitavel()).param("motivo", limitar(d.motivoNaoAceitavel(), 300))
                .param("hrh", horariosTexto(d.horariosRh())).param("hlocal", horariosTexto(d.horariosLocal()))
                .param("ocorrencia", limitar(d.ocorrenciaRh(), 80))
                .param("tipoDia", d.tipoDiaLocal() == null ? null : d.tipoDiaLocal().name())
                .param("saldoRh", d.saldoRhSegundos()).param("saldoLocal", d.saldoLocalSegundos())
                .param("status", d.status().name()).param("detectada", utc(d.detectadaEm()))
                .param("resolvida", utc(d.resolvidaEm())).param("por", d.resolvidaPor())
                .param("observacao", limitar(d.observacao(), 300))
                .update();
    }

    @Override
    public Optional<Divergencia> buscarPorId(UUID id) {
        return jdbc.sql("SELECT * FROM tb_divergencia WHERE id = :id").param("id", id)
                .query(DivergenciaRepositoryJdbc::divergencia).optional();
    }

    @Override
    public List<Divergencia> listar(UUID usuarioId, StatusDivergencia status, LocalDate inicio, LocalDate fim) {
        return jdbc.sql("""
                        SELECT * FROM tb_divergencia
                         WHERE usuario_id = :usuario
                           AND (CAST(:status AS VARCHAR) IS NULL OR status = CAST(:status AS VARCHAR))
                           AND (CAST(:inicio AS DATE) IS NULL OR data >= CAST(:inicio AS DATE))
                           AND (CAST(:fim AS DATE) IS NULL OR data <= CAST(:fim AS DATE))
                         ORDER BY data
                        """)
                .param("usuario", usuarioId)
                .param("status", status == null ? null : status.name())
                .param("inicio", inicio).param("fim", fim)
                .query(DivergenciaRepositoryJdbc::divergencia).list();
    }

    @Override
    public void excluir(UUID id) {
        jdbc.sql("DELETE FROM tb_divergencia WHERE id = :id").param("id", id).update();
    }

    private static Divergencia divergencia(ResultSet rs, int linha) throws SQLException {
        String tipoDia = rs.getString("tipo_dia_local");
        return new Divergencia(rs.getObject("id", UUID.class), rs.getObject("usuario_id", UUID.class),
                rs.getObject("data", LocalDate.class),
                rs.getObject("relatorio_id", UUID.class), TipoDivergencia.valueOf(rs.getString("tipo")),
                rs.getString("descricao"), rs.getBoolean("aceitavel"), rs.getString("motivo_nao_aceitavel"),
                horarios(rs.getString("horarios_rh")), horarios(rs.getString("horarios_local")),
                rs.getString("ocorrencia_rh"), tipoDia == null ? null : TipoDia.valueOf(tipoDia),
                inteiro(rs, "saldo_rh_segundos"), inteiro(rs, "saldo_local_segundos"),
                StatusDivergencia.valueOf(rs.getString("status")), instante(rs, "detectada_em"),
                instante(rs, "resolvida_em"), rs.getString("resolvida_por"), rs.getString("observacao"));
    }

    private static String limitar(String texto, int maximo) {
        return texto == null || texto.length() <= maximo ? texto : texto.substring(0, maximo - 1) + "…";
    }
}
