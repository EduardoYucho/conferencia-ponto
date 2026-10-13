package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** As partes do pedido ao app do Gemini que não precisam do banco: o JSON colado e os áudios juntos. */
class PeloAppDoGeminiPartesTest {

    @TempDir
    Path pasta;

    @Test
    void achaOJsonNoQueFoiColado() {
        assertThat(PeloAppDoGemini.jsonDaResposta("Claro! Aqui está:\n```json\n{\"a\":{\"b\":1}}\n```\nPosso ajudar?"))
                .isEqualTo("{\"a\":{\"b\":1}}");
        assertThat(PeloAppDoGemini.jsonDaResposta("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(PeloAppDoGemini.jsonDaResposta("o resultado: {\"a\":[1,2]} fim")).isEqualTo("{\"a\":[1,2]}");
        assertThatThrownBy(() -> PeloAppDoGemini.jsonDaResposta(" \n "))
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "RESPOSTA_VAZIA");
        assertThatThrownBy(() -> PeloAppDoGemini.jsonDaResposta("Não consegui abrir o vídeo."))
                .isInstanceOf(RegraNegocioException.class).hasFieldOrPropertyWithValue("codigo", "RESPOSTA_FORA_DO_FORMATO");
    }

    private Path wav(String nome, int segundos) throws IOException {
        Path arquivo = pasta.resolve(nome);
        byte[] dados = new byte[segundos * JuncaoDeAudios.BYTES_POR_SEGUNDO];
        dados[0] = 7; // para conferir que os dados vão na ordem
        byte[] cabecalho = JuncaoDeAudios.cabecalho(dados.length);
        byte[] tudo = new byte[cabecalho.length + dados.length];
        System.arraycopy(cabecalho, 0, tudo, 0, cabecalho.length);
        System.arraycopy(dados, 0, tudo, cabecalho.length, dados.length);
        Files.write(arquivo, tudo);
        return arquivo;
    }

    @Test
    void juntaOsAudiosComUmSegundoEntreElesEDizOndeCadaUmEsta() throws IOException {
        Path destino = pasta.resolve("junto/Audios.wav");

        List<JuncaoDeAudios.Trecho> trechos = JuncaoDeAudios.juntar(List.of(wav("a.wav", 3), wav("b.wav", 2), wav("c.wav", 1)),
                destino);

        assertThat(trechos).containsExactly(new JuncaoDeAudios.Trecho(0, 3000), new JuncaoDeAudios.Trecho(4000, 6000),
                new JuncaoDeAudios.Trecho(7000, 8000));
        long[] dados = JuncaoDeAudios.dados(destino);
        assertThat(dados[0]).isEqualTo(44);
        assertThat(dados[1]).as("6 s de áudio e 2 s de silêncio").isEqualTo(8L * JuncaoDeAudios.BYTES_POR_SEGUNDO);
        byte[] lido = Files.readAllBytes(destino);
        assertThat(lido[44]).isEqualTo((byte) 7);
        assertThat(lido[44 + 4 * JuncaoDeAudios.BYTES_POR_SEGUNDO]).as("o segundo áudio começa em 4 s").isEqualTo((byte) 7);
    }

    @Test
    void arquivoQueNaoEWavNaoEntra() throws IOException {
        Path falso = pasta.resolve("x.wav");
        Files.writeString(falso, "não sou wav");

        assertThatThrownBy(() -> JuncaoDeAudios.juntar(List.of(falso), pasta.resolve("y.wav"))).isInstanceOf(IOException.class);
    }

    @Test
    void duracaoLegivel() {
        assertThat(PeloAppDoGemini.duracao(42_000)).isEqualTo("42 s");
        assertThat(PeloAppDoGemini.duracao(754_000)).isEqualTo("12 min 34 s");
    }
}
