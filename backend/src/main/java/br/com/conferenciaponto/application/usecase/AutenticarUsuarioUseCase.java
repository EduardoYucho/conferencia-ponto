package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.view.SessaoView;
import br.com.conferenciaponto.domain.exception.CredenciaisInvalidasException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.CodificadorSenha;
import br.com.conferenciaponto.domain.port.EmissorToken;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/** Login (emite o JWT), consulta do usuário logado e troca de senha. */
@Service
public class AutenticarUsuarioUseCase {

    static final int TAMANHO_MINIMO_SENHA = 8;

    private final UsuarioRepository usuarios;
    private final CodificadorSenha codificador;
    private final EmissorToken emissor;
    private final Clock clock;
    /** Hash usado quando o login não existe: mantém o tempo de resposta igual (evita enumeração). */
    private final String hashFicticio;

    public AutenticarUsuarioUseCase(UsuarioRepository usuarios, CodificadorSenha codificador, EmissorToken emissor,
                                    Clock clock) {
        this.usuarios = usuarios;
        this.codificador = codificador;
        this.emissor = emissor;
        this.clock = clock;
        this.hashFicticio = codificador.codificar("senha-ficticia-para-tempo-constante");
    }

    @Transactional
    public SessaoView autenticar(String login, String senha) {
        if (login == null || login.isBlank() || senha == null) {
            throw new CredenciaisInvalidasException();
        }
        Optional<Usuario> encontrado = usuarios.buscarPorLogin(Usuario.normalizarLogin(login));
        boolean senhaConfere = codificador.confere(senha, encontrado.map(Usuario::senhaHash).orElse(hashFicticio));
        Usuario usuario = encontrado.filter(u -> senhaConfere && u.ativo())
                .orElseThrow(CredenciaisInvalidasException::new);

        Usuario logado = usuario.registrarLogin(clock.instant());
        usuarios.salvar(logado);
        EmissorToken.TokenEmitido token = emissor.emitir(logado);
        return new SessaoView(token.valor(), token.expiraEm(), logado);
    }

    @Transactional(readOnly = true)
    public Usuario usuarioAtual(String login) {
        return usuarios.buscarPorLogin(login).filter(Usuario::ativo)
                .orElseThrow(CredenciaisInvalidasException::new);
    }

    @Transactional
    public void alterarSenha(String login, String senhaAtual, String novaSenha) {
        Usuario usuario = usuarioAtual(login);
        if (senhaAtual == null || !codificador.confere(senhaAtual, usuario.senhaHash())) {
            throw new RegraNegocioException("SENHA_ATUAL_INCORRETA", "A senha atual não confere.");
        }
        if (novaSenha == null || novaSenha.length() < TAMANHO_MINIMO_SENHA) {
            throw new RegraNegocioException("SENHA_FRACA",
                    "A nova senha precisa ter pelo menos %d caracteres.".formatted(TAMANHO_MINIMO_SENHA));
        }
        GerenciarUsuariosUseCase.validarSenha(novaSenha);
        if (codificador.confere(novaSenha, usuario.senhaHash())) {
            throw new RegraNegocioException("SENHA_REPETIDA", "A nova senha precisa ser diferente da atual.");
        }
        usuarios.salvar(usuario.comSenha(codificador.codificar(novaSenha)));
    }
}
