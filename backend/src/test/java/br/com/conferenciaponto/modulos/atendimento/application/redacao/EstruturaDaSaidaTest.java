package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.domain.saida.BlocoDoTexto;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TextoRedigido;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** O texto guardado no banco: o que volta igual, inclusive os arquivos que ficaram de fora. */
class EstruturaDaSaidaTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void osArquivosSemAnaliseFicamGuardadosComOTexto() {
        UUID video = UUID.randomUUID();
        TextoRedigido texto = new TextoRedigido(1, "Assunto", List.of(new BlocoDoTexto(BlocoDoTexto.Tipo.PARAGRAFO, "Texto.",
                List.of("M1"), List.of())), Map.of("cliente", "Loja"), "Pedidos", List.of("Escrito sem o conteúdo do vídeo"))
                .comSemAnalise(List.of(video));

        TextoRedigido lido = EstruturaDaSaida.ler(json, EstruturaDaSaida.escrever(json, texto));

        assertThat(lido.semAnalise()).containsExactly(video);
        assertThat(lido.avisos()).containsExactly("Escrito sem o conteúdo do vídeo");
        assertThat(lido.comAssunto("Outro", List.of()).semAnalise()).as("as cópias levam junto").containsExactly(video);
    }

    @Test
    void textoDaVersaoAnteriorNaoTemArquivosDeFora() {
        TextoRedigido texto = new TextoRedigido(1, "Assunto", List.of(), Map.of(), null, List.of());

        String guardado = EstruturaDaSaida.escrever(json, texto);

        assertThat(guardado).doesNotContain("semAnalise");
        assertThat(EstruturaDaSaida.ler(json, guardado).semAnalise()).isEmpty();
        assertThat(EstruturaDaSaida.ler(json, "{\"semAnalise\":[\"isto-nao-e-uuid\"]}").semAnalise()).isEmpty();
    }
}
