package br.com.conferenciaponto.infrastructure.web.acesso;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.CredenciaisInvalidasException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Locale;

/**
 * Regras de acesso entre usuários:
 * <ul>
 *   <li>cada titular (ADMIN/USER) vê e altera os próprios dados;</li>
 *   <li>administrador e coordenação (VIEWER) consultam os dados de qualquer titular;</li>
 *   <li>alterações nos dados de ponto são sempre do próprio usuário logado;</li>
 *   <li>horário e pasta de comprovantes: o próprio usuário ou o administrador.</li>
 * </ul>
 * O usuário é relido do banco a cada requisição: desativar ou trocar o perfil vale na hora.
 */
@Component
public class AcessoUsuarios {

    private final UsuarioRepository usuarios;

    public AcessoUsuarios(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    /** Usuário do token da requisição atual (ativo). */
    public Usuario logado() {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao == null || autenticacao.getName() == null) {
            throw new CredenciaisInvalidasException();
        }
        return logado(autenticacao.getName());
    }

    public Usuario logado(String login) {
        return usuarios.buscarPorLogin(login).filter(Usuario::ativo)
                .orElseThrow(CredenciaisInvalidasException::acessoDesativado);
    }

    /**
     * Titular dos dados pedidos.
     *
     * @param loginPedido {@code ?usuario=} (vazio = o próprio; para a coordenação, o primeiro titular)
     * @param escrita     requisição que altera dados: só nos próprios
     */
    public Titular titular(Usuario logado, String loginPedido, boolean escrita) {
        if (logado.trocarSenha()) {
            throw new AcessoNegadoException("TROCAR_SENHA", "Troque a senha provisória para continuar.");
        }
        boolean semPedido = loginPedido == null || loginPedido.isBlank()
                || loginPedido.strip().toLowerCase(Locale.ROOT).equals(logado.login());
        if (semPedido) {
            if (logado.isTitular()) {
                return Titular.de(logado, logado);
            }
            if (escrita) {
                throw new AcessoNegadoException("SOMENTE_CONSULTA", "Seu perfil é somente de consulta.");
            }
            return Titular.de(primeiroTitular(), logado);
        }
        if (escrita) {
            throw new AcessoNegadoException("ALTERAR_DADOS_DE_OUTRO",
                    "Só o próprio usuário altera os dados de ponto dele.");
        }
        if (!logado.podeVerTodos()) {
            throw new AcessoNegadoException("ACESSO_NEGADO", "Você só pode consultar os seus dados.");
        }
        return Titular.de(titularPorLogin(loginPedido), logado);
    }

    /** Usuário cujo horário/pasta será alterado: o próprio ou, para o administrador, qualquer titular. */
    public Usuario alvoDeEdicao(Usuario logado, String loginPedido) {
        if (logado.trocarSenha()) {
            throw new AcessoNegadoException("TROCAR_SENHA", "Troque a senha provisória para continuar.");
        }
        if (loginPedido == null || loginPedido.isBlank()
                || loginPedido.strip().toLowerCase(Locale.ROOT).equals(logado.login())) {
            if (!logado.isTitular()) {
                throw new AcessoNegadoException("SOMENTE_CONSULTA", "Seu perfil é somente de consulta.");
            }
            return logado;
        }
        if (!logado.isAdmin()) {
            throw new AcessoNegadoException("ACESSO_NEGADO", "Só o administrador altera o horário de outra pessoa.");
        }
        return titularPorLogin(loginPedido);
    }

    private Usuario titularPorLogin(String login) {
        return usuarios.buscarPorLogin(login.strip().toLowerCase(Locale.ROOT))
                .filter(Usuario::isTitular)
                .orElseThrow(() -> new RecursoNaoEncontradoException("USUARIO_NAO_ENCONTRADO",
                        "Usuário \"%s\" não encontrado.".formatted(login.strip())));
    }

    private Usuario primeiroTitular() {
        return usuarios.listar().stream()
                .filter(u -> u.ativo() && u.isTitular())
                .min(Comparator.comparing((Usuario u) -> u.nome() == null ? u.login() : u.nome(),
                        String.CASE_INSENSITIVE_ORDER))
                .orElseThrow(() -> new RecursoNaoEncontradoException("SEM_TITULARES",
                        "Ainda não há usuários com dados de ponto."));
    }
}
