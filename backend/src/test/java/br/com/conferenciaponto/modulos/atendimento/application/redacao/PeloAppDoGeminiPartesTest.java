package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.domain.saida.ModeloDoTexto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** As partes do pacote para o Gem que não precisam do banco: o JSON colado, as instruções e os áudios juntos. */
class PeloAppDoGeminiPartesTest {

    @TempDir
    Path pasta;

    @Test
    void separaOJsonDoTextoDoGem() {
        assertThat(PeloAppDoGemini.jsonDaResposta("Claro! Aqui está:\n```json\n{\"a\":{\"b\":1}}\n```\nPosso ajudar?"))
                .contains("{\"a\":{\"b\":1}}");
        assertThat(PeloAppDoGemini.jsonDaResposta("```\n{\"a\":1}\n```")).contains("{\"a\":1}");
        assertThat(PeloAppDoGemini.jsonDaResposta("{\"a\":[1,2]}")).contains("{\"a\":[1,2]}");
        assertThat(PeloAppDoGemini.jsonDaResposta("Assunto: erro {no campo}\n\nTexto.")).as("texto com chaves é texto").isEmpty();
        assertThat(PeloAppDoGemini.jsonDaResposta("Não consegui abrir o vídeo.")).isEmpty();
    }

    @Test
    void asInstrucoesDoGemTrazemCadaParteDosChamadosComOTituloQueOSistemaLe() {
        String instrucoes = PeloAppDoGemini.INSTRUCOES.texto();
        for (ModeloDoTexto modelo : List.of(ModeloDoTexto.DEV_ERRO, ModeloDoTexto.DEV_ALTERACAO)) {
            for (ModeloDoTexto.Secao secao : modelo.secoes()) {
                assertThat(instrucoes).as(secao.chave()).contains("\n## " + secao.titulo() + "\n");
            }
        }
        assertThat(instrucoes).contains("[Imagem A3: ").contains("Assunto: Digisac:").contains("Tela/Funcionalidade:")
                .contains("Solicitação de Melhoria").contains(ModeloDoTexto.CAUSA_NAO_IDENTIFICADA)
                .contains("Código da tela:").doesNotContain("{{");
        assertThat(PeloAppDoGemini.nomeDoPdf("20261013174527")).isEqualTo("Atendimento_20261013174527.pdf");
        assertThat(PeloAppDoGemini.nomeDoPdf(null)).isEqualTo("Atendimento.pdf");
        assertThat(PeloAppDoGemini.nomeDoPdf("../x")).isEqualTo("Atendimento_x.pdf");
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
