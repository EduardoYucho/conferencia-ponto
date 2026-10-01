package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.evento.UsuarioAlteradoEvento;
import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.CodificadorSenha;
import br.com.conferenciaponto.domain.port.HorarioTrabalhoRepository;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Cadastro de usuários (administrador) e pasta de comprovantes (o próprio usuário ou o administrador).
 *
 * <p>Usuários não são excluídos (os dados de ponto ficam): são desativados. O administrador cria o acesso com
 * uma senha provisória, que a pessoa troca no primeiro login. Quem é titular (ADMIN/USER) ganha o horário
 * padrão e o ciclo do banco de horas; a coordenação (VIEWER) só consulta.
 */
@Service
public class GerenciarUsuariosUseCase {

    private static final Pattern LOGIN = Pattern.compile("[a-z0-9][a-z0-9._-]{2,59}");
    static final int TAMANHO_MAXIMO_PASTA = 500;

    private final UsuarioRepository usuarios;
    private final CodificadorSenha codificador;
    private final HorarioTrabalhoRepository horarios;
    private final RegrasJornada regras;
    private final GerenciarCicloBancoUseCase ciclos;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public GerenciarUsuariosUseCase(UsuarioRepository usuarios, CodificadorSenha codificador,
                                    HorarioTrabalhoRepository horarios, RegrasJornada regras,
                                    GerenciarCicloBancoUseCase ciclos, ApplicationEventPublisher eventos, Clock clock) {
        this.usuarios = usuarios;
        this.codificador = codificador;
        this.horarios = horarios;
        this.regras = regras;
        this.ciclos = ciclos;
        this.eventos = eventos;
        this.clock = clock;
    }

