package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.acesso.AjusteDeAcesso;
import br.com.conferenciaponto.domain.acesso.MudancaDeAcesso;
import br.com.conferenciaponto.domain.acesso.PerfilDeAcesso;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.port.AcessosRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * tb_perfil_acesso e tb_perfil_permissao (os perfis), tb_usuario_perfil (os perfis de cada pessoa),
 * tb_usuario_permissao (os ajustes de cada pessoa) e tb_historico_acesso.
 */
@Repository
class AcessosRepositoryJdbc implements AcessosRepository {

    private static final String PERFIS = """
            SELECT p.id, p.nome, p.descricao, p.todas, p.sistema, p.atualizado_em, p.atualizado_por,
                   COALESCE(array_agg(pp.permissao ORDER BY pp.permissao) FILTER (WHERE pp.permissao IS NOT NULL),
                            '{}') AS permissoes
              FROM tb_perfil_acesso p
              LEFT JOIN tb_perfil_permissao pp ON pp.perfil_id = p.id
            """;
    private static final String AGRUPADO = " GROUP BY p.id ORDER BY lower(p.nome)";

    private final JdbcClient jdbc;

    AcessosRepositoryJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PerfilDeAcesso> perfis() {
        return jdbc.sql(PERFIS + AGRUPADO).query(AcessosRepositoryJdbc::perfil).list();
    }

    @Override
    public Optional<PerfilDeAcesso> perfil(UUID id) {
        return jdbc.sql(PERFIS + " WHERE p.id = :id" + AGRUPADO).param("id", id)
                .query(AcessosRepositoryJdbc::perfil).optional();
    }

    @Override
    public Optional<PerfilDeAcesso> perfilDoSistema(Perfil perfil) {
        return jdbc.sql(PERFIS + " WHERE p.sistema = :sistema" + AGRUPADO).param("sistema", perfil.name())
                .query(AcessosRepositoryJdbc::perfil).optional();
    }

    @Override
    public void salvarPerfil(PerfilDeAcesso perfil) {
        jdbc.sql("""
                        INSERT INTO tb_perfil_acesso (id, nome, descricao, todas, sistema, atualizado_em, atualizado_por)
                        VALUES (:id, :nome, :descricao, :todas, :sistema, :em, :por)
                        ON CONFLICT (id) DO UPDATE SET nome = EXCLUDED.nome, descricao = EXCLUDED.descricao,
                            atualizado_em = EXCLUDED.atualizado_em, atualizado_por = EXCLUDED.atualizado_por
                        """)
                .param("id", perfil.id()).param("nome", perfil.nome()).param("descricao", perfil.descricao())
                .param("todas", perfil.todas()).param("sistema", perfil.sistema() == null ? null : perfil.sistema().name())
                .param("em", RelatorioRhRepositoryJdbc.utc(perfil.atualizadoEm() == null ? Instant.now() : perfil.atualizadoEm()))
                .param("por", perfil.atualizadoPor())
                .update();
        jdbc.sql("DELETE FROM tb_perfil_permissao WHERE perfil_id = :id").param("id", perfil.id()).update();
        for (String permissao : perfil.permissoes()) {
            jdbc.sql("INSERT INTO tb_perfil_permissao (perfil_id, permissao) VALUES (:id, :permissao)")
                    .param("id", perfil.id()).param("permissao", permissao).update();
        }
    }

    @Override
    public void excluirPerfil(UUID id) {
        jdbc.sql("DELETE FROM tb_perfil_acesso WHERE id = :id").param("id", id).update();
    }

    @Override
    public Map<UUID, Set<UUID>> perfisPorUsuario() {
        Map<UUID, Set<UUID>> porUsuario = new HashMap<>();
        jdbc.sql("SELECT usuario_id, perfil_id FROM tb_usuario_perfil").query(rs -> {
            porUsuario.computeIfAbsent(rs.getObject("usuario_id", UUID.class), u -> new LinkedHashSet<>())
                    .add(rs.getObject("perfil_id", UUID.class));
        });
        return porUsuario;
    }

    @Override
    public Set<UUID> perfisDo(UUID usuarioId) {
        return new LinkedHashSet<>(jdbc.sql("SELECT perfil_id FROM tb_usuario_perfil WHERE usuario_id = :u")
                .param("u", usuarioId).query(UUID.class).list());
    }

    @Override
    public List<PerfilDeAcesso> perfisCompletosDo(UUID usuarioId) {
        return jdbc.sql(PERFIS + " WHERE p.id IN (SELECT perfil_id FROM tb_usuario_perfil WHERE usuario_id = :u)" + AGRUPADO)
                .param("u", usuarioId).query(AcessosRepositoryJdbc::perfil).list();
    }

    @Override
    public void definirPerfis(UUID usuarioId, Collection<UUID> perfis, String quem, Instant quando) {
        Set<UUID> novos = new LinkedHashSet<>(perfis);
        if (novos.isEmpty()) {
            jdbc.sql("DELETE FROM tb_usuario_perfil WHERE usuario_id = :u").param("u", usuarioId).update();
        } else {
            jdbc.sql("DELETE FROM tb_usuario_perfil WHERE usuario_id = :u AND perfil_id NOT IN (:perfis)")
                    .param("u", usuarioId).param("perfis", List.copyOf(novos)).update();
        }
        for (UUID perfil : novos) {
            jdbc.sql("""
                            INSERT INTO tb_usuario_perfil (usuario_id, perfil_id, atribuido_em, atribuido_por)
                            VALUES (:u, :p, :em, :por) ON CONFLICT DO NOTHING
                            """)
                    .param("u", usuarioId).param("p", perfil).param("em", RelatorioRhRepositoryJdbc.utc(quando))
                    .param("por", quem).update();
        }
    }

