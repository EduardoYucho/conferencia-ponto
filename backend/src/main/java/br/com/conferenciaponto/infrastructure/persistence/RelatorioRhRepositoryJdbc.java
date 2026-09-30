package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.StatusRelatorioRh;
import br.com.conferenciaponto.domain.port.RelatorioRhRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** Relatórios do RH e seus dias (SQL direto: tabelas simples e a consulta do relatório vigente por data). */
@Repository
class RelatorioRhRepositoryJdbc implements RelatorioRhRepository {

    private final JdbcClient jdbc;

    RelatorioRhRepositoryJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void salvar(RelatorioRh r, List<DiaRelatorioRh> dias) {
        jdbc.sql("""
                        INSERT INTO tb_relatorio_rh (id, nome_arquivo, hash_sha256, funcionario, emitido_em, periodo_inicio,
                            periodo_fim, total_previsto_segundos, total_trabalhado_segundos, total_saldo_segundos,
                            dias_lidos, status, mensagem, divergencias, enviado_em, enviado_por, processado_em)
                        VALUES (:id, :nome, :hash, :funcionario, :emitido, :inicio, :fim, :previsto, :trabalhado, :saldo,
                            :dias, :status, :mensagem, :divergencias, :enviado, :por, :processado)
                        """)
                .param("id", r.id()).param("nome", r.nomeArquivo()).param("hash", r.hashSha256())
                .param("funcionario", r.funcionario()).param("emitido", r.emitidoEm())
                .param("inicio", r.periodoInicio()).param("fim", r.periodoFim())
                .param("previsto", r.totalPrevistoSegundos()).param("trabalhado", r.totalTrabalhadoSegundos())
                .param("saldo", r.totalSaldoSegundos()).param("dias", r.diasLidos())
                .param("status", r.status().name()).param("mensagem", r.mensagem())
                .param("divergencias", r.divergencias()).param("enviado", utc(r.enviadoEm()))
                .param("por", r.enviadoPor()).param("processado", utc(r.processadoEm()))
                .update();
        for (DiaRelatorioRh d : dias) {
            jdbc.sql("""
                            INSERT INTO tb_relatorio_rh_dia (relatorio_id, data, horarios, ocorrencia,
                                jornada_prevista_segundos, segundos_trabalhados, saldo_segundos)
                            VALUES (:relatorio, :data, :horarios, :ocorrencia, :prevista, :trabalhados, :saldo)
                            """)
                    .param("relatorio", r.id()).param("data", d.data()).param("horarios", horariosTexto(d.horarios()))
                    .param("ocorrencia", d.ocorrencia()).param("prevista", d.jornadaPrevistaSegundos())
                    .param("trabalhados", d.segundosTrabalhados()).param("saldo", d.saldoSegundos())
                    .update();
        }
    }

    @Override
    public void atualizar(RelatorioRh r) {
        jdbc.sql("""
                        UPDATE tb_relatorio_rh SET status = :status, mensagem = :mensagem, divergencias = :divergencias,
                               processado_em = :processado
                         WHERE id = :id
                        """)
                .param("status", r.status().name()).param("mensagem", r.mensagem())
                .param("divergencias", r.divergencias()).param("processado", utc(r.processadoEm()))
                .param("id", r.id())
                .update();
    }

    @Override
    public Optional<RelatorioRh> buscarPorId(UUID id) {
        return jdbc.sql("SELECT * FROM tb_relatorio_rh WHERE id = :id").param("id", id)
                .query(RelatorioRhRepositoryJdbc::relatorio).optional();
    }

    @Override
    public Optional<RelatorioRh> buscarPorHash(String hash) {
        return jdbc.sql("SELECT * FROM tb_relatorio_rh WHERE hash_sha256 = :hash").param("hash", hash)
                .query(RelatorioRhRepositoryJdbc::relatorio).optional();
    }

    @Override
    public List<RelatorioRh> listar() {
        return jdbc.sql("SELECT * FROM tb_relatorio_rh ORDER BY emitido_em DESC, enviado_em DESC")
                .query(RelatorioRhRepositoryJdbc::relatorio).list();
    }

