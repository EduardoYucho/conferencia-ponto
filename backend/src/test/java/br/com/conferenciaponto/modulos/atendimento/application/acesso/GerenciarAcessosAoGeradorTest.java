package br.com.conferenciaponto.modulos.atendimento.application.acesso;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.UsuariosEmMemoria;
import br.com.conferenciaponto.modulos.atendimento.domain.acesso.AcessoAoGerador;
import br.com.conferenciaponto.modulos.atendimento.domain.acesso.AcessosAoGerador;
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

class GerenciarAcessosAoGeradorTest {

    private static final Instant AGORA = Instant.parse("2026-10-09T17:00:00Z");

    private final UsuariosEmMemoria usuarios = new UsuariosEmMemoria();
    private final AcessosEmMemoria acessos = new AcessosEmMemoria();
    private final GerenciarAcessosAoGerador gerenciar =
            new GerenciarAcessosAoGerador(acessos, usuarios, Clock.fixed(AGORA, ZoneId.of("America/Sao_Paulo")));

    private final Usuario admin = usuarios.novo("admin", "Administrador", Perfil.ROLE_ADMIN);
    private final Usuario maria = usuarios.novo("maria", "Maria", Perfil.ROLE_USER);
    private final Usuario coordenacao = usuarios.novo("coordenacao", "Coordenação", Perfil.ROLE_VIEWER);

    @Test
    void ninguemComecaLiberadoNemOAdministrador() {
        assertThat(gerenciar.liberado(admin)).isFalse();
        assertThat(gerenciar.liberado(maria)).isFalse();
    }

    @Test
    void oAdministradorLiberaQualquerPerfilInclusiveASiMesmo() {
        AcessoAoGeradorView liberado = gerenciar.definir(admin, coordenacao.id(), true);
        gerenciar.definir(admin, admin.id(), true);

        assertThat(liberado.gerador()).isTrue();
        assertThat(liberado.concedidoPor()).isEqualTo("admin");
        assertThat(liberado.atualizadoEm()).isEqualTo(AGORA);
        assertThat(gerenciar.liberado(coordenacao)).isTrue();
        assertThat(gerenciar.liberado(admin)).isTrue();
        assertThat(gerenciar.liberado(maria)).isFalse();
    }

    @Test
    void retirarValeNaHora() {
        gerenciar.definir(admin, maria.id(), true);
        gerenciar.definir(admin, maria.id(), false);

        assertThat(gerenciar.liberado(maria)).isFalse();
    }

    @Test
    void usuarioDesativadoNuncaUsaNemPodeSerLiberado() {
        Usuario joao = usuarios.desativado("joao", "João");
        acessos.salvar(new AcessoAoGerador(joao.id(), true, "admin", AGORA));

        assertThat(gerenciar.liberado(joao)).isFalse();
        assertThatThrownBy(() -> gerenciar.definir(admin, joao.id(), true))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("João está desativado");
        assertThat(gerenciar.definir(admin, joao.id(), false).gerador()).isFalse();
    }

    @Test
    void soOAdministradorListaELibera() {
        assertThatThrownBy(() -> gerenciar.listar(maria)).isInstanceOf(AcessoNegadoException.class);
        assertThatThrownBy(() -> gerenciar.definir(maria, maria.id(), true)).isInstanceOf(AcessoNegadoException.class);
        assertThat(gerenciar.liberado(maria)).isFalse();
    }

    @Test
    void usuarioQueNaoExisteEAvisado() {
        assertThatThrownBy(() -> gerenciar.definir(admin, UUID.randomUUID(), true))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void aListaTemSoOsAtivosPorNomeComOAcessoDeCada() {
        usuarios.desativado("joao", "João");
        gerenciar.definir(admin, maria.id(), true);

        List<AcessoAoGeradorView> lista = gerenciar.listar(admin);

        assertThat(lista).extracting(AcessoAoGeradorView::nome).containsExactly("Administrador", "Coordenação", "Maria");
        assertThat(lista).extracting(AcessoAoGeradorView::gerador).containsExactly(false, false, true);
        assertThat(lista.get(1).perfis()).containsExactly("ROLE_VIEWER");
    }

    static class AcessosEmMemoria implements AcessosAoGerador {
        private final Map<UUID, AcessoAoGerador> porUsuario = new HashMap<>();

        @Override
        public Optional<AcessoAoGerador> buscar(UUID usuarioId) {
            return Optional.ofNullable(porUsuario.get(usuarioId));
        }

        @Override
        public List<AcessoAoGerador> listar() {
            return new ArrayList<>(porUsuario.values());
        }

        @Override
        public void salvar(AcessoAoGerador acesso) {
            porUsuario.put(acesso.usuarioId(), acesso);
        }
    }
}
