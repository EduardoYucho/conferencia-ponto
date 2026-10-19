package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.acesso.AjusteDeAcesso;
import br.com.conferenciaponto.domain.acesso.MudancaDeAcesso;
import br.com.conferenciaponto.domain.acesso.PerfilDeAcesso;
import br.com.conferenciaponto.domain.acesso.PermissoesDaAdministracao;
import br.com.conferenciaponto.domain.acesso.PermissoesDoPonto;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.modulos.PostgresDeTeste;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Os perfis, os perfis e ajustes de cada pessoa e o histórico, no PostgreSQL temporário (com todas as migrações). */
class AcessosRepositoryJdbcTest {

    private final AcessosRepositoryJdbc acessos = new AcessosRepositoryJdbc(PostgresDeTeste.jdbc());
    // no futuro: o histórico do banco compartilhado com os outros testes fica todo antes destas linhas
    private static final Instant AGORA = Instant.parse("2090-10-19T13:00:00Z");

    private PerfilDeAcesso novoPerfil(String permissao) {
        PerfilDeAcesso perfil = PerfilDeAcesso.novo("Perfil " + UUID.randomUUID().toString().substring(0, 8), "Teste",
                Set.of(permissao), "eduardo", AGORA);
        acessos.salvarPerfil(perfil);
        return perfil;
    }

    @Test
    @DisplayName("Cria e altera o perfil (nome, descrição e permissões); o nome não se repete nem em maiúsculas")
    void perfil() {
        PerfilDeAcesso criado = novoPerfil(PermissoesDoPonto.EQUIPE);
        assertThat(acessos.perfil(criado.id())).get().satisfies(p -> {
            assertThat(p.nome()).isEqualTo(criado.nome());
            assertThat(p.descricao()).isEqualTo("Teste");
            assertThat(p.permissoes()).containsExactly(PermissoesDoPonto.EQUIPE);
            assertThat(p.sistema()).isNull();
            assertThat(p.atualizadoPor()).isEqualTo("eduardo");
        });

        acessos.salvarPerfil(criado.comDados(criado.nome() + " 2", null,
                Set.of(PermissoesDoPonto.PROPRIO, PermissoesDaAdministracao.LOGS), "ana", AGORA.plusSeconds(60)));
        assertThat(acessos.perfil(criado.id())).get().satisfies(p -> {
            assertThat(p.nome()).endsWith(" 2");
            assertThat(p.descricao()).isNull();
            assertThat(p.permissoes()).containsExactlyInAnyOrder(PermissoesDoPonto.PROPRIO, PermissoesDaAdministracao.LOGS);
            assertThat(p.atualizadoPor()).isEqualTo("ana");
        });

        PerfilDeAcesso igual = PerfilDeAcesso.novo(criado.nome().toUpperCase() + " 2", null, Set.of(), "eduardo", AGORA);
        assertThatThrownBy(() -> acessos.salvarPerfil(igual)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(acessos.perfilDoSistema(Perfil.ROLE_ADMIN)).get().satisfies(p -> assertThat(p.todas()).isTrue());
    }

    @Test
    @DisplayName("Perfis da pessoa: trocar substitui; excluir o perfil o tira de quem tinha")
    void perfisDaPessoa() {
        UUID maria = PostgresDeTeste.novoUsuario(true);
        PerfilDeAcesso a = novoPerfil(PermissoesDoPonto.EQUIPE);
        PerfilDeAcesso b = novoPerfil(PermissoesDoPonto.PROPRIO);
        UUID usuario = acessos.perfilDoSistema(Perfil.ROLE_USER).orElseThrow().id();

        acessos.definirPerfis(maria, List.of(a.id(), b.id()), "eduardo", AGORA);
        assertThat(acessos.perfisDo(maria)).containsExactlyInAnyOrder(a.id(), b.id());
        acessos.definirPerfis(maria, List.of(b.id(), usuario), "eduardo", AGORA);
        assertThat(acessos.perfisDo(maria)).containsExactlyInAnyOrder(b.id(), usuario);
        assertThat(acessos.perfisCompletosDo(maria)).extracting(PerfilDeAcesso::id).containsExactlyInAnyOrder(b.id(), usuario);
        assertThat(acessos.perfisPorUsuario().get(maria)).containsExactlyInAnyOrder(b.id(), usuario);

        acessos.excluirPerfil(b.id());
        assertThat(acessos.perfisDo(maria)).containsExactly(usuario);
        acessos.definirPerfis(maria, List.of(), "eduardo", AGORA);
        assertThat(acessos.perfisDo(maria)).isEmpty();
        assertThat(acessos.perfisPorUsuario()).doesNotContainKey(maria);
    }

    @Test
    @DisplayName("Ajustes da pessoa: trocar substitui, muda de liberada para retirada e apaga os que saíram")
    void ajustesDaPessoa() {
        UUID maria = PostgresDeTeste.novoUsuario(true);
        acessos.definirAjustes(maria, List.of(new AjusteDeAcesso(PermissoesDaAdministracao.LOGS, true),
                new AjusteDeAcesso(PermissoesDoPonto.LANCAR, false)), "eduardo", AGORA);
        assertThat(acessos.ajustesDo(maria)).containsExactly(new AjusteDeAcesso(PermissoesDaAdministracao.LOGS, true),
                new AjusteDeAcesso(PermissoesDoPonto.LANCAR, false));

        acessos.definirAjustes(maria, List.of(new AjusteDeAcesso(PermissoesDaAdministracao.LOGS, false)), "ana", AGORA);
        assertThat(acessos.ajustesDo(maria)).containsExactly(new AjusteDeAcesso(PermissoesDaAdministracao.LOGS, false));
        assertThat(acessos.ajustesPorUsuario().get(maria)).hasSize(1);

        acessos.definirAjustes(maria, List.of(), "ana", AGORA);
        assertThat(acessos.ajustesDo(maria)).isEmpty();
    }

    @Test
    @DisplayName("Histórico: do mais novo para o mais antigo, de todos ou de uma pessoa")
    void historico() {
        UUID maria = PostgresDeTeste.novoUsuario(true);
        acessos.registrar(new MudancaDeAcesso(AGORA, "eduardo", maria, null, "Acesso de Maria: primeiro"));
        acessos.registrar(new MudancaDeAcesso(AGORA.plusSeconds(1), "eduardo", maria, null, "Acesso de Maria: segundo"));
        acessos.registrar(new MudancaDeAcesso(AGORA.plusSeconds(2), "eduardo", null, UUID.randomUUID(), "Perfil X criado."));

        assertThat(acessos.historico(maria, 10)).extracting(MudancaDeAcesso::descricao)
                .containsExactly("Acesso de Maria: segundo", "Acesso de Maria: primeiro");
        assertThat(acessos.historico(null, 1)).singleElement().satisfies(m -> {
            assertThat(m.quando()).isAfterOrEqualTo(AGORA);
            assertThat(m.quem()).isEqualTo("eduardo");
        });
    }
}
