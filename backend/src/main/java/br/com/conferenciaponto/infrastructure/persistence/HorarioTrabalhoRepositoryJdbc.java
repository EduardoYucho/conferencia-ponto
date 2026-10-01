package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.port.HorarioTrabalhoRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** tb_horario_trabalho: uma coluna por dia da semana com os períodos em texto ("08:00-12:00 13:00-17:48"). */
@Repository
class HorarioTrabalhoRepositoryJdbc implements HorarioTrabalhoRepository {

    private static final Map<DayOfWeek, String> COLUNAS = colunas();

    private final JdbcClient jdbc;

    HorarioTrabalhoRepositoryJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void salvar(HorarioTrabalho h) {
        JdbcClient.StatementSpec sql = jdbc.sql("""
                        INSERT INTO tb_horario_trabalho (id, usuario_id, vigente_desde, tolerancia_minutos,
                            seg, ter, qua, qui, sex, sab, dom, criado_em, criado_por)
                        VALUES (:id, :usuario, :desde, :tolerancia, :seg, :ter, :qua, :qui, :sex, :sab, :dom,
                            :criadoEm, :criadoPor)
                        ON CONFLICT (id) DO UPDATE SET
                            vigente_desde = EXCLUDED.vigente_desde, tolerancia_minutos = EXCLUDED.tolerancia_minutos,
                            seg = EXCLUDED.seg, ter = EXCLUDED.ter, qua = EXCLUDED.qua, qui = EXCLUDED.qui,
                            sex = EXCLUDED.sex, sab = EXCLUDED.sab, dom = EXCLUDED.dom,
                            criado_em = EXCLUDED.criado_em, criado_por = EXCLUDED.criado_por
                        """)
                .param("id", h.id()).param("usuario", h.usuarioId()).param("desde", h.vigenteDesde())
                .param("tolerancia", h.toleranciaMinutos())
                .param("criadoEm", RelatorioRhRepositoryJdbc.utc(h.criadoEm()))
                .param("criadoPor", h.criadoPor());
        for (Map.Entry<DayOfWeek, String> coluna : COLUNAS.entrySet()) {
            GradeHoraria grade = h.dias().get(coluna.getKey());
            sql = sql.param(coluna.getValue(), grade == null ? null : grade.texto());
        }
        sql.update();
    }

    @Override
    public Optional<HorarioTrabalho> buscarPorId(UUID id) {
        return jdbc.sql("SELECT * FROM tb_horario_trabalho WHERE id = :id").param("id", id)
                .query(HorarioTrabalhoRepositoryJdbc::horario).optional();
    }

    @Override
    public List<HorarioTrabalho> listarPorUsuario(UUID usuarioId) {
        return jdbc.sql("SELECT * FROM tb_horario_trabalho WHERE usuario_id = :usuario ORDER BY vigente_desde")
                .param("usuario", usuarioId)
                .query(HorarioTrabalhoRepositoryJdbc::horario).list();
    }

    @Override
    public void excluir(UUID id) {
        jdbc.sql("DELETE FROM tb_horario_trabalho WHERE id = :id").param("id", id).update();
    }

    private static HorarioTrabalho horario(ResultSet rs, int linha) throws SQLException {
        EnumMap<DayOfWeek, GradeHoraria> dias = new EnumMap<>(DayOfWeek.class);
        for (Map.Entry<DayOfWeek, String> coluna : COLUNAS.entrySet()) {
            GradeHoraria grade = GradeHoraria.ler(rs.getString(coluna.getValue()));
            if (grade != null) {
                dias.put(coluna.getKey(), grade);
            }
        }
        Instant criadoEm = RelatorioRhRepositoryJdbc.instante(rs, "criado_em");
        return new HorarioTrabalho(rs.getObject("id", UUID.class), rs.getObject("usuario_id", UUID.class),
                rs.getObject("vigente_desde", LocalDate.class), rs.getInt("tolerancia_minutos"), dias,
                criadoEm == null ? Instant.EPOCH : criadoEm, rs.getString("criado_por"));
    }

    private static Map<DayOfWeek, String> colunas() {
        EnumMap<DayOfWeek, String> mapa = new EnumMap<>(DayOfWeek.class);
        mapa.put(DayOfWeek.MONDAY, "seg");
        mapa.put(DayOfWeek.TUESDAY, "ter");
        mapa.put(DayOfWeek.WEDNESDAY, "qua");
        mapa.put(DayOfWeek.THURSDAY, "qui");
        mapa.put(DayOfWeek.FRIDAY, "sex");
        mapa.put(DayOfWeek.SATURDAY, "sab");
        mapa.put(DayOfWeek.SUNDAY, "dom");
        return mapa;
    }
}
