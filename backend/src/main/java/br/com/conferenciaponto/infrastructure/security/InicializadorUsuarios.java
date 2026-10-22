package br.com.conferenciaponto.infrastructure.security;

import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.acesso.MudancaDeAcesso;
import br.com.conferenciaponto.domain.acesso.PerfilDeAcesso;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.AcessosRepository;
import br.com.conferenciaponto.domain.port.CodificadorSenha;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cria os usuários de {@code ponto.seguranca.usuarios-iniciais} que ainda não existem, com os perfis do sistema
 * da configuração (ROLE_ADMIN = Administrador, ROLE_USER = Usuário, ROLE_VIEWER = Coordenador).
 * Nunca altera usuários existentes. Sem senha configurada, gera uma aleatória e provisória (precisa ser trocada no
 * primeiro acesso) e a mostra só no console e no log geral ({@code ponto logs}): não vai para a tela "Logs" nem para
 * os logs por pessoa, que outras pessoas com a permissão de ver logs consultam.
 */
@Component
@Order(1)
public class InicializadorUsuarios implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InicializadorUsuarios.class);
    /** Só console e log geral (logback-spring.xml): a senha gerada não aparece na tela "Logs". */
    static final String LOG_DA_SENHA = "senha-inicial";
    private static final Logger logDaSenha = LoggerFactory.getLogger(LOG_DA_SENHA);
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final SegurancaProperties properties;
    private final UsuarioRepository usuarios;
    private final CodificadorSenha codificador;
    private final AcessosRepository acessos;
    private final Clock clock;

    public InicializadorUsuarios(SegurancaProperties properties, UsuarioRepository usuarios,
                                 CodificadorSenha codificador, AcessosRepository acessos, Clock clock) {
        this.properties = properties;
        this.usuarios = usuarios;
        this.codificador = codificador;
        this.acessos = acessos;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (SegurancaProperties.UsuarioInicial inicial : properties.usuariosIniciais()) {
            if (inicial.login() == null || inicial.login().isBlank()
                    || usuarios.buscarPorLogin(Usuario.normalizarLogin(inicial.login())).isPresent()) {
                continue;
            }
            boolean gerada = inicial.senha() == null || inicial.senha().isBlank();
            String senha = gerada ? gerarSenha() : inicial.senha();
            EnumSet<Perfil> perfis = inicial.perfis() == null || inicial.perfis().isEmpty()
                    ? EnumSet.of(Perfil.ROLE_VIEWER)
                    : EnumSet.copyOf(inicial.perfis());
            String nome = inicial.nome() == null || inicial.nome().isBlank() ? inicial.login() : inicial.nome();

            Usuario novo = Usuario.novo(inicial.login(), nome, codificador.codificar(senha),
                    Acesso.de(perfis.toArray(Perfil[]::new)));
            if (gerada) {
                novo = novo.comSenhaProvisoria(novo.senhaHash()); // quem a ler depois no log não entra com ela
            }
            usuarios.salvar(novo);
            darPerfis(novo, perfis);
            if (gerada) {
                log.warn("Usuário '{}' criado com perfis {} e senha provisória gerada (veja em \"ponto logs\"; "
                        + "ela precisa ser trocada no primeiro acesso)", inicial.login(), perfis);
                logDaSenha.warn("Senha provisória gerada para '{}': {}", inicial.login(), senha);
            } else {
                log.info("Usuário '{}' criado com perfis {}", inicial.login(), perfis);
            }
        }
    }

    private void darPerfis(Usuario novo, EnumSet<Perfil> perfis) {
        List<UUID> ids = perfis.stream().map(acessos::perfilDoSistema).flatMap(Optional::stream)
                .map(PerfilDeAcesso::id).toList();
        Instant agora = clock.instant();
        acessos.definirPerfis(novo.id(), ids, "sistema", agora);
        List<String> nomes = novo.acesso().perfis();
        acessos.registrar(new MudancaDeAcesso(agora, "sistema", novo.id(), null, "Usuário inicial " + novo.login()
                + " criado com " + (nomes.size() == 1 ? "o perfil " : "os perfis ") + String.join(", ", nomes) + "."));
    }

    private static String gerarSenha() {
        byte[] bytes = new byte[12];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
