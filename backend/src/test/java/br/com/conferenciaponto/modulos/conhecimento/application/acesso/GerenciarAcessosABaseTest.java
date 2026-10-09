package br.com.conferenciaponto.modulos.conhecimento.application.acesso;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.UsuariosEmMemoria;
import br.com.conferenciaponto.modulos.conhecimento.domain.acesso.AcessoABase;
import br.com.conferenciaponto.modulos.conhecimento.domain.acesso.AcessosABase;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GerenciarAcessosABaseTest {

    private static final Instant AGORA = Instant.parse("2026-10-09T17:00:00Z");

    private final UsuariosEmMemoria usuarios = new UsuariosEmMemoria();
    private final AcessosEmMemoria acessos = new AcessosEmMemoria();
    private final GerenciarAcessosABase gerenciar =
            new GerenciarAcessosABase(acessos, usuarios, Clock.fixed(AGORA, ZoneId.of("America/Sao_Paulo")));

    private final Usuario admin = usuarios.novo("admin", "Administrador", Perfil.ROLE_ADMIN);
    private final Usuario maria = usuarios.novo("maria", "Maria", Perfil.ROLE_USER);

    @Test
    void ninguemComecaComAcesso() {
        assertThat(gerenciar.acessoDe(maria).pesquisar()).isFalse();
        assertThat(gerenciar.acessoDe(admin).curar()).isFalse();
    }

    @Test
    void quemCuraTambemPesquisa() {
        AcessoABaseView curadora = gerenciar.definir(admin, maria.id(), false, true);

        assertThat(curadora.pesquisar()).isTrue();
        assertThat(curadora.curar()).isTrue();
        assertThat(gerenciar.acessoDe(maria).pesquisar()).isTrue();
    }

    @Test
    void tirarAPesquisaDeixaSemNada() {
        gerenciar.definir(admin, maria.id(), true, false);
        gerenciar.definir(admin, maria.id(), false, false);

        assertThat(gerenciar.acessoDe(maria).pesquisar()).isFalse();
        assertThat(gerenciar.listar(admin)).filteredOn(v -> v.login().equals("maria"))
                .singleElement().satisfies(v -> {
                    assertThat(v.concedidoPor()).isEqualTo("admin");
                    assertThat(v.atualizadoEm()).isEqualTo(AGORA);
                });
    }

    @Test
    void usuarioDesativadoNaoTemAcessoNemPodeSerLiberado() {
        Usuario joao = usuarios.desativado("joao", "João");
        acessos.salvar(new AcessoABase(joao.id(), true, true, "admin", AGORA));

        assertThat(gerenciar.acessoDe(joao).pesquisar()).isFalse();
        assertThatThrownBy(() -> gerenciar.definir(admin, joao.id(), true, false))
                .isInstanceOf(RegraNegocioException.class);
    }

    @Test
    void soOAdministradorLibera() {
        assertThatThrownBy(() -> gerenciar.definir(maria, maria.id(), true, true)).isInstanceOf(AcessoNegadoException.class);
        assertThatThrownBy(() -> gerenciar.listar(maria)).isInstanceOf(AcessoNegadoException.class);
    }

    static class AcessosEmMemoria implements AcessosABase {
        private final Map<UUID, AcessoABase> porUsuario = new HashMap<>();

        @Override
        public Optional<AcessoABase> buscar(UUID usuarioId) {
            return Optional.ofNullable(porUsuario.get(usuarioId));
        }

        @Override
        public List<AcessoABase> listar() {
            return new ArrayList<>(porUsuario.values());
        }

        @Override
        public void salvar(AcessoABase acesso) {
            porUsuario.put(acesso.usuarioId(), acesso);
        }
    }
}
