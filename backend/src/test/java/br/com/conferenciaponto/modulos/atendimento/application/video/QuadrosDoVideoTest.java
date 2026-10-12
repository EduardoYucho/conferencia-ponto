package br.com.conferenciaponto.modulos.atendimento.application.video;

import br.com.conferenciaponto.modulos.atendimento.domain.video.InstanteDoVideo;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.armazenamento.PastaEmDisco;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.video.ExtratorQuadrosFfmpeg;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.video.FfmpegDeMentira;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Os passos do vídeo e o quadro de cada um: o instante, o que fica perto do fim, os repetidos e os que não saem. */
class QuadrosDoVideoTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ATENDIMENTO = UUID.fromString("2a7e1c3d-0000-4000-8000-000000000001");
    private static final UUID VIDEO = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @TempDir
    Path raiz;

    private static JsonNode analise() throws IOException {
        return JSON.readTree("""
                {"descricao":"Gravação da tela","duracao":"00:40",
                 "passos":[
                   {"instante":"00:02","tela":{"nome":"Pedidos","codigo":"VEN010","caminhoMenu":"Vendas > Pedidos"},
                    "acoes":["clique em Novo"],"mensagens":[],"descricao":"Abre um pedido novo.","instanteDoQuadro":"00:03"},
                   {"instante":"00:10","tela":{"nome":"não identificado","codigo":"não identificado","caminhoMenu":"não identificado"},
                    "acoes":[],"mensagens":[],"descricao":"Digita o desconto.","instanteDoQuadro":"não identificado"},
                   {"instante":"00:12","tela":{"nome":"Pedidos","codigo":"VEN010","caminhoMenu":""},
                    "acoes":[],"mensagens":[{"texto":"Erro ao gravar","tipo":"erro"}],"descricao":"Mostra o erro.","instanteDoQuadro":"00:12"},
                   {"instante":"00:12","tela":{"nome":"Pedidos","codigo":"","caminhoMenu":""},
                    "acoes":[],"mensagens":[],"descricao":"Fecha a mensagem.","instanteDoQuadro":"00:12"},
                   {"instante":"00:39","tela":{"nome":"Pedidos","codigo":"","caminhoMenu":""},
                    "acoes":[],"mensagens":[],"descricao":"Fim.","instanteDoQuadro":"00:45"},
                   {"instante":"talvez","tela":{"nome":"","codigo":"","caminhoMenu":""},
                    "acoes":[],"mensagens":[],"descricao":"","instanteDoQuadro":"logo depois"}],
                 "narracao":[],"textoLegivel":true}""");
    }

    private QuadrosDoVideo quadros(List<String> ffmpeg) {
        return new QuadrosDoVideo(new PastaEmDisco(raiz), new ExtratorQuadrosFfmpeg(ffmpeg, Duration.ofSeconds(30)));
    }

    private String video() throws IOException {
        Path arquivo = raiz.resolve(ATENDIMENTO.toString()).resolve("arquivos").resolve("video_001_tela.mp4");
        Files.createDirectories(arquivo.getParent());
        Files.write(arquivo, new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p'});
        return raiz.relativize(arquivo).toString().replace('\\', '/');
    }

    @Test
    void osPassosComOInstanteDoQuadroOuOComecoDoPassoELegenda() throws IOException {
        List<QuadrosDoVideo.Passo> passos = QuadrosDoVideo.passos(analise());

        assertThat(passos).extracting(QuadrosDoVideo.Passo::quadroMs)
                .containsExactly(3_000L, 10_000L, 12_000L, 12_000L, 39_500L, null);
        assertThat(passos.get(0).legenda()).isEqualTo("tela Pedidos (VEN010): abre um pedido novo");
        assertThat(passos.get(1).legenda()).as("sem tela, só o que acontece").isEqualTo("Digita o desconto");
        assertThat(passos.get(4).quadro()).as("depois do fim fica meio segundo antes").isEqualTo("00:39");
        assertThat(passos.get(5).legenda()).isNull();
    }

    @Test
    void tiraUmQuadroPorInstanteENaoTiraDeNovoOQueJaExiste() throws IOException {
        QuadrosDoVideo quadros = quadros(FfmpegDeMentira.comando());
        String video = video();

        QuadrosDoVideo.Preparo primeiro = quadros.preparar(ATENDIMENTO, VIDEO, video, analise());

        assertThat(primeiro.tirados()).isEqualTo(4);
        assertThat(primeiro.aviso()).isNull();
        List<QuadrosDoVideo.Quadro> lista = quadros.quadros(ATENDIMENTO, VIDEO, analise());
        assertThat(lista).extracting(QuadrosDoVideo.Quadro::instante).containsExactly("00:03", "00:10", "00:12", "00:39");
        assertThat(lista).allMatch(QuadrosDoVideo.Quadro::disponivel);
        assertThat(lista.get(0).caminho()).isEqualTo(ATENDIMENTO + "/arquivos/quadros/" + VIDEO + "/000003000.png");

        QuadrosDoVideo.Preparo segundo = quadros.preparar(ATENDIMENTO, VIDEO, video, analise());
        assertThat(segundo.tirados()).isZero();
        assertThat(segundo.jaExistiam()).isEqualTo(4);
    }

    @Test
    void semFfmpegOsPassosFicamSemQuadroComOAviso() throws IOException {
        QuadrosDoVideo quadros = quadros(List.of());

        QuadrosDoVideo.Preparo preparo = quadros.preparar(ATENDIMENTO, VIDEO, video(), analise());

        assertThat(preparo.semFfmpeg()).isTrue();
        assertThat(preparo.aviso()).contains("winget install Gyan.FFmpeg");
        assertThat(quadros.quadros(ATENDIMENTO, VIDEO, analise())).noneMatch(QuadrosDoVideo.Quadro::disponivel);
    }

    @Test
    void quadroQueOFfmpegNaoTiraFicaDeForaEOsOutrosSaem() throws IOException {
        QuadrosDoVideo quadros = quadros(FfmpegDeMentira.comando());
        JsonNode longo = JSON.readTree("""
                {"descricao":"","duracao":"25:00","narracao":[],"textoLegivel":true,"passos":[
                  {"instante":"00:05","tela":{"nome":"","codigo":"","caminhoMenu":""},"acoes":[],"mensagens":[],
                   "descricao":"Começo.","instanteDoQuadro":"00:05"},
                  {"instante":"20:00","tela":{"nome":"","codigo":"","caminhoMenu":""},"acoes":[],"mensagens":[],
                   "descricao":"Sem imagem aqui.","instanteDoQuadro":"20:00"}]}""");

        QuadrosDoVideo.Preparo preparo = quadros.preparar(ATENDIMENTO, VIDEO, video(), longo);

        assertThat(preparo.tirados()).isEqualTo(1);
        assertThat(preparo.falharam()).isEqualTo(1);
        assertThat(preparo.aviso()).contains("1 quadro do vídeo não pôde ser tirado");
    }

    @Test
    void videoQueJaSaiuDoServidorNaoTentaTirar() throws IOException {
        QuadrosDoVideo.Preparo preparo = quadros(FfmpegDeMentira.comando()).preparar(ATENDIMENTO, VIDEO, null, analise());

        assertThat(preparo.semVideo()).isTrue();
        assertThat(preparo.aviso()).contains("saiu do servidor");
    }

    @Test
    void instantesDoVideo() {
        assertThat(InstanteDoVideo.lerMs("02:15")).contains(135_000L);
        assertThat(InstanteDoVideo.lerMs("1:02:15")).contains(3_735_000L);
        assertThat(InstanteDoVideo.lerMs("75:10")).contains(4_510_000L);
        assertThat(InstanteDoVideo.lerMs("00:07.5")).contains(7_500L);
        assertThat(InstanteDoVideo.lerMs("00:61")).isEmpty();
        assertThat(InstanteDoVideo.lerMs("não identificado")).isEmpty();
        assertThat(InstanteDoVideo.texto(4_510_999)).isEqualTo("75:10");
    }
}
