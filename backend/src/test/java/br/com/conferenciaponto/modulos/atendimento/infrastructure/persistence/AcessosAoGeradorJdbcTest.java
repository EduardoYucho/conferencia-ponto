package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.domain.acesso.AcessoAoGerador;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** atendimento.acesso num PostgreSQL de verdade (temporário). */
class AcessosAoGeradorJdbcTest {

    private final AcessosAoGeradorJdbc acessos = new AcessosAoGeradorJdbc(PostgresDeTeste.jdbc());

    @Test
    void quemNuncaFoiLiberadoNaoTemLinha() {
        assertThat(acessos.buscar(PostgresDeTeste.novoUsuario(true))).isEmpty();
    }

    @Test
    void liberarERetirarSubstituemALinhaDaPessoa() {
        UUID usuario = PostgresDeTeste.novoUsuario(true);
        Instant liberadoEm = Instant.parse("2026-10-09T13:00:00Z");
        Instant retiradoEm = Instant.parse("2026-10-09T14:30:00Z");

        acessos.salvar(new AcessoAoGerador(usuario, true, "admin", liberadoEm));
        assertThat(acessos.buscar(usuario)).contains(new AcessoAoGerador(usuario, true, "admin", liberadoEm));

        acessos.salvar(new AcessoAoGerador(usuario, false, "outro.admin", retiradoEm));
        assertThat(acessos.buscar(usuario)).contains(new AcessoAoGerador(usuario, false, "outro.admin", retiradoEm));
        assertThat(acessos.listar()).filteredOn(a -> a.usuarioId().equals(usuario)).hasSize(1);
    }
}
