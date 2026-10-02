package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.VinculoPlanilha;
import br.com.conferenciaponto.domain.port.VinculoPlanilhaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** tb_planilha_google: uma linha por usuário com planilha vinculada. */
@Repository
class VinculoPlanilhaRepositoryJdbc implements VinculoPlanilhaRepository {

    private final JdbcClient jdbc;

    VinculoPlanilhaRepositoryJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<VinculoPlanilha> buscar(UUID usuarioId) {
        return jdbc.sql("SELECT * FROM tb_planilha_google WHERE usuario_id = :usuario").param("usuario", usuarioId)
                .query(VinculoPlanilhaRepositoryJdbc::vinculo).optional();
    }

    @Override
    public Optional<VinculoPlanilha> buscarPorPlanilha(String planilhaId) {
        return jdbc.sql("SELECT * FROM tb_planilha_google WHERE planilha_id = :planilha").param("planilha", planilhaId)
                .query(VinculoPlanilhaRepositoryJdbc::vinculo).optional();
    }

    @Override
    public List<VinculoPlanilha> listar() {
        return jdbc.sql("SELECT * FROM tb_planilha_google ORDER BY vinculada_em")
                .query(VinculoPlanilhaRepositoryJdbc::vinculo).list();
    }

    @Override
    public void salvar(VinculoPlanilha v) {
        jdbc.sql("""
                        INSERT INTO tb_planilha_google (usuario_id, planilha_id, titulo, vinculada_em, vinculada_por,
                            sincronizada_em, erro)
                        VALUES (:usuario, :planilha, :titulo, :vinculadaEm, :vinculadaPor, :sincronizadaEm, :erro)
                        ON CONFLICT (usuario_id) DO UPDATE SET
                            planilha_id = EXCLUDED.planilha_id, titulo = EXCLUDED.titulo,
                            vinculada_em = EXCLUDED.vinculada_em, vinculada_por = EXCLUDED.vinculada_por,
                            sincronizada_em = EXCLUDED.sincronizada_em, erro = EXCLUDED.erro
                        """)
                .param("usuario", v.usuarioId()).param("planilha", v.planilhaId()).param("titulo", v.titulo())
                .param("vinculadaEm", RelatorioRhRepositoryJdbc.utc(v.vinculadaEm()))
                .param("vinculadaPor", v.vinculadaPor())
                .param("sincronizadaEm", RelatorioRhRepositoryJdbc.utc(v.sincronizadaEm()))
                .param("erro", v.erro())
                .update();
    }

    @Override
    public void excluir(UUID usuarioId) {
        jdbc.sql("DELETE FROM tb_planilha_google WHERE usuario_id = :usuario").param("usuario", usuarioId).update();
    }

    private static VinculoPlanilha vinculo(ResultSet rs, int linha) throws SQLException {
        return new VinculoPlanilha(rs.getObject("usuario_id", UUID.class), rs.getString("planilha_id"),
                rs.getString("titulo"), RelatorioRhRepositoryJdbc.instante(rs, "vinculada_em"),
                rs.getString("vinculada_por"), RelatorioRhRepositoryJdbc.instante(rs, "sincronizada_em"),
                rs.getString("erro"));
    }
}
