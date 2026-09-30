package br.com.conferenciaponto.infrastructure.security;

import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.CodificadorSenha;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;

/**
 * Cria os usuários de {@code ponto.seguranca.usuarios-iniciais} que ainda não existem.
 * Nunca altera usuários existentes. Sem senha configurada, gera uma aleatória e a mostra
 * uma única vez no log (troque-a depois em PUT /api/v1/auth/senha).
 */
@Component
public class InicializadorUsuarios implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InicializadorUsuarios.class);
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private final SegurancaProperties properties;
    private final UsuarioRepository usuarios;
    private final CodificadorSenha codificador;

    public InicializadorUsuarios(SegurancaProperties properties, UsuarioRepository usuarios,
                                 CodificadorSenha codificador) {
        this.properties = properties;
        this.usuarios = usuarios;
        this.codificador = codificador;
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

            usuarios.salvar(Usuario.novo(inicial.login(), nome, codificador.codificar(senha), perfis));
            if (gerada) {
                log.warn("Usuário '{}' criado com perfis {}. Senha gerada: {}  (defina a senha por variável de ambiente "
                        + "ou troque-a após o primeiro login)", inicial.login(), perfis, senha);
            } else {
                log.info("Usuário '{}' criado com perfis {}", inicial.login(), perfis);
            }
        }
    }

    private static String gerarSenha() {
        byte[] bytes = new byte[12];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
