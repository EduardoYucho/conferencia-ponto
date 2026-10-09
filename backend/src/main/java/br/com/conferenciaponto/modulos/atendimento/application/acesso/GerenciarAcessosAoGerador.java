package br.com.conferenciaponto.modulos.atendimento.application.acesso;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.modulos.atendimento.domain.acesso.AcessoAoGerador;
import br.com.conferenciaponto.modulos.atendimento.domain.acesso.AcessosAoGerador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Quem pode usar o gerador: consulta (a cada requisição do módulo) e a liberação pelo administrador. */
@Service
public class GerenciarAcessosAoGerador {

    private static final Logger log = LoggerFactory.getLogger(GerenciarAcessosAoGerador.class);

    private final AcessosAoGerador acessos;
    private final UsuarioRepository usuarios;
    private final Clock clock;

    public GerenciarAcessosAoGerador(AcessosAoGerador acessos, UsuarioRepository usuarios, Clock clock) {
        this.acessos = acessos;
        this.usuarios = usuarios;
        this.clock = clock;
    }

    /** A pessoa pode usar o gerador agora? (usuário desativado nunca pode) */
    public boolean liberado(Usuario usuario) {
        return usuario.ativo() && acessos.buscar(usuario.id()).map(AcessoAoGerador::gerador).orElse(false);
    }

    /** Todos os usuários ativos, por nome, com o acesso de cada um. Só o administrador. */
    public List<AcessoAoGeradorView> listar(Usuario administrador) {
        exigirAdministrador(administrador);
        Map<UUID, AcessoAoGerador> porUsuario = acessos.listar().stream()
                .collect(Collectors.toMap(AcessoAoGerador::usuarioId, Function.identity()));
        return usuarios.listar().stream()
                .filter(Usuario::ativo)
                .sorted(Comparator.comparing(Usuario::nome, String.CASE_INSENSITIVE_ORDER))
                .map(u -> view(u, porUsuario.getOrDefault(u.id(), AcessoAoGerador.semAcesso(u.id()))))
                .toList();
    }

    /** Libera ou retira o gerador de uma pessoa. Só o administrador; liberar exige usuário ativo. */
    public AcessoAoGeradorView definir(Usuario administrador, UUID usuarioId, boolean gerador) {
        exigirAdministrador(administrador);
        Usuario usuario = usuarios.buscarPorId(usuarioId).orElseThrow(() -> new RecursoNaoEncontradoException(
                "USUARIO_NAO_ENCONTRADO", "Usuário não encontrado. Recarregue a lista de acessos."));
        if (gerador && !usuario.ativo()) {
            throw new RegraNegocioException("USUARIO_INATIVO",
                    usuario.nome() + " está desativado. Reative a pessoa em Usuários antes de liberar o gerador.");
        }
        AcessoAoGerador novo = new AcessoAoGerador(usuarioId, gerador, administrador.login(), clock.instant());
        acessos.salvar(novo);
        log.info("Gerador de atendimentos {} para {} (por {})", gerador ? "liberado" : "retirado", usuario.login(),
                administrador.login());
        return view(usuario, novo);
    }

    private static void exigirAdministrador(Usuario usuario) {
        if (!usuario.isAdmin()) {
            throw new AcessoNegadoException("ACESSO_NEGADO", "Só o administrador libera o acesso aos módulos.");
        }
    }

    private static AcessoAoGeradorView view(Usuario usuario, AcessoAoGerador acesso) {
        List<String> perfis = usuario.perfis().stream().map(Enum::name).sorted().toList();
        return new AcessoAoGeradorView(usuario.id(), usuario.login(), usuario.nome(), perfis, acesso.gerador(),
                acesso.concedidoPor(), acesso.atualizadoEm());
    }
}
