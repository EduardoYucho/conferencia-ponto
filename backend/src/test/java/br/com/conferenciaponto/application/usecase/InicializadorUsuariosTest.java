package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.port.CodificadorSenha;
import br.com.conferenciaponto.infrastructure.security.InicializadorUsuarios;
import br.com.conferenciaponto.infrastructure.security.SegurancaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Os usuários da primeira subida: a senha gerada é provisória (quem a ler depois no log não entra com ela). */
class InicializadorUsuariosTest {

    private final UsuarioRepositoryEmMemoria usuarios = new UsuarioRepositoryEmMemoria();
    private final CodificadorSenha codificador = new CodificadorSenha() {
        @Override
        public String codificar(String senhaAberta) {
            return "hash:" + senhaAberta;
        }

        @Override
        public boolean confere(String senhaAberta, String hash) {
            return hash.equals(codificar(senhaAberta));
        }
    };

    private void subir(String senha) {
        var propriedades = new SegurancaProperties(null, List.of(
                new SegurancaProperties.UsuarioInicial("admin", "Administrador", senha, List.of(Perfil.ROLE_ADMIN))));
        new InicializadorUsuarios(propriedades, usuarios, codificador, new AcessosEmMemoria(),
                Clock.fixed(Instant.parse("2026-10-22T12:00:00Z"), ZoneOffset.UTC))
                .run(new DefaultApplicationArguments());
    }

    @Test
    @DisplayName("Sem senha configurada: gera uma senha provisória (troca obrigatória no primeiro acesso)")
    void senhaGeradaEhProvisoria() {
        subir(null);
        assertThat(usuarios.buscarPorLogin("admin")).get().satisfies(u -> assertThat(u.trocarSenha()).isTrue());
    }

    @Test
    @DisplayName("Com a senha configurada (variável de ambiente): vale como está, sem troca obrigatória")
    void senhaConfiguradaVale() {
        subir("uma-senha-de-teste");
        assertThat(usuarios.buscarPorLogin("admin")).get().satisfies(u -> {
            assertThat(u.trocarSenha()).isFalse();
            assertThat(codificador.confere("uma-senha-de-teste", u.senhaHash())).isTrue();
        });
    }
}
