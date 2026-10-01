package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.view.SessaoView;
import br.com.conferenciaponto.domain.exception.CredenciaisInvalidasException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.CodificadorSenha;
import br.com.conferenciaponto.domain.port.EmissorToken;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutenticarUsuarioUseCaseTest {

    private static final Instant AGORA = Instant.parse("2026-09-29T11:00:00Z");

    private final Map<String, Usuario> banco = new HashMap<>();
    private final UsuarioRepository usuarios = new UsuarioRepository() {
        @Override
        public Optional<Usuario> buscarPorLogin(String login) {
            return Optional.ofNullable(banco.get(login));
        }

        @Override
        public Optional<Usuario> buscarPorId(java.util.UUID id) {
            return banco.values().stream().filter(u -> u.id().equals(id)).findFirst();
        }

        @Override
        public java.util.List<Usuario> listar() {
            return java.util.List.copyOf(banco.values());
        }

        @Override
        public void salvar(Usuario usuario) {
            banco.put(usuario.login(), usuario);
        }
    };
    /** "Hash" reversível apenas para teste. */
    private final CodificadorSenha codificador = new CodificadorSenha() {
        @Override
        public String codificar(String senha) {
            return "h:" + senha;
        }

        @Override
        public boolean confere(String senha, String hash) {
            return ("h:" + senha).equals(hash);
        }
    };
    private final EmissorToken emissor = u -> new EmissorToken.TokenEmitido("token-" + u.login(), AGORA.plusSeconds(3600));
    private final AutenticarUsuarioUseCase useCase =
            new AutenticarUsuarioUseCase(usuarios, codificador, emissor, Clock.fixed(AGORA, ZoneOffset.UTC));

    private void cadastrar(String login, String senha, Perfil perfil) {
        usuarios.salvar(Usuario.novo(login, login, codificador.codificar(senha), Set.of(perfil)));
    }

    @Test
    @DisplayName("Login correto emite token e registra o último acesso (login é case-insensitive)")
    void loginValido() {
        cadastrar("coordenacao", "senha-forte", Perfil.ROLE_VIEWER);

        SessaoView sessao = useCase.autenticar("  Coordenacao ", "senha-forte");

        assertThat(sessao.token()).isEqualTo("token-coordenacao");
        assertThat(sessao.usuario().podeEscrever()).isFalse();
        assertThat(banco.get("coordenacao").ultimoLoginEm()).isEqualTo(AGORA);
    }

    @Test
    @DisplayName("Senha errada, usuário inexistente ou inativo: mesma resposta (sem revelar qual)")
    void credenciaisInvalidas() {
        cadastrar("eduardo", "senha-forte", Perfil.ROLE_ADMIN);
        Usuario inativo = Usuario.novo("antigo", "Antigo", codificador.codificar("senha-forte"), Set.of(Perfil.ROLE_USER));
        usuarios.salvar(new Usuario(inativo.id(), inativo.login(), inativo.nome(), inativo.senhaHash(), false,
                inativo.perfis(), null));

        assertThatThrownBy(() -> useCase.autenticar("eduardo", "errada")).isInstanceOf(CredenciaisInvalidasException.class);
        assertThatThrownBy(() -> useCase.autenticar("ninguem", "senha-forte")).isInstanceOf(CredenciaisInvalidasException.class);
        assertThatThrownBy(() -> useCase.autenticar("antigo", "senha-forte")).isInstanceOf(CredenciaisInvalidasException.class);
    }

    @Test
    @DisplayName("Troca de senha exige a senha atual e no mínimo 8 caracteres")
    void alterarSenha() {
        cadastrar("coordenacao", "senha-forte", Perfil.ROLE_VIEWER);

        assertThatThrownBy(() -> useCase.alterarSenha("coordenacao", "errada", "nova-senha-123"))
                .extracting("codigo").isEqualTo("SENHA_ATUAL_INCORRETA");
        assertThatThrownBy(() -> useCase.alterarSenha("coordenacao", "senha-forte", "curta"))
                .extracting("codigo").isEqualTo("SENHA_FRACA");

        useCase.alterarSenha("coordenacao", "senha-forte", "nova-senha-123");
        assertThat(useCase.autenticar("coordenacao", "nova-senha-123").token()).isEqualTo("token-coordenacao");
    }
}