    @Override
    public Map<UUID, List<AjusteDeAcesso>> ajustesPorUsuario() {
        Map<UUID, List<AjusteDeAcesso>> porUsuario = new HashMap<>();
        jdbc.sql("SELECT usuario_id, permissao, liberada FROM tb_usuario_permissao ORDER BY permissao").query(rs -> {
            porUsuario.computeIfAbsent(rs.getObject("usuario_id", UUID.class), u -> new ArrayList<>())
                    .add(new AjusteDeAcesso(rs.getString("permissao"), rs.getBoolean("liberada")));
        });
        return porUsuario;
    }

    @Override
    public List<AjusteDeAcesso> ajustesDo(UUID usuarioId) {
        return jdbc.sql("SELECT permissao, liberada FROM tb_usuario_permissao WHERE usuario_id = :u ORDER BY permissao")
                .param("u", usuarioId)
                .query((rs, n) -> new AjusteDeAcesso(rs.getString("permissao"), rs.getBoolean("liberada"))).list();
    }

    @Override
    public void definirAjustes(UUID usuarioId, Collection<AjusteDeAcesso> ajustes, String quem, Instant quando) {
        Map<String, Boolean> novos = new LinkedHashMap<>();
        ajustes.forEach(a -> novos.put(a.permissao(), a.liberada()));
        if (novos.isEmpty()) {
            jdbc.sql("DELETE FROM tb_usuario_permissao WHERE usuario_id = :u").param("u", usuarioId).update();
        } else {
            jdbc.sql("DELETE FROM tb_usuario_permissao WHERE usuario_id = :u AND permissao NOT IN (:codigos)")
                    .param("u", usuarioId).param("codigos", List.copyOf(novos.keySet())).update();
        }
        for (Map.Entry<String, Boolean> ajuste : novos.entrySet()) {
            jdbc.sql("""
                            INSERT INTO tb_usuario_permissao (usuario_id, permissao, liberada, alterado_em, alterado_por)
                            VALUES (:u, :permissao, :liberada, :em, :por)
                            ON CONFLICT (usuario_id, permissao) DO UPDATE SET liberada = EXCLUDED.liberada,
                                alterado_em = EXCLUDED.alterado_em, alterado_por = EXCLUDED.alterado_por
                             WHERE tb_usuario_permissao.liberada IS DISTINCT FROM EXCLUDED.liberada
                            """)
                    .param("u", usuarioId).param("permissao", ajuste.getKey()).param("liberada", ajuste.getValue())
                    .param("em", RelatorioRhRepositoryJdbc.utc(quando)).param("por", quem).update();
        }
    }

    @Override
    public void registrar(MudancaDeAcesso mudanca) {
        jdbc.sql("""
                        INSERT INTO tb_historico_acesso (quando, quem, usuario_id, perfil_id, descricao)
                        VALUES (:quando, :quem, :usuario, :perfil, :descricao)
                        """)
                .param("quando", RelatorioRhRepositoryJdbc.utc(mudanca.quando())).param("quem", mudanca.quem())
                .param("usuario", mudanca.usuarioId()).param("perfil", mudanca.perfilId())
                .param("descricao", RelatorioRhRepositoryJdbc.cortar(mudanca.descricao(), 1000))
                .update();
    }

    @Override
    public List<MudancaDeAcesso> historico(UUID usuarioId, int limite) {
        String filtro = usuarioId == null ? "" : " WHERE usuario_id = :u";
        JdbcClient.StatementSpec sql = jdbc.sql("SELECT quando, quem, usuario_id, perfil_id, descricao FROM tb_historico_acesso"
                + filtro + " ORDER BY quando DESC, id DESC LIMIT :limite").param("limite", limite);
        if (usuarioId != null) {
            sql = sql.param("u", usuarioId);
        }
        return sql.query((rs, n) -> new MudancaDeAcesso(RelatorioRhRepositoryJdbc.instante(rs, "quando"),
                rs.getString("quem"), rs.getObject("usuario_id", UUID.class), rs.getObject("perfil_id", UUID.class),
                rs.getString("descricao"))).list();
    }

    private static PerfilDeAcesso perfil(ResultSet rs, int linha) throws SQLException {
        String sistema = rs.getString("sistema");
        Set<String> permissoes = new TreeSet<>();
        java.sql.Array array = rs.getArray("permissoes");
        if (array != null) {
            for (Object codigo : (Object[]) array.getArray()) {
                permissoes.add((String) codigo);
            }
        }
        return new PerfilDeAcesso(rs.getObject("id", UUID.class), rs.getString("nome"), rs.getString("descricao"),
                rs.getBoolean("todas"), sistema == null ? null : Perfil.valueOf(sistema), permissoes,
                RelatorioRhRepositoryJdbc.instante(rs, "atualizado_em"), rs.getString("atualizado_por"));
    }
}
