package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.seguranca.TentativasDeLogin;
import br.com.conferenciaponto.application.view.SessaoView;
import br.com.conferenciaponto.domain.exception.CredenciaisInvalidasException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.CodificadorSenha;
import br.com.conferenciaponto.domain.port.EmissorToken;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/** Login (emite o JWT, com o freio de tentativas), consulta do usuário logado e troca de senha. */
@Service
public class AutenticarUsuarioUseCase {

    static final int TAMANHO_MINIMO_SENHA = 8;

    private final UsuarioRepository usuarios;
    private final CodificadorSenha codificador;
    private final EmissorToken emissor;
    private final Clock clock;
    private final TentativasDeLogin tentativas;
    /** Hash usado quando o login não existe: mantém o tempo de resposta igual (evita enumeração). */
    private final String hashFicticio;

    @Autowired
    public AutenticarUsuarioUseCase(UsuarioRepository usuarios, CodificadorSenha codificador, EmissorToken emissor,
                                    Clock clock, TentativasDeLogin tentativas) {
        this.usuarios = usuarios;
        this.codificador = codificador;
        this.emissor = emissor;
        this.clock = clock;
        this.tentativas = tentativas;
        this.hashFicticio = codificador.codificar("senha-ficticia-para-tempo-constante");
    }

    public AutenticarUsuarioUseCase(UsuarioRepository usuarios, CodificadorSenha codificador, EmissorToken emissor,
                                    Clock clock) {
        this(usuarios, codificador, emissor, clock, new TentativasDeLogin(clock));
    }

    public SessaoView autenticar(String login, String senha) {
        return autenticar(login, senha, null);
    }

    /**
     * @param origem o endereço de quem tenta ({@code null} = desconhecido): erros demais do mesmo computador também
     *               bloqueiam (TentativasDeLogin)
     */
    @Transactional
    public SessaoView autenticar(String login, String senha, String origem) {
        if (login == null || login.isBlank() || senha == null) {
            throw new CredenciaisInvalidasException();
        }
        tentativas.conferir(login, origem);
        Optional<Usuario> encontrado = usuarios.buscarPorLogin(Usuario.normalizarLogin(login));
        boolean senhaConfere = codificador.confere(senha, encontrado.map(Usuario::senhaHash).orElse(hashFicticio));
        Optional<Usuario> valido = encontrado.filter(u -> senhaConfere && u.ativo());
        if (valido.isEmpty()) {
            tentativas.falhou(login, origem);
            throw new CredenciaisInvalidasException();
        }
        tentativas.entrou(login);
        Usuario usuario = valido.get();

        // relê antes de gravar: a conferência da senha demora, e o administrador pode ter alterado o cadastro
        Usuario logado = usuarios.buscarPorId(usuario.id()).filter(Usuario::ativo)
                .orElseThrow(CredenciaisInvalidasException::new)
                .registrarLogin(clock.instant());
        usuarios.salvar(logado);
        EmissorToken.TokenEmitido token = emissor.emitir(logado);
        return new SessaoView(token.valor(), token.expiraEm(), logado);
    }

    @Transactional(readOnly = true)
    public Usuario usuarioAtual(String login) {
        return usuarios.buscarPorLogin(login).filter(Usuario::ativo)
                .orElseThrow(CredenciaisInvalidasException::acessoDesativado);
    }

    /**
     * Troca a própria senha. As sessões abertas com a senha antiga deixam de valer (o token leva a impressão da
     * senha); a desta tela continua com o token novo que volta daqui.
     */
    @Transactional
    public SessaoView alterarSenha(String login, String senhaAtual, String novaSenha) {
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
        Usuario alterado = usuario.comSenha(codificador.codificar(novaSenha));
        usuarios.salvar(alterado);
        EmissorToken.TokenEmitido token = emissor.emitir(alterado);
        return new SessaoView(token.valor(), token.expiraEm(), alterado);
    }
}
