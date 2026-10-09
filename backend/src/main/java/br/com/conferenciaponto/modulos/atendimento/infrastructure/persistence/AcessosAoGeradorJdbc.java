package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.atendimento.domain.acesso.AcessoAoGerador;
import br.com.conferenciaponto.modulos.atendimento.domain.acesso.AcessosAoGerador;
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

/** atendimento.acesso: uma linha por pessoa que já teve o gerador liberado ou retirado. */
@Repository
class AcessosAoGeradorJdbc implements AcessosAoGerador {

    private final JdbcClient jdbc;

    AcessosAoGeradorJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AcessoAoGerador> buscar(UUID usuarioId) {
        return jdbc.sql("SELECT * FROM atendimento.acesso WHERE usuario_id = :usuario")
                .param("usuario", usuarioId)
                .query(AcessosAoGeradorJdbc::acesso).optional();
    }

    @Override
    public List<AcessoAoGerador> listar() {
        return jdbc.sql("SELECT * FROM atendimento.acesso").query(AcessosAoGeradorJdbc::acesso).list();
    }

    @Override
    public void salvar(AcessoAoGerador acesso) {
        jdbc.sql("""
                        INSERT INTO atendimento.acesso (usuario_id, gerador, concedido_por, atualizado_em)
                        VALUES (:usuario, :gerador, :por, :em)
                        ON CONFLICT (usuario_id) DO UPDATE SET
                            gerador = EXCLUDED.gerador, concedido_por = EXCLUDED.concedido_por,
                            atualizado_em = EXCLUDED.atualizado_em
                        """)
                .param("usuario", acesso.usuarioId())
                .param("gerador", acesso.gerador())
                .param("por", acesso.concedidoPor())
                .param("em", utc(acesso.atualizadoEm() == null ? Instant.now() : acesso.atualizadoEm()))
                .update();
    }

    private static AcessoAoGerador acesso(ResultSet rs, int linha) throws SQLException {
        OffsetDateTime em = rs.getObject("atualizado_em", OffsetDateTime.class);
        return new AcessoAoGerador(rs.getObject("usuario_id", UUID.class), rs.getBoolean("gerador"),
                rs.getString("concedido_por"), em == null ? null : em.toInstant());
    }

    private static OffsetDateTime utc(Instant instante) {
        return OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }
}
