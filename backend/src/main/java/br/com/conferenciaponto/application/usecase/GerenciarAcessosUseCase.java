package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.UsuarioAlteradoEvento;
import br.com.conferenciaponto.domain.acesso.AjusteDeAcesso;
import br.com.conferenciaponto.domain.acesso.CatalogoDePermissoes;
import br.com.conferenciaponto.domain.acesso.MudancaDeAcesso;
import br.com.conferenciaponto.domain.acesso.PerfilDeAcesso;
import br.com.conferenciaponto.domain.acesso.Permissao;
import br.com.conferenciaponto.domain.acesso.PermissoesDaAdministracao;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.AcessosRepository;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Os perfis de acesso (criar, editar, excluir) e o acesso de cada pessoa (os perfis dela e os ajustes só dela).
 *
 * <p>Regras que valem em toda mudança: sempre sobra pelo menos uma pessoa ativa com "Perfis e acessos" (senão
 * ninguém mais conseguiria mudar os acessos), e ninguém tira essa permissão de si mesmo. O perfil Administrador
 * (tudo liberado) não muda; os perfis do sistema não são excluídos. Cada mudança vai para o histórico, e quem
 * teve o acesso mudado é avisado na hora (as telas abertas se atualizam; quem ganhou o próprio ponto recebe o
 * horário padrão e o ciclo do banco).
 */
@Service
public class GerenciarAcessosUseCase {

    private static final Logger log = LoggerFactory.getLogger(GerenciarAcessosUseCase.class);
    static final int TAMANHO_MAXIMO_NOME = 60;
    static final int TAMANHO_MAXIMO_DESCRICAO = 200;

    /** Uma pessoa que tem o perfil. */
    public record PessoaDoPerfil(UUID id, String nome, String login, boolean ativo) {
    }

    /**
     * Um perfil na tela.
     *
     * @param sistema    o perfil do sistema (ROLE_ADMIN, ROLE_USER, ROLE_VIEWER); null = criado na tela
     * @param permissoes o que ele libera (no Administrador, todas as que existem hoje)
     */
    public record PerfilView(UUID id, String nome, String descricao, boolean todas, boolean doSistema, String sistema,
                             List<String> permissoes, List<PessoaDoPerfil> pessoas, Instant atualizadoEm,
                             String atualizadoPor) {
    }

    /**
     * O acesso de uma pessoa: os perfis, os ajustes e, para cada permissão, se ela pode e por quê.
     *
     * @param perfis os ids dos perfis dela
     */
    public record AcessoDaPessoaView(UUID usuarioId, String nome, String login, boolean ativo, List<UUID> perfis,
                                     List<AjusteDeAcesso> ajustes, List<CatalogoDePermissoes.Origem> permissoes) {
    }

    /** Uma linha do histórico, com o nome da pessoa (quando a mudança foi numa pessoa). */
    public record MudancaView(Instant quando, String quem, UUID usuarioId, String pessoa, UUID perfilId,
                              String descricao) {
    }

    private final AcessosRepository acessos;
    private final UsuarioRepository usuarios;
    private final CatalogoDePermissoes catalogo;
    private final PreparacaoDoTitular preparacao;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public GerenciarAcessosUseCase(AcessosRepository acessos, UsuarioRepository usuarios, CatalogoDePermissoes catalogo,
                                   PreparacaoDoTitular preparacao, ApplicationEventPublisher eventos, Clock clock) {
        this.acessos = acessos;
        this.usuarios = usuarios;
        this.catalogo = catalogo;
        this.preparacao = preparacao;
        this.eventos = eventos;
        this.clock = clock;
    }

    /** Todas as permissões que existem, em grupos (as do ponto e as dos módulos). */
    public List<CatalogoDePermissoes.Grupo> catalogo() {
        return catalogo.grupos();
    }

    /** Todos os perfis, por nome, com o que liberam e quem os tem. */
    @Transactional(readOnly = true)
    public List<PerfilView> perfis() {
        Estado estado = estado();
        return estado.perfis.values().stream()
                .sorted(Comparator.comparing(PerfilDeAcesso::nome, String.CASE_INSENSITIVE_ORDER))
                .map(p -> view(p, estado))
                .toList();
    }

