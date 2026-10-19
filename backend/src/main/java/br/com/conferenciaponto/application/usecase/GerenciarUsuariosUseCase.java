package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.UsuarioAlteradoEvento;
import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.acesso.PermissoesDaAdministracao;
import br.com.conferenciaponto.domain.acesso.PermissoesDoPonto;
import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.CodificadorSenha;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Cadastro de usuários (quem tem "Cadastrar usuários") e pasta de comprovantes (o próprio usuário ou quem configura
 * o ponto da equipe). Os perfis e os ajustes de cada pessoa ficam em {@link GerenciarAcessosUseCase}.
 *
 * <p>Usuários não são excluídos (os dados de ponto ficam): são desativados. O acesso é criado com uma senha
 * provisória, que a pessoa troca no primeiro login. Quem tem o próprio ponto ganha o horário padrão e o ciclo do
 * banco de horas. Desativar alguém ou trocar a senha dele só pode quem tem pelo menos os mesmos acessos que ele
 * (senão, quem só cadastra usuários conseguiria entrar como o administrador).
 */
@Service
public class GerenciarUsuariosUseCase {

    private static final Pattern LOGIN = Pattern.compile("[a-z0-9][a-z0-9._-]{2,59}");
    static final int TAMANHO_MAXIMO_PASTA = 500;

    private final UsuarioRepository usuarios;
    private final CodificadorSenha codificador;
    private final PreparacaoDoTitular preparacao;
    private final GerenciarAcessosUseCase acessos;
    private final ApplicationEventPublisher eventos;

    public GerenciarUsuariosUseCase(UsuarioRepository usuarios, CodificadorSenha codificador,
                                    PreparacaoDoTitular preparacao, GerenciarAcessosUseCase acessos,
                                    ApplicationEventPublisher eventos) {
        this.usuarios = usuarios;
        this.codificador = codificador;
        this.preparacao = preparacao;
        this.acessos = acessos;
        this.eventos = eventos;
    }

    /** Todos os usuários, ativos primeiro, por nome. */
    @Transactional(readOnly = true)
    public List<Usuario> listar() {
        return usuarios.listar().stream()
                .sorted(Comparator.comparing(Usuario::ativo).reversed()
                        .thenComparing(u -> nomeOuLogin(u).toLowerCase(Locale.ROOT)))
                .toList();
    }

    /** Usuários ativos com o próprio ponto, por nome: os que quem consulta a equipe pode ver. */
    @Transactional(readOnly = true)
    public List<Usuario> titulares() {
        return listar().stream().filter(u -> u.ativo() && u.isTitular()).toList();
    }

    @Transactional(readOnly = true)
    public Usuario buscar(UUID id) {
        return usuarios.buscarPorId(id).orElseThrow(GerenciarUsuariosUseCase::naoEncontrado);
    }

    /**
     * @param perfis os perfis da pessoa (só quem cuida dos acessos escolhe; vazio = sem acesso a nada, até alguém
     *               dar um perfil)
     */
    @Transactional
    public Usuario criar(String login, String nome, Collection<UUID> perfis, String senhaProvisoria, String pasta,
                         Usuario quem) {
        String normalizado = login == null ? "" : login.strip().toLowerCase(Locale.ROOT);
        if (!LOGIN.matcher(normalizado).matches()) {
            throw new RegraNegocioException("LOGIN_INVALIDO",
                    "O login deve ter de 3 a 60 caracteres: letras minúsculas, números, ponto, hífen ou sublinhado.");
        }
        if (usuarios.buscarPorLogin(normalizado).isPresent()) {
            throw new ConflitoException("LOGIN_EM_USO", "Já existe um usuário com o login \"%s\".".formatted(normalizado));
        }
        boolean comPerfis = perfis != null && !perfis.isEmpty();
        if (comPerfis && !quem.pode(PermissoesDaAdministracao.ACESSOS)) {
            throw new AcessoNegadoException("ACESSO_NEGADO", "Só quem cuida dos acessos escolhe os perfis. Cadastre "
                    + "a pessoa sem perfil e peça a quem cuida dos acessos para dar o perfil.");
        }
        validarSenha(senhaProvisoria);
        Usuario novo = Usuario.novo(normalizado, validarNome(nome), "-", Acesso.NENHUM)
                .comSenhaProvisoria(codificador.codificar(senhaProvisoria))
                .comPasta(validarPasta(pasta, null));
        usuarios.salvar(novo);
        acessos.darPerfisAoNovo(quem, novo, comPerfis ? perfis : List.of());
        Usuario criado = buscar(novo.id());
        preparacao.preparar(criado, quem.login());
        eventos.publishEvent(new UsuarioAlteradoEvento(criado.id(), "Usuário %s cadastrado".formatted(criado.login())));
        return criado;
    }