    /** Todos os usuários, ativos primeiro, por nome. */
    @Transactional(readOnly = true)
    public List<Usuario> listar() {
        return usuarios.listar().stream()
                .sorted(Comparator.comparing(Usuario::ativo).reversed()
                        .thenComparing(u -> nomeOuLogin(u).toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** Usuários ativos com dados de ponto (ADMIN/USER), por nome: os que a coordenação pode consultar. */
    @Transactional(readOnly = true)
    public List<Usuario> titulares() {
        return listar().stream().filter(u -> u.ativo() && u.isTitular()).toList();
    }

    @Transactional(readOnly = true)
    public Usuario buscar(UUID id) {
        return usuarios.buscarPorId(id).orElseThrow(GerenciarUsuariosUseCase::naoEncontrado);
    }

    @Transactional
    public Usuario criar(String login, String nome, Perfil perfil, String senhaProvisoria, String pasta,
                         String criadoPor) {
        String normalizado = login == null ? "" : login.strip().toLowerCase(Locale.ROOT);
        if (!LOGIN.matcher(normalizado).matches()) {
            throw new RegraNegocioException("LOGIN_INVALIDO",
                    "O login deve ter de 3 a 60 caracteres: letras minúsculas, números, ponto, hífen ou sublinhado.");
        }
        if (usuarios.buscarPorLogin(normalizado).isPresent()) {
            throw new ConflitoException("LOGIN_EM_USO", "Já existe um usuário com o login \"%s\".".formatted(normalizado));
        }
        validarSenha(senhaProvisoria);
        Usuario novo = Usuario.novo(normalizado, validarNome(nome), "-", Set.of(exigirPerfil(perfil)))
                .comSenhaProvisoria(codificador.codificar(senhaProvisoria))
                .comPasta(validarPasta(pasta, null));
        usuarios.salvar(novo);
        prepararTitular(novo, criadoPor);
        eventos.publishEvent(new UsuarioAlteradoEvento(novo.id(), "Usuário %s cadastrado".formatted(novo.login())));
        return novo;
    }

    /**
     * Altera nome, perfil e situação. O administrador não pode tirar o próprio acesso de administrador nem se
     * desativar, e sempre sobra pelo menos um administrador ativo.
     */
    @Transactional
    public Usuario atualizar(UUID id, String nome, Perfil perfil, boolean ativo, UUID quemAltera, String login) {
        Usuario atual = buscar(id);
        Perfil novoPerfil = exigirPerfil(perfil);
        if (id.equals(quemAltera) && (!ativo || novoPerfil != Perfil.ROLE_ADMIN)) {
            throw new RegraNegocioException("ALTERAR_PROPRIO_ACESSO",
                    "Você não pode desativar o próprio usuário nem tirar seu perfil de administrador.");
        }
        boolean deixaDeSerAdminAtivo = atual.isAdmin() && atual.ativo() && (!ativo || novoPerfil != Perfil.ROLE_ADMIN);
        if (deixaDeSerAdminAtivo && administradoresAtivos() <= 1) {
            throw new RegraNegocioException("ULTIMO_ADMINISTRADOR", "É preciso manter pelo menos um administrador ativo.");
        }
        Usuario alterado = atual.comDados(validarNome(nome), Set.of(novoPerfil), ativo);
        usuarios.salvar(alterado);
        prepararTitular(alterado, login);
        eventos.publishEvent(new UsuarioAlteradoEvento(id, "Usuário %s atualizado".formatted(alterado.login())));
        return alterado;
    }

    /** Senha provisória definida pelo administrador (a pessoa troca no próximo acesso). */
    @Transactional
    public Usuario redefinirSenha(UUID id, String senhaProvisoria) {
        Usuario atual = buscar(id);
        validarSenha(senhaProvisoria);
        Usuario alterado = atual.comSenhaProvisoria(codificador.codificar(senhaProvisoria));
        usuarios.salvar(alterado);
        return alterado;
    }

    /**
     * Pasta monitorada com os comprovantes PDF do usuário (local, de rede ou sincronizada). Vazia = sem
     * monitoramento (os PDFs podem ser enviados pela tela).
     *
     * @param quem quem está alterando: o próprio usuário ou um administrador
     */
    @Transactional
    public Usuario definirPasta(UUID id, String pasta, Usuario quem) {
        if (!quem.id().equals(id) && !quem.isAdmin()) {
            throw new AcessoNegadoException("ACESSO_NEGADO", "Só o próprio usuário ou um administrador altera a pasta.");
        }
        Usuario atual = buscar(id);
        if (!atual.isTitular()) {
            throw new RegraNegocioException("USUARIO_SEM_PONTO",
                    "Este usuário é da coordenação (só consulta): não tem comprovantes próprios.");
        }
        Usuario alterado = atual.comPasta(validarPasta(pasta, id));
        usuarios.salvar(alterado);
        eventos.publishEvent(new UsuarioAlteradoEvento(id, alterado.pastaComprovantes() == null
                ? "Monitoramento de pasta desligado"
                : "Pasta de comprovantes: %s".formatted(alterado.pastaComprovantes())));
        return alterado;
    }

    /** Na subida: todo titular ativo tem horário gravado e ciclo do banco aberto. */
    @Transactional
    public void prepararTitulares() {
        for (Usuario u : usuarios.listar()) {
            prepararTitular(u, "sistema");
        }
    }

    /** Titular sem horário gravado ganha o horário padrão (vale para todo o histórico) e o ciclo do banco. */
    private void prepararTitular(Usuario usuario, String quem) {
        if (!usuario.isTitular() || !usuario.ativo()) {
            return;
        }
        if (horarios.listarPorUsuario(usuario.id()).isEmpty()) {
            horarios.salvar(regras.padraoParaGravar(usuario.id(), quem, clock.instant()));
            regras.invalidar(usuario.id());
        }
        ciclos.garantirCicloAberto(usuario.id());
    }

    private long administradoresAtivos() {
        return usuarios.listar().stream().filter(u -> u.ativo() && u.isAdmin()).count();
    }

    private String validarPasta(String pasta, UUID dono) {
        if (pasta == null || pasta.isBlank()) {
            return null;
        }
        String limpa = pasta.strip();
        if (limpa.length() > TAMANHO_MAXIMO_PASTA) {
            throw new RegraNegocioException("PASTA_INVALIDA",
                    "O caminho da pasta pode ter no máximo %d caracteres.".formatted(TAMANHO_MAXIMO_PASTA));
        }
        if (limpa.chars().anyMatch(c -> c < 32) || limpa.contains("\"")) {
            throw new RegraNegocioException("PASTA_INVALIDA", "O caminho da pasta tem caracteres inválidos.");
        }
        String chave = chavePasta(limpa);
        usuarios.listar().stream()
                .filter(u -> !u.id().equals(dono) && u.pastaComprovantes() != null)
                .filter(u -> chavePasta(u.pastaComprovantes()).equals(chave))
                .findFirst()
                .ifPresent(outro -> {
                    throw new ConflitoException("PASTA_EM_USO", "Essa pasta já é a de %s: cada pessoa precisa da sua."
                            .formatted(nomeOuLogin(outro)));
                });
        return limpa;
    }

    /** Mesma pasta escrita de outro jeito (barras, maiúsculas, barra no fim). */
    static String chavePasta(String pasta) {
        String p = pasta.strip().replace('\\', '/').toLowerCase(Locale.ROOT);
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String validarNome(String nome) {
        if (nome == null || nome.isBlank()) {
            throw new RegraNegocioException("NOME_OBRIGATORIO", "Informe o nome.");
        }
        String limpo = nome.strip();
        if (limpo.length() > 120) {
            throw new RegraNegocioException("NOME_LONGO", "O nome pode ter no máximo 120 caracteres.");
        }
        return limpo;
    }

    private static Perfil exigirPerfil(Perfil perfil) {
        if (perfil == null) {
            throw new RegraNegocioException("PERFIL_OBRIGATORIO", "Escolha o perfil do usuário.");
        }
        return perfil;
    }

    static void validarSenha(String senha) {
        if (senha == null || senha.length() < AutenticarUsuarioUseCase.TAMANHO_MINIMO_SENHA) {
            throw new RegraNegocioException("SENHA_FRACA", "A senha precisa ter pelo menos %d caracteres."
                    .formatted(AutenticarUsuarioUseCase.TAMANHO_MINIMO_SENHA));
        }
        if (senha.length() > 72) { // limite do BCrypt
            throw new RegraNegocioException("SENHA_LONGA", "A senha pode ter no máximo 72 caracteres.");
        }
    }

    private static String nomeOuLogin(Usuario u) {
        return u.nome() == null || u.nome().isBlank() ? u.login() : u.nome();
    }

    private static RecursoNaoEncontradoException naoEncontrado() {
        return new RecursoNaoEncontradoException("USUARIO_NAO_ENCONTRADO", "Usuário não encontrado.");
    }
}