    @Transactional
    public PerfilView criarPerfil(Usuario quem, String nome, String descricao, Collection<String> permissoes) {
        Estado estado = estado();
        String limpo = validarNome(nome, null, estado);
        Set<String> codigos = validarPermissoes(permissoes);
        PerfilDeAcesso novo = PerfilDeAcesso.novo(limpo, validarDescricao(descricao), codigos, quem.login(),
                clock.instant());
        acessos.salvarPerfil(novo);
        estado.perfis.put(novo.id(), novo);
        registrar(quem, null, novo.id(), "Perfil " + limpo + " criado"
                + (codigos.isEmpty() ? ", ainda sem nada liberado." : ": " + nomes(codigos) + "."));
        return view(novo, estado);
    }

    @Transactional
    public PerfilView alterarPerfil(Usuario quem, UUID id, String nome, String descricao, Collection<String> permissoes) {
        Estado estado = estado();
        PerfilDeAcesso atual = perfilExistente(estado, id);
        if (atual.bloqueado()) {
            throw new RegraNegocioException("PERFIL_BLOQUEADO",
                    "O perfil " + atual.nome() + " tem tudo liberado e não muda. Crie outro perfil para dar menos.");
        }
        PerfilDeAcesso alterado = atual.comDados(validarNome(nome, id, estado), validarDescricao(descricao),
                validarPermissoes(permissoes), quem.login(), clock.instant());
        estado.perfis.put(id, alterado);
        conferirQueSobraQuemCuida(estado, quem);
        acessos.salvarPerfil(alterado);
        String mudou = descreverMudanca(atual, alterado);
        if (mudou != null) {
            registrar(quem, null, id, mudou);
            estado.quemTem(id).forEach(u -> avisar(u, quem));
        }
        return view(alterado, estado);
    }

    /** Exclui o perfil, que sai de quem o tinha (os perfis do sistema não são excluídos). */
    @Transactional
    public void excluirPerfil(Usuario quem, UUID id) {
        Estado estado = estado();
        PerfilDeAcesso atual = perfilExistente(estado, id);
        if (atual.doSistema()) {
            throw new RegraNegocioException("PERFIL_DO_SISTEMA", "O perfil " + atual.nome()
                    + " é do sistema e não pode ser excluído" + (atual.bloqueado() ? "." : ": dá para mudar o que ele libera."));
        }
        List<UUID> quemTinha = estado.quemTem(id);
        estado.perfis.remove(id);
        estado.perfisDe.values().forEach(perfis -> perfis.remove(id));
        conferirQueSobraQuemCuida(estado, quem);
        acessos.excluirPerfil(id);
        registrar(quem, null, id, "Perfil " + atual.nome() + " excluído"
                + (quemTinha.isEmpty() ? "." : "; saiu de " + pessoas(estado, quemTinha) + "."));
        quemTinha.forEach(u -> avisar(u, quem));
    }

    @Transactional(readOnly = true)
    public AcessoDaPessoaView acessoDe(UUID usuarioId) {
        Estado estado = estado();
        return view(estado, estado.usuario(usuarioId));
    }

    /** Troca os perfis e os ajustes da pessoa por estes. */
    @Transactional
    public AcessoDaPessoaView definirAcesso(Usuario quem, UUID usuarioId, Collection<UUID> perfis,
                                            Collection<AjusteDeAcesso> ajustes) {
        Estado estado = estado();
        Usuario alvo = estado.usuario(usuarioId);
        Set<UUID> novosPerfis = validarPerfis(estado, perfis);
        List<AjusteDeAcesso> novosAjustes = validarAjustes(ajustes);
        Set<UUID> perfisAntes = Set.copyOf(estado.perfisDe(usuarioId));
        List<AjusteDeAcesso> ajustesAntes = List.copyOf(estado.ajustesDe(usuarioId));
        estado.perfisDe.put(usuarioId, new LinkedHashSet<>(novosPerfis));
        estado.ajustesDe.put(usuarioId, novosAjustes);
        conferirQueSobraQuemCuida(estado, quem);

        Instant agora = clock.instant();
        acessos.definirPerfis(usuarioId, novosPerfis, quem.login(), agora);
        acessos.definirAjustes(usuarioId, novosAjustes, quem.login(), agora);
        String mudou = descreverMudanca(estado, alvo, perfisAntes, novosPerfis, ajustesAntes, novosAjustes);
        if (mudou != null) {
            registrar(quem, usuarioId, null, mudou);
            avisar(usuarioId, quem);
        }
        return view(estado, alvo);
    }

