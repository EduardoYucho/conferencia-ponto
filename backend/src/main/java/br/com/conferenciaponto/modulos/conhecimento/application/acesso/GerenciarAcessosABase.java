package br.com.conferenciaponto.modulos.conhecimento.application.acesso;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.modulos.conhecimento.domain.acesso.AcessoABase;
import br.com.conferenciaponto.modulos.conhecimento.domain.acesso.AcessosABase;
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

/** Quem pesquisa e quem cura a base: consulta (a cada requisição do módulo) e a liberação pelo administrador. */
@Service
public class GerenciarAcessosABase {

    private static final Logger log = LoggerFactory.getLogger(GerenciarAcessosABase.class);

    private final AcessosABase acessos;
    private final UsuarioRepository usuarios;
    private final Clock clock;

    public GerenciarAcessosABase(AcessosABase acessos, UsuarioRepository usuarios, Clock clock) {
        this.acessos = acessos;
        this.usuarios = usuarios;
        this.clock = clock;
    }

    /** O acesso da pessoa agora (usuário desativado não tem nenhum). */
    public AcessoABase acessoDe(Usuario usuario) {
        if (!usuario.ativo()) {
            return AcessoABase.semAcesso(usuario.id());
        }
        return acessos.buscar(usuario.id()).orElseGet(() -> AcessoABase.semAcesso(usuario.id()));
    }

    /** Todos os usuários ativos, por nome, com o acesso de cada um. Só o administrador. */
    public List<AcessoABaseView> listar(Usuario administrador) {
        exigirAdministrador(administrador);
        Map<UUID, AcessoABase> porUsuario = acessos.listar().stream()
                .collect(Collectors.toMap(AcessoABase::usuarioId, Function.identity()));
        return usuarios.listar().stream()
                .filter(Usuario::ativo)
                .sorted(Comparator.comparing(Usuario::nome, String.CASE_INSENSITIVE_ORDER))
                .map(u -> view(u, porUsuario.getOrDefault(u.id(), AcessoABase.semAcesso(u.id()))))
                .toList();
    }

    /**
     * Muda o acesso de uma pessoa à base. Só o administrador; liberar exige usuário ativo. Curar inclui
     * pesquisar.
     */
    public AcessoABaseView definir(Usuario administrador, UUID usuarioId, boolean pesquisar, boolean curar) {
        exigirAdministrador(administrador);
        Usuario usuario = usuarios.buscarPorId(usuarioId).orElseThrow(() -> new RecursoNaoEncontradoException(
                "USUARIO_NAO_ENCONTRADO", "Usuário não encontrado. Recarregue a lista de acessos."));
        if ((pesquisar || curar) && !usuario.ativo()) {
            throw new RegraNegocioException("USUARIO_INATIVO",
                    usuario.nome() + " está desativado. Reative a pessoa em Usuários antes de liberar a base.");
        }
        AcessoABase novo = new AcessoABase(usuarioId, pesquisar, curar, administrador.login(), clock.instant());
        acessos.salvar(novo);
        log.info("Base de conhecimento para {}: pesquisar={}, curar={} (por {})", usuario.login(), novo.pesquisar(),
                novo.curar(), administrador.login());
        return view(usuario, novo);
    }

    private static void exigirAdministrador(Usuario usuario) {
        if (!usuario.isAdmin()) {
            throw new AcessoNegadoException("ACESSO_NEGADO", "Só o administrador libera o acesso aos módulos.");
        }
    }

    private static AcessoABaseView view(Usuario usuario, AcessoABase acesso) {
        List<String> perfis = usuario.perfis().stream().map(Enum::name).sorted().toList();
        return new AcessoABaseView(usuario.id(), usuario.login(), usuario.nome(), perfis, acesso.pesquisar(),
                acesso.curar(), acesso.concedidoPor(), acesso.atualizadoEm());
    }
}
