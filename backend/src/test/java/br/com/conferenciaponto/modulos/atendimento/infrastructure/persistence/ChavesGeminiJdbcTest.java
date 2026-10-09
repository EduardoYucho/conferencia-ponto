package br.com.conferenciaponto.modulos.atendimento.infrastructure.persistence;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveCifrada;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChaveGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.SituacaoDaChave;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** atendimento.chave_gemini no PostgreSQL temporário. */
class ChavesGeminiJdbcTest {

    private final ChavesGeminiJdbc chaves = new ChavesGeminiJdbc(PostgresDeTeste.jdbc());
    private final Instant agora = Instant.now().truncatedTo(ChronoUnit.MICROS);

    private ChaveGemini chave(UUID usuario, byte primeiroByte, SituacaoDaChave situacao) {
        byte[] cifrada = {primeiroByte, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};
        byte[] vetor = new byte[12];
        vetor[0] = primeiroByte;
        return new ChaveGemini(usuario, new ChaveCifrada(cifrada, vetor, 1), "abcd", true, situacao, agora, agora);
    }

    @Test
    void guardaELeAChaveCifrada() {
        UUID usuario = PostgresDeTeste.novoUsuario(true);

        chaves.salvar(chave(usuario, (byte) 1, SituacaoDaChave.VALIDA));
        ChaveGemini lida = chaves.buscar(usuario).orElseThrow();

        assertThat(lida.cifrada().cifrada()).startsWith(new byte[] {1, 2, 3}).hasSize(17);
        assertThat(lida.cifrada().vetorInicial()).hasSize(12);
        assertThat(lida.cifrada().versaoChaveMestra()).isEqualTo(1);
        assertThat(lida.ultimosCaracteres()).isEqualTo("abcd");
        assertThat(lida.nivelPagoConfirmado()).isTrue();
        assertThat(lida.situacao()).isEqualTo(SituacaoDaChave.VALIDA);
        assertThat(lida.testadaEm()).isEqualTo(agora);
    }

    @Test
    void cadastrarDeNovoSubstituiAChave() {
        UUID usuario = PostgresDeTeste.novoUsuario(true);
        chaves.salvar(chave(usuario, (byte) 1, SituacaoDaChave.VALIDA));

        chaves.salvar(chave(usuario, (byte) 9, SituacaoDaChave.SEM_COTA));

        ChaveGemini lida = chaves.buscar(usuario).orElseThrow();
        assertThat(lida.cifrada().cifrada()[0]).isEqualTo((byte) 9);
        assertThat(lida.situacao()).isEqualTo(SituacaoDaChave.SEM_COTA);
    }

    @Test
    void atualizaASituacaoEApaga() {
        UUID usuario = PostgresDeTeste.novoUsuario(true);
        chaves.salvar(chave(usuario, (byte) 1, SituacaoDaChave.VALIDA));
        Instant depois = agora.plusSeconds(60);

        chaves.atualizarSituacao(usuario, SituacaoDaChave.RECUSADA, depois);
        assertThat(chaves.buscar(usuario).orElseThrow().situacao()).isEqualTo(SituacaoDaChave.RECUSADA);
        assertThat(chaves.buscar(usuario).orElseThrow().testadaEm()).isEqualTo(depois);

        chaves.apagar(usuario);
        assertThat(chaves.buscar(usuario)).isEmpty();
    }
}