    /** O usuário recém-cadastrado ganha estes perfis (quem cadastra precisa cuidar dos acessos). */
    void darPerfisAoNovo(Usuario quem, Usuario novo, Collection<UUID> perfis) {
        Estado estado = estado();
        Set<UUID> ids = validarPerfis(estado, perfis);
        acessos.definirPerfis(novo.id(), ids, quem.login(), clock.instant());
        String nomes = ids.stream().map(estado.perfis::get).map(PerfilDeAcesso::nome)
                .sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));
        registrar(quem, novo.id(), null, "Usuário " + nomeOuLogin(novo) + " cadastrado"
                + (ids.isEmpty() ? " sem perfil (ainda sem acesso a nada)." : " com " + (ids.size() == 1 ? "o perfil " : "os perfis ") + nomes + "."));
    }

    /** Antes de desativar a pessoa: sobra quem cuide dos acessos? */
    void conferirDesativacao(Usuario quem, UUID usuarioId) {
        Estado estado = estado();
        estado.ativos.remove(usuarioId);
        conferirQueSobraQuemCuida(estado, quem);
    }

    /** As últimas mudanças (de uma pessoa, ou de todas com null), da mais nova para a mais antiga. */
    @Transactional(readOnly = true)
    public List<MudancaView> historico(UUID usuarioId, int limite) {
        Map<UUID, Usuario> porId = usuarios.listar().stream().collect(Collectors.toMap(Usuario::id, u -> u));
        return acessos.historico(usuarioId, Math.max(1, Math.min(limite, 200))).stream()
                .map(m -> new MudancaView(m.quando(), m.quem(), m.usuarioId(),
                        m.usuarioId() == null || !porId.containsKey(m.usuarioId()) ? null : nomeOuLogin(porId.get(m.usuarioId())),
                        m.perfilId(), m.descricao()))
                .toList();
    }

    // ------------------------------------------------------------------ regras

    private void conferirQueSobraQuemCuida(Estado estado, Usuario quem) {
        boolean sobra = estado.ativos.stream().anyMatch(u -> estado.efetivas(u).contains(PermissoesDaAdministracao.ACESSOS));
        if (!sobra) {
            throw new RegraNegocioException("SEM_QUEM_CUIDE_DOS_ACESSOS", "Assim ninguém ficaria com \""
                    + nome(PermissoesDaAdministracao.ACESSOS) + "\": é preciso manter pelo menos uma pessoa ativa que "
                    + "cuide dos acessos.");
        }
        if (quem.pode(PermissoesDaAdministracao.ACESSOS) && estado.ativos.contains(quem.id())
                && !estado.efetivas(quem.id()).contains(PermissoesDaAdministracao.ACESSOS)) {
            throw new RegraNegocioException("ALTERAR_PROPRIO_ACESSO", "Assim você tiraria de si mesmo \""
                    + nome(PermissoesDaAdministracao.ACESSOS) + "\". Se é isso mesmo, peça a outra pessoa que cuida dos acessos.");
        }
    }

    private String validarNome(String nome, UUID idAtual, Estado estado) {
        String limpo = nome == null ? "" : nome.strip().replaceAll("\\s+", " ");
        if (limpo.length() < 2) {
            throw new RegraNegocioException("NOME_DO_PERFIL", "Dê um nome ao perfil (pelo menos 2 letras).");
        }
        if (limpo.length() > TAMANHO_MAXIMO_NOME) {
            throw new RegraNegocioException("NOME_DO_PERFIL",
                    "O nome do perfil pode ter no máximo %d caracteres.".formatted(TAMANHO_MAXIMO_NOME));
        }
        estado.perfis.values().stream()
                .filter(p -> !p.id().equals(idAtual) && p.nome().equalsIgnoreCase(limpo))
                .findFirst()
                .ifPresent(p -> {
                    throw new ConflitoException("PERFIL_EM_USO", "Já existe um perfil chamado \"%s\".".formatted(p.nome()));
                });
        return limpo;
    }

    private static String validarDescricao(String descricao) {
        if (descricao == null || descricao.isBlank()) {
            return null;
        }
        String limpa = descricao.strip();
        if (limpa.length() > TAMANHO_MAXIMO_DESCRICAO) {
            throw new RegraNegocioException("DESCRICAO_DO_PERFIL",
                    "A descrição pode ter no máximo %d caracteres.".formatted(TAMANHO_MAXIMO_DESCRICAO));
        }
        return limpa;
    }

    /** Só permissões que existem; o requisito de cada uma entra junto (curar a base leva pesquisar). */
    private Set<String> validarPermissoes(Collection<String> permissoes) {
        Set<String> codigos = new LinkedHashSet<>();
        for (String codigo : permissoes == null ? List.<String>of() : permissoes) {
            exigirConhecida(codigo);
            codigos.add(codigo);
        }
        boolean entrou;
        do {
            entrou = false;
            for (String codigo : List.copyOf(codigos)) {
                String requisito = catalogo.permissao(codigo).map(Permissao::requer).orElse(null);
                if (requisito != null && codigos.add(requisito)) {
                    entrou = true;
                }
            }
        } while (entrou);
        return catalogo.soAsConhecidas(codigos);
    }

    private void exigirConhecida(String codigo) {
        if (codigo == null || !catalogo.existe(codigo)) {
            throw new RegraNegocioException("PERMISSAO_DESCONHECIDA",
                    "A permissão \"%s\" não existe. Recarregue a tela.".formatted(codigo));
        }
    }

    private Set<UUID> validarPerfis(Estado estado, Collection<UUID> perfis) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (UUID id : perfis == null ? List.<UUID>of() : perfis) {
            if (id == null || !estado.perfis.containsKey(id)) {
                throw new RecursoNaoEncontradoException("PERFIL_NAO_ENCONTRADO",
                        "Um dos perfis escolhidos não existe mais. Recarregue a tela.");
            }
            ids.add(id);
        }
        return ids;
    }

    private List<AjusteDeAcesso> validarAjustes(Collection<AjusteDeAcesso> ajustes) {
        Map<String, AjusteDeAcesso> porCodigo = new LinkedHashMap<>();
        for (AjusteDeAcesso ajuste : ajustes == null ? List.<AjusteDeAcesso>of() : ajustes) {
            exigirConhecida(ajuste.permissao());
            if (porCodigo.put(ajuste.permissao(), ajuste) != null) {
                throw new RegraNegocioException("AJUSTE_REPETIDO",
                        "A permissão \"%s\" veio duas vezes nos ajustes.".formatted(nome(ajuste.permissao())));
            }
        }
        return List.copyOf(porCodigo.values());
    }

    private static PerfilDeAcesso perfilExistente(Estado estado, UUID id) {
        PerfilDeAcesso perfil = estado.perfis.get(id);
        if (perfil == null) {
            throw new RecursoNaoEncontradoException("PERFIL_NAO_ENCONTRADO", "Este perfil não existe mais. Recarregue a tela.");
        }
        return perfil;
    }

    // ------------------------------------------------------- histórico e avisos

    private void registrar(Usuario quem, UUID usuarioId, UUID perfilId, String descricao) {
        acessos.registrar(new MudancaDeAcesso(clock.instant(), quem.login(), usuarioId, perfilId, descricao));
        log.info("Acessos ({}): {}", quem.login(), descricao);
    }

    /** As telas abertas da pessoa se atualizam; quem ganhou o próprio ponto recebe horário e ciclo do banco. */
    private void avisar(UUID usuarioId, Usuario quem) {
        usuarios.buscarPorId(usuarioId).ifPresent(u -> preparacao.preparar(u, quem.login()));
        eventos.publishEvent(new UsuarioAlteradoEvento(usuarioId, "Acesso alterado"));
    }

    private String descreverMudanca(PerfilDeAcesso antes, PerfilDeAcesso depois) {
        List<String> partes = new ArrayList<>();
        if (!antes.nome().equals(depois.nome())) {
            partes.add("renomeado de " + antes.nome() + " para " + depois.nome());
        }
        Set<String> ganhou = new TreeSet<>(depois.permissoes());
        ganhou.removeAll(antes.permissoes());
        Set<String> perdeu = new TreeSet<>(antes.permissoes());
        perdeu.removeAll(depois.permissoes());
        if (!ganhou.isEmpty()) {
            partes.add("passou a liberar " + nomes(ganhou));
        }
        if (!perdeu.isEmpty()) {
            partes.add("deixou de liberar " + nomes(perdeu));
        }
        if (!Objects.equals(antes.descricao(), depois.descricao())) {
            partes.add("descrição alterada");
        }
        return partes.isEmpty() ? null : "Perfil " + depois.nome() + ": " + String.join("; ", partes) + ".";
    }

    private String descreverMudanca(Estado estado, Usuario alvo, Set<UUID> perfisAntes, Set<UUID> perfisDepois,
                                    List<AjusteDeAcesso> ajustesAntes, List<AjusteDeAcesso> ajustesDepois) {
        List<String> partes = new ArrayList<>();
        Set<UUID> ganhou = new LinkedHashSet<>(perfisDepois);
        ganhou.removeAll(perfisAntes);
        Set<UUID> perdeu = new LinkedHashSet<>(perfisAntes);
        perdeu.removeAll(perfisDepois);
        if (!ganhou.isEmpty()) {
            partes.add((ganhou.size() == 1 ? "ganhou o perfil " : "ganhou os perfis ") + nomesDosPerfis(estado, ganhou));
        }
        if (!perdeu.isEmpty()) {
            partes.add((perdeu.size() == 1 ? "perdeu o perfil " : "perdeu os perfis ") + nomesDosPerfis(estado, perdeu));
        }
        Map<String, Boolean> antes = new HashMap<>();
        ajustesAntes.forEach(a -> antes.put(a.permissao(), a.liberada()));
        Map<String, Boolean> depois = new HashMap<>();
        ajustesDepois.forEach(a -> depois.put(a.permissao(), a.liberada()));
        List<String> liberadas = new ArrayList<>();
        List<String> retiradas = new ArrayList<>();
        List<String> voltaram = new ArrayList<>();
        for (String codigo : catalogo.codigos()) {
            Boolean a = antes.get(codigo);
            Boolean d = depois.get(codigo);
            if (Objects.equals(a, d)) {
                continue;
            }
            if (d == null) {
                voltaram.add(nome(codigo));
            } else if (d) {
                liberadas.add(nome(codigo));
            } else {
                retiradas.add(nome(codigo));
            }
        }
        if (!liberadas.isEmpty()) {
            partes.add("liberado a mais: " + String.join(", ", liberadas));
        }
        if (!retiradas.isEmpty()) {
            partes.add("retirado: " + String.join(", ", retiradas));
        }
        if (!voltaram.isEmpty()) {
            partes.add("voltou a seguir os perfis em: " + String.join(", ", voltaram));
        }
        return partes.isEmpty() ? null : "Acesso de " + nomeOuLogin(alvo) + ": " + String.join("; ", partes) + ".";
    }

    private String nomes(Collection<String> codigos) {
        return catalogo.soAsConhecidas(codigos).stream().map(this::nome).collect(Collectors.joining(", "));
    }

    private String nome(String codigo) {
        return catalogo.permissao(codigo).map(Permissao::nome).orElse(codigo);
    }

    private static String nomesDosPerfis(Estado estado, Collection<UUID> ids) {
        return ids.stream().map(estado.perfis::get).filter(Objects::nonNull).map(PerfilDeAcesso::nome)
                .sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));
    }

    private static String pessoas(Estado estado, Collection<UUID> ids) {
        return ids.stream().map(estado.pessoas::get).filter(Objects::nonNull).map(GerenciarAcessosUseCase::nomeOuLogin)
                .sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.joining(", "));
    }

    private static String nomeOuLogin(Usuario u) {
        return u.nome() == null || u.nome().isBlank() ? u.login() : u.nome();
    }

    // -------------------------------------------------------------------- views

    private PerfilView view(PerfilDeAcesso perfil, Estado estado) {
        List<PessoaDoPerfil> pessoas = estado.quemTem(perfil.id()).stream()
                .map(estado.pessoas::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(Usuario::ativo).reversed()
                        .thenComparing(u -> nomeOuLogin(u).toLowerCase(Locale.ROOT)))
                .map(u -> new PessoaDoPerfil(u.id(), u.nome(), u.login(), u.ativo()))
                .toList();
        return new PerfilView(perfil.id(), perfil.nome(), perfil.descricao(), perfil.todas(), perfil.doSistema(),
                perfil.sistema() == null ? null : perfil.sistema().name(), List.copyOf(perfil.permissoesNo(catalogo)),
                pessoas, perfil.atualizadoEm(), perfil.atualizadoPor());
    }

    private AcessoDaPessoaView view(Estado estado, Usuario usuario) {
        List<PerfilDeAcesso> perfis = estado.perfisCompletos(usuario.id());
        List<AjusteDeAcesso> ajustes = estado.ajustesDe(usuario.id());
        return new AcessoDaPessoaView(usuario.id(), usuario.nome(), usuario.login(), usuario.ativo(),
                perfis.stream().map(PerfilDeAcesso::id).toList(), ajustes, catalogo.explicar(perfis, ajustes));
    }

    // -------------------------------------------------------------------- estado

    private Estado estado() {
        return new Estado(acessos.perfis(), acessos.perfisPorUsuario(), acessos.ajustesPorUsuario(), usuarios.listar());
    }

    /** Uma foto dos acessos de todos, para simular uma mudança antes de gravar. */
    private final class Estado {

        final Map<UUID, PerfilDeAcesso> perfis = new LinkedHashMap<>();
        final Map<UUID, Set<UUID>> perfisDe = new HashMap<>();
        final Map<UUID, List<AjusteDeAcesso>> ajustesDe = new HashMap<>();
        final Map<UUID, Usuario> pessoas = new LinkedHashMap<>();
        final Set<UUID> ativos = new LinkedHashSet<>();

        Estado(List<PerfilDeAcesso> perfis, Map<UUID, Set<UUID>> perfisDe, Map<UUID, List<AjusteDeAcesso>> ajustesDe,
               List<Usuario> todos) {
            perfis.forEach(p -> this.perfis.put(p.id(), p));
            perfisDe.forEach((u, ids) -> this.perfisDe.put(u, new LinkedHashSet<>(ids)));
            this.ajustesDe.putAll(ajustesDe);
            for (Usuario u : todos) {
                pessoas.put(u.id(), u);
                if (u.ativo()) {
                    ativos.add(u.id());
                }
            }
        }

        Usuario usuario(UUID id) {
            Usuario u = id == null ? null : pessoas.get(id);
            if (u == null) {
                throw new RecursoNaoEncontradoException("USUARIO_NAO_ENCONTRADO", "Usuário não encontrado. Recarregue a tela.");
            }
            return u;
        }

        Set<UUID> perfisDe(UUID usuarioId) {
            return perfisDe.getOrDefault(usuarioId, Set.of());
        }

        List<AjusteDeAcesso> ajustesDe(UUID usuarioId) {
            return ajustesDe.getOrDefault(usuarioId, List.of());
        }

        List<PerfilDeAcesso> perfisCompletos(UUID usuarioId) {
            return perfisDe(usuarioId).stream().map(perfis::get).filter(Objects::nonNull)
                    .sorted(Comparator.comparing(PerfilDeAcesso::nome, String.CASE_INSENSITIVE_ORDER)).toList();
        }

        Set<String> efetivas(UUID usuarioId) {
            return catalogo.efetivas(perfisCompletos(usuarioId), ajustesDe(usuarioId));
        }

        List<UUID> quemTem(UUID perfilId) {
            return perfisDe.entrySet().stream().filter(e -> e.getValue().contains(perfilId)).map(Map.Entry::getKey).toList();
        }
    }
}
