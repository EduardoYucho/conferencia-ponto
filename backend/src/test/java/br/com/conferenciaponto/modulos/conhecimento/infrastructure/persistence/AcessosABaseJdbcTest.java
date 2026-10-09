package br.com.conferenciaponto.modulos.conhecimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.conhecimento.domain.acesso.AcessoABase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** conhecimento.acesso num PostgreSQL de verdade (temporário). */
class AcessosABaseJdbcTest {

    private final AcessosABaseJdbc acessos = new AcessosABaseJdbc(PostgresDeTeste.jdbc());

    @Test
    void quemNuncaFoiLiberadoNaoTemLinha() {
        assertThat(acessos.buscar(PostgresDeTeste.novoUsuario(true))).isEmpty();
    }

    @Test
    void gravaOQueAPessoaPodeFazerEOSubstitui() {
        UUID usuario = PostgresDeTeste.novoUsuario(true);
        Instant em = Instant.parse("2026-10-09T13:00:00Z");

        acessos.salvar(new AcessoABase(usuario, false, true, "admin", em));
        assertThat(acessos.buscar(usuario)).contains(new AcessoABase(usuario, true, true, "admin", em));

        acessos.salvar(new AcessoABase(usuario, true, false, "admin", em.plusSeconds(60)));
        assertThat(acessos.buscar(usuario)).contains(new AcessoABase(usuario, true, false, "admin", em.plusSeconds(60)));
        assertThat(acessos.listar()).filteredOn(a -> a.usuarioId().equals(usuario)).hasSize(1);
    }
}