    /**
     * Altera o nome e a situação. Ninguém se desativa; desativar exige ter pelo menos os mesmos acessos da pessoa,
     * e sempre sobra alguém ativo que cuide dos acessos.
     */
    @Transactional
    public Usuario atualizar(UUID id, String nome, boolean ativo, Usuario quem) {
        Usuario atual = buscar(id);
        if (id.equals(quem.id()) && !ativo) {
            throw new RegraNegocioException("ALTERAR_PROPRIO_ACESSO", "Você não pode desativar o próprio usuário.");
        }
        if (atual.ativo() != ativo) {
            conferirQuePodeMexerEm(quem, atual, ativo ? "reativar" : "desativar");
        }
        if (atual.ativo() && !ativo) {
            acessos.conferirDesativacao(quem, id);
        }
        Usuario alterado = atual.comDados(validarNome(nome), ativo);
        usuarios.salvar(alterado);
        preparacao.preparar(alterado, quem.login());
        eventos.publishEvent(new UsuarioAlteradoEvento(id, "Usuário %s atualizado".formatted(alterado.login())));
        return alterado;
    }

    /** Senha provisória (a pessoa troca no próximo acesso). */
    @Transactional
    public Usuario redefinirSenha(UUID id, String senhaProvisoria, Usuario quem) {
        Usuario atual = buscar(id);
        conferirQuePodeMexerEm(quem, atual, "trocar a senha de");
        validarSenha(senhaProvisoria);
        Usuario alterado = atual.comSenhaProvisoria(codificador.codificar(senhaProvisoria));
        usuarios.salvar(alterado);
        return alterado;
    }

    /**
     * Pasta monitorada com os comprovantes PDF do usuário (local, de rede ou sincronizada). Vazia = sem
     * monitoramento (os PDFs podem ser enviados pela tela).
     *
     * @param quem quem está alterando: o próprio usuário ou quem configura o ponto da equipe
     */
    @Transactional
    public Usuario definirPasta(UUID id, String pasta, Usuario quem) {
        if (!quem.id().equals(id) && !quem.pode(PermissoesDoPonto.CONFIGURAR_EQUIPE)) {
            throw new AcessoNegadoException("ACESSO_NEGADO",
                    "Só a própria pessoa ou quem configura o ponto da equipe altera a pasta.");
        }
        Usuario atual = buscar(id);
        if (!atual.isTitular()) {
            throw new RegraNegocioException("USUARIO_SEM_PONTO",
                    "Esta pessoa não tem o próprio ponto aqui: não tem comprovantes próprios.");
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
            preparacao.preparar(u, "sistema");
        }
    }

    /** Desativar, reativar ou trocar a senha de alguém: só quem tem pelo menos os mesmos acessos que a pessoa. */
    private static void conferirQuePodeMexerEm(Usuario quem, Usuario alvo, String acao) {
        if (quem.id().equals(alvo.id()) || quem.acesso().permissoes().containsAll(alvo.acesso().permissoes())) {
            return;
        }
        throw new AcessoNegadoException("ACESSO_MAIOR_QUE_O_SEU", "%s tem acessos que você não tem: só quem tem pelo "
                .formatted(nomeOuLogin(alvo)) + "menos os mesmos acessos pode " + acao + " " + primeiroNome(alvo) + ".");
    }

    private static String primeiroNome(Usuario u) {
        return nomeOuLogin(u).split("\\s+")[0];
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
        if (!caminhoCompleto(limpa)) {
            throw new RegraNegocioException("PASTA_INVALIDA", "Informe o caminho completo da pasta "
                    + "(ex.: C:\\Users\\fulano\\Downloads\\Ponto ou \\\\NOME-DO-PC\\Ponto).");
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

    /** Começa na raiz: {@code C:\...}, {@code \\servidor\...} ou {@code /...}. */
    static boolean caminhoCompleto(String pasta) {
        String p = pasta.strip().replace('\\', '/');
        return p.startsWith("/") || p.matches("^[A-Za-z]:/.*");
    }

    /**
     * Mesma pasta escrita de outro jeito: barras, maiúsculas, barra no fim, barras repetidas e os trechos
     * {@code .} e {@code ..} (que apontariam para a pasta de outra pessoa sem parecer).
     */
    static String chavePasta(String pasta) {
        String p = pasta.strip().replace('\\', '/');
        String raiz = p.startsWith("//") ? "//" : p.startsWith("/") ? "/" : "";
        java.util.ArrayDeque<String> partes = new java.util.ArrayDeque<>();
        for (String parte : p.split("/+")) {
            if (parte.isEmpty() || parte.equals(".")) {
                continue;
            }
            if (parte.equals("..")) {
                partes.pollLast();
            } else {
                partes.addLast(parte.strip());
            }
        }
        return (raiz + String.join("/", partes)).toLowerCase(Locale.ROOT);
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

    static void validarSenha(String senha) {
        if (senha == null || senha.length() < AutenticarUsuarioUseCase.TAMANHO_MINIMO_SENHA) {
            throw new RegraNegocioException("SENHA_FRACA", "A senha precisa ter pelo menos %d caracteres."
                    .formatted(AutenticarUsuarioUseCase.TAMANHO_MINIMO_SENHA));
        }
        if (senha.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) { // limite do BCrypt, em bytes
            throw new RegraNegocioException("SENHA_LONGA",
                    "A senha é longa demais: use até 72 caracteres (letras com acento contam em dobro).");
        }
    }

    private static String nomeOuLogin(Usuario u) {
        return u.nome() == null || u.nome().isBlank() ? u.login() : u.nome();
    }

    private static RecursoNaoEncontradoException naoEncontrado() {
        return new RecursoNaoEncontradoException("USUARIO_NAO_ENCONTRADO", "Usuário não encontrado.");
    }
}
