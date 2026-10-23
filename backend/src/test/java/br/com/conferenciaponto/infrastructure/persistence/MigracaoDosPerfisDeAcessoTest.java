package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.acesso.AjusteDeAcesso;
import br.com.conferenciaponto.domain.acesso.CatalogoDePermissoes;
import br.com.conferenciaponto.domain.acesso.PerfilDeAcesso;
import br.com.conferenciaponto.domain.acesso.PermissoesDaAdministracao;
import br.com.conferenciaponto.domain.acesso.PermissoesDoPonto;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.application.acesso.PermissoesDoAtendimento;
import br.com.conferenciaponto.modulos.conhecimento.application.acesso.PermissoesDaBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A V16 num banco que estava na V15, com as pessoas e as liberações dos módulos de antes: cada uma fica com o
 * acesso que já tinha (nem mais, nem menos).
 */
class MigracaoDosPerfisDeAcessoTest {

    private static JdbcClient jdbc;
    private static AcessosRepositoryJdbc acessos;
    private static final CatalogoDePermissoes CATALOGO = new CatalogoDePermissoes(List.of(new PermissoesDoPonto(),
            new PermissoesDoAtendimento(), new PermissoesDaBase(), new PermissoesDaAdministracao()));
    private static UUID eduardo;
    private static UUID carla;
    private static UUID bruno;
    private static UUID ana;
    private static UUID semPerfil;

    @BeforeAll
    static void migrarComOsDadosDeAntes() {
        DataSource banco = PostgresDeTeste.bancoVazio("migracao_perfis");
        Flyway.configure().dataSource(banco).locations("classpath:db/migration").target("15").load().migrate();
        jdbc = JdbcClient.create(banco);

        eduardo = pessoa("eduardo", 1);
        carla = pessoa("carla", 3);
        bruno = pessoa("bruno", 2);
        ana = pessoa("ana", 2);
        semPerfil = pessoa("sem", null);
        jdbc.sql("INSERT INTO atendimento.acesso (usuario_id, gerador, concedido_por) VALUES (?, true, 'eduardo'), (?, true, 'eduardo')")
                .params(eduardo, ana).update();
        jdbc.sql("""
                INSERT INTO conhecimento.acesso (usuario_id, pesquisar, curar, concedido_por)
                VALUES (?, true, true, 'eduardo'), (?, true, true, 'eduardo'), (?, true, false, 'eduardo'), (?, false, false, 'eduardo')""")
                .params(eduardo, ana, semPerfil, carla).update();

        Flyway.configure().dataSource(banco).locations("classpath:db/migration").load().migrate();
        acessos = new AcessosRepositoryJdbc(jdbc);
    }

    private static UUID pessoa(String login, Integer role) {
        UUID id = jdbc.sql("INSERT INTO tb_usuario (login, nome, senha_hash) VALUES (?, ?, 'x') RETURNING id")
                .params(login, login).query(UUID.class).single();
        if (role != null) {
            jdbc.sql("INSERT INTO tb_usuario_role (usuario_id, role_id) VALUES (?, ?)").params(id, role).update();
        }
        return id;
    }

    private static Set<String> efetivas(UUID usuario) {
        return CATALOGO.efetivas(acessos.perfisCompletosDo(usuario), acessos.ajustesDo(usuario));
    }

    @Test
    @DisplayName("Os três perfis do sistema, com o que cada um liberava")
    void perfisDoSistema() {
        assertThat(acessos.perfis()).extracting(PerfilDeAcesso::nome).containsExactly("Administrador", "Coordenador", "Usuário");
        assertThat(acessos.perfilDoSistema(Perfil.ROLE_ADMIN)).get().satisfies(p -> {
            assertThat(p.todas()).isTrue();
            assertThat(p.permissoes()).isEmpty();
        });
        assertThat(acessos.perfilDoSistema(Perfil.ROLE_USER)).get()
                .satisfies(p -> assertThat(p.permissoes()).containsExactlyInAnyOrder(PermissoesDoPonto.PROPRIO, PermissoesDoPonto.LANCAR));
        assertThat(acessos.perfilDoSistema(Perfil.ROLE_VIEWER)).get().as("ponto.conferir veio na V17")
                .satisfies(p -> assertThat(p.permissoes()).containsExactlyInAnyOrder(PermissoesDoPonto.EQUIPE,
                        PermissoesDoPonto.CONFERIR));
        assertThat(jdbc.sql("SELECT count(*) FROM tb_historico_acesso").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    @DisplayName("Cada pessoa fica com o acesso que tinha: o administrador sem ajustes (já tem tudo), as liberações dos outros como ajustes")
    void ninguemGanhaNemPerde() {
        assertThat(efetivas(eduardo)).containsExactlyElementsOf(CATALOGO.codigos());
        assertThat(acessos.ajustesDo(eduardo)).isEmpty();

        assertThat(efetivas(carla)).containsExactly(PermissoesDoPonto.EQUIPE, PermissoesDoPonto.CONFERIR);
        assertThat(efetivas(bruno)).containsExactlyInAnyOrder(PermissoesDoPonto.PROPRIO, PermissoesDoPonto.LANCAR);
        assertThat(efetivas(ana)).containsExactlyInAnyOrder(PermissoesDoPonto.PROPRIO, PermissoesDoPonto.LANCAR,
                PermissoesDoAtendimento.USAR, PermissoesDaBase.PESQUISAR, PermissoesDaBase.CURAR);
        assertThat(acessos.ajustesDo(ana)).containsExactlyInAnyOrder(new AjusteDeAcesso(PermissoesDoAtendimento.USAR, true),
                new AjusteDeAcesso(PermissoesDaBase.PESQUISAR, true), new AjusteDeAcesso(PermissoesDaBase.CURAR, true));
        assertThat(efetivas(semPerfil)).containsExactly(PermissoesDaBase.PESQUISAR);
    }

    @Test
    @DisplayName("As tabelas antigas ficam como estavam (para dar para voltar atrás)")
    void tabelasAntigasFicam() {
        assertThat(jdbc.sql("SELECT count(*) FROM tb_usuario_role").query(Integer.class).single()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM atendimento.acesso").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM conhecimento.acesso").query(Integer.class).single()).isEqualTo(4);
    }
}