    @Override
    public void excluir(UUID id) {
        jdbc.sql("DELETE FROM tb_relatorio_rh WHERE id = :id").param("id", id).update();
    }

    @Override
    public List<DiaRelatorioRh> dias(UUID relatorioId) {
        return jdbc.sql("SELECT * FROM tb_relatorio_rh_dia WHERE relatorio_id = :id ORDER BY data")
                .param("id", relatorioId).query((rs, i) -> dia(rs)).list();
    }

    @Override
    public List<DiaVigente> vigentes(LocalDate inicio, LocalDate fim) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (d.data) d.*
                          FROM tb_relatorio_rh_dia d
                          JOIN tb_relatorio_rh r ON r.id = d.relatorio_id
                         WHERE d.data BETWEEN :inicio AND :fim
                           AND r.status <> 'ERRO'
                         ORDER BY d.data, r.emitido_em DESC, r.enviado_em DESC
                        """)
                .param("inicio", inicio).param("fim", fim)
                .query((rs, i) -> new DiaVigente(rs.getObject("relatorio_id", UUID.class), dia(rs))).list();
    }

    @Override
    public Optional<Abrangencia> abrangencia() {
        return jdbc.sql("""
                        SELECT MIN(d.data) AS inicio, MAX(d.data) AS fim
                          FROM tb_relatorio_rh_dia d JOIN tb_relatorio_rh r ON r.id = d.relatorio_id
                         WHERE r.status <> 'ERRO'
                        """)
                .query((rs, i) -> rs.getObject("inicio", LocalDate.class) == null ? null
                        : new Abrangencia(rs.getObject("inicio", LocalDate.class), rs.getObject("fim", LocalDate.class)))
                .optional();
    }

    private static RelatorioRh relatorio(ResultSet rs, int linha) throws SQLException {
        return new RelatorioRh(rs.getObject("id", UUID.class), rs.getString("nome_arquivo"), rs.getString("hash_sha256"),
                rs.getString("funcionario"), rs.getObject("emitido_em", LocalDateTime.class),
                rs.getObject("periodo_inicio", LocalDate.class), rs.getObject("periodo_fim", LocalDate.class),
                inteiro(rs, "total_previsto_segundos"), inteiro(rs, "total_trabalhado_segundos"),
                inteiro(rs, "total_saldo_segundos"), rs.getInt("dias_lidos"),
                StatusRelatorioRh.valueOf(rs.getString("status")), rs.getString("mensagem"), rs.getInt("divergencias"),
                instante(rs, "enviado_em"), rs.getString("enviado_por"), instante(rs, "processado_em"));
    }

    private static DiaRelatorioRh dia(ResultSet rs) throws SQLException {
        return new DiaRelatorioRh(rs.getObject("data", LocalDate.class), horarios(rs.getString("horarios")),
                rs.getString("ocorrencia"), rs.getInt("jornada_prevista_segundos"), rs.getInt("segundos_trabalhados"),
                rs.getInt("saldo_segundos"));
    }

    static String horariosTexto(List<LocalTime> horarios) {
        return horarios.stream().map(h -> h.toString().length() == 5 ? h + ":00" : h.toString())
                .collect(Collectors.joining(" "));
    }

    static List<LocalTime> horarios(String texto) {
        if (texto == null || texto.isBlank()) {
            return List.of();
        }
        return Arrays.stream(texto.trim().split("\\s+")).map(LocalTime::parse).toList();
    }

    static Integer inteiro(ResultSet rs, String coluna) throws SQLException {
        int valor = rs.getInt(coluna);
        return rs.wasNull() ? null : valor;
    }

    static Instant instante(ResultSet rs, String coluna) throws SQLException {
        OffsetDateTime valor = rs.getObject(coluna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    static OffsetDateTime utc(Instant instante) {
        return instante == null ? null : instante.atOffset(ZoneOffset.UTC);
    }
}
