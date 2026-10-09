package br.com.conferenciaponto.modulos.conhecimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.conhecimento.domain.acesso.AcessoABase;
import br.com.conferenciaponto.modulos.conhecimento.domain.acesso.AcessosABase;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** conhecimento.acesso: uma linha por pessoa que já teve a base liberada ou retirada. */
@Repository
class AcessosABaseJdbc implements AcessosABase {

    private final JdbcClient jdbc;

    AcessosABaseJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AcessoABase> buscar(UUID usuarioId) {
        return jdbc.sql("SELECT * FROM conhecimento.acesso WHERE usuario_id = :usuario")
                .param("usuario", usuarioId)
                .query(AcessosABaseJdbc::acesso).optional();
    }

    @Override
    public List<AcessoABase> listar() {
        return jdbc.sql("SELECT * FROM conhecimento.acesso").query(AcessosABaseJdbc::acesso).list();
    }

    @Override
    public void salvar(AcessoABase acesso) {
        jdbc.sql("""
                        INSERT INTO conhecimento.acesso (usuario_id, pesquisar, curar, concedido_por, atualizado_em)
                        VALUES (:usuario, :pesquisar, :curar, :por, :em)
                        ON CONFLICT (usuario_id) DO UPDATE SET
                            pesquisar = EXCLUDED.pesquisar, curar = EXCLUDED.curar,
                            concedido_por = EXCLUDED.concedido_por, atualizado_em = EXCLUDED.atualizado_em
                        """)
                .param("usuario", acesso.usuarioId())
                .param("pesquisar", acesso.pesquisar())
                .param("curar", acesso.curar())
                .param("por", acesso.concedidoPor())
                .param("em", utc(acesso.atualizadoEm() == null ? Instant.now() : acesso.atualizadoEm()))
                .update();
    }

    private static AcessoABase acesso(ResultSet rs, int linha) throws SQLException {
        OffsetDateTime em = rs.getObject("atualizado_em", OffsetDateTime.class);
        return new AcessoABase(rs.getObject("usuario_id", UUID.class), rs.getBoolean("pesquisar"),
                rs.getBoolean("curar"), rs.getString("concedido_por"), em == null ? null : em.toInstant());
    }

    private static OffsetDateTime utc(Instant instante) {
        return OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }
}
