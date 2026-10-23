package br.com.conferenciaponto.domain.acesso;

import br.com.conferenciaponto.domain.model.Perfil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogoDePermissoesTest {

    private static FonteDePermissoes fonte(String grupo, int ordem, Permissao... permissoes) {
        return new FonteDePermissoes() {
            @Override
            public String grupo() {
                return grupo;
            }

            @Override
            public int ordem() {
                return ordem;
            }

            @Override
            public List<Permissao> permissoes() {
                return List.of(permissoes);
            }
        };
    }

    private static final FonteDePermissoes BASE = fonte("Base", 30,
            Permissao.de("base.pesquisar", "Pesquisar", null),
            Permissao.de("base.curar", "Curar", null).requer("base.pesquisar"),
            Permissao.de("base.publicar", "Publicar", null).requer("base.curar"));

    private final CatalogoDePermissoes catalogo = new CatalogoDePermissoes(
            List.of(new PermissoesDaAdministracao(), BASE, new PermissoesDoPonto()));

    private static PerfilDeAcesso perfil(String nome, String... permissoes) {
        return new PerfilDeAcesso(UUID.randomUUID(), nome, null, false, null, Set.of(permissoes), Instant.EPOCH, "teste");
    }

    @Test
    @DisplayName("Os grupos seguem a ordem declarada, e os códigos a ordem da tela")
    void grupos() {
        assertThat(catalogo.grupos()).extracting(CatalogoDePermissoes.Grupo::nome)
                .containsExactly("Ponto", "Base", "Administração");
        assertThat(catalogo.codigos()).startsWith(PermissoesDoPonto.PROPRIO, PermissoesDoPonto.LANCAR)
                .endsWith(PermissoesDaAdministracao.LOGS)
                .hasSize(PermissoesDoPonto.CODIGOS.size() + 3 + PermissoesDaAdministracao.CODIGOS.size());
    }

    @Test
    @DisplayName("Permissão repetida, requisito que não existe e código fora do padrão impedem o sistema de subir")
    void catalogoInvalido() {
        assertThatThrownBy(() -> new CatalogoDePermissoes(List.of(new PermissoesDoPonto(),
                fonte("Outro", 50, Permissao.de(PermissoesDoPonto.LANCAR, "De novo", null)))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("duas vezes");
        assertThatThrownBy(() -> new CatalogoDePermissoes(List.of(
                fonte("Outro", 50, Permissao.de("x.editar", "Editar", null).requer("x.ler")))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("x.ler");
        assertThatThrownBy(() -> Permissao.de("Ponto.Lancar", "X", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Permissao.de("a.b", "X", null).requer("a.b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Soma dos perfis, mais o liberado, menos o retirado; sem o requisito não vale (em cadeia); códigos antigos somem")
    void efetivas() {
        PerfilDeAcesso usuario = perfil("Usuário", PermissoesDoPonto.PROPRIO, PermissoesDoPonto.LANCAR, "modulo.retirado");
        PerfilDeAcesso curador = perfil("Curador", "base.pesquisar", "base.curar", "base.publicar");

        assertThat(catalogo.efetivas(List.of(usuario), List.of()))
                .containsExactly(PermissoesDoPonto.PROPRIO, PermissoesDoPonto.LANCAR);
        assertThat(catalogo.efetivas(List.of(usuario, curador), List.of(new AjusteDeAcesso(PermissoesDaAdministracao.LOGS, true))))
                .containsExactly(PermissoesDoPonto.PROPRIO, PermissoesDoPonto.LANCAR, "base.pesquisar", "base.curar",
                        "base.publicar", PermissoesDaAdministracao.LOGS);
        assertThat(catalogo.efetivas(List.of(usuario, curador), List.of(new AjusteDeAcesso("base.pesquisar", false))))
                .as("sem pesquisar, curar e publicar caem juntos")
                .containsExactly(PermissoesDoPonto.PROPRIO, PermissoesDoPonto.LANCAR);
        assertThat(catalogo.efetivas(List.of(), List.of(new AjusteDeAcesso(PermissoesDoPonto.LANCAR, true))))
                .as("lançar sem ter o próprio ponto não vale").isEmpty();
    }

    @Test
    @DisplayName("O Administrador tem tudo o que existe, inclusive o que vier depois")
    void administradorTemTudo() {
        PerfilDeAcesso administrador = new PerfilDeAcesso(UUID.randomUUID(), "Administrador", null, true, Perfil.ROLE_ADMIN,
                Set.of(), Instant.EPOCH, "sistema");

        assertThat(catalogo.efetivas(List.of(administrador), List.of())).containsExactlyElementsOf(catalogo.codigos());
        assertThat(administrador.permissoes()).isEmpty();
        assertThat(administrador.bloqueado()).isTrue();
    }

    @Test
    @DisplayName("A explicação diz de quais perfis vem cada permissão, o ajuste e o requisito que falta")
    void explicar() {
        PerfilDeAcesso usuario = perfil("Usuário", PermissoesDoPonto.PROPRIO, PermissoesDoPonto.LANCAR);
        PerfilDeAcesso outro = perfil("Atendente", PermissoesDoPonto.PROPRIO, "base.curar");

        List<CatalogoDePermissoes.Origem> origens = catalogo.explicar(List.of(usuario, outro),
                List.of(new AjusteDeAcesso(PermissoesDoPonto.LANCAR, false)));

        assertThat(origens).hasSize(catalogo.codigos().size());
        assertThat(origens).filteredOn(o -> o.permissao().equals(PermissoesDoPonto.PROPRIO)).singleElement()
                .satisfies(o -> {
                    assertThat(o.pode()).isTrue();
                    assertThat(o.perfis()).containsExactly("Atendente", "Usuário");
                    assertThat(o.ajuste()).isNull();
                });
        assertThat(origens).filteredOn(o -> o.permissao().equals(PermissoesDoPonto.LANCAR)).singleElement()
                .satisfies(o -> {
                    assertThat(o.pode()).isFalse();
                    assertThat(o.ajuste()).isFalse();
                    assertThat(o.faltando()).isNull();
                });
        assertThat(origens).filteredOn(o -> o.permissao().equals("base.curar")).singleElement()
                .satisfies(o -> {
                    assertThat(o.pode()).isFalse();
                    assertThat(o.faltando()).isEqualTo("base.pesquisar");
                });
    }

    @Test
    @DisplayName("O acesso de fábrica dos perfis do sistema (usuários iniciais e testes)")
    void acessoDeFabrica() {
        Acesso carla = Acesso.de(Perfil.ROLE_VIEWER, Perfil.ROLE_USER);

        assertThat(carla.perfis()).containsExactly("Coordenador", "Usuário");
        assertThat(carla.permissoes()).containsExactlyInAnyOrder(PermissoesDoPonto.EQUIPE, PermissoesDoPonto.CONFERIR,
                PermissoesDoPonto.PROPRIO, PermissoesDoPonto.LANCAR);
        assertThat(Acesso.administrador().permissoes()).containsAll(PermissoesDoPonto.CODIGOS)
                .containsAll(PermissoesDaAdministracao.CODIGOS);
        assertThat(Acesso.usuario().sem(PermissoesDoPonto.LANCAR).com(PermissoesDoPonto.EQUIPE).ordenadas())
                .containsExactly(PermissoesDoPonto.EQUIPE, PermissoesDoPonto.PROPRIO);
    }
}
