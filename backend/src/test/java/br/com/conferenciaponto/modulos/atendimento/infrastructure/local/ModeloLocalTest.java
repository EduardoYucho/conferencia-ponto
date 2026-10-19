package br.com.conferenciaponto.modulos.atendimento.infrastructure.local;

import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.PedidoAoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.RespostaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.video.ConversorDeMidia;
import br.com.conferenciaponto.modulos.atendimento.domain.video.ExtratorQuadros;
import br.com.conferenciaponto.modulos.atendimento.domain.video.FalhaNoQuadro;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.IaLocalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A IA local contra o Ollama de mentira: o pedido (sem raciocínio, com o esquema no "format"), o que cada mídia vira
 * (imagem, áudio transcrito, quadros do vídeo, texto e páginas do PDF), a espera pela placa de vídeo e cada falha.
 */
class ModeloLocalTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ESQUEMA = "{\"type\":\"object\",\"properties\":{\"descricao\":{\"type\":\"string\"}},\"required\":[\"descricao\"]}";
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @TempDir
    Path pasta;

    private final OllamaDeMentira ollama = new OllamaDeMentira();
    private final List<Path> transcritos = new CopyOnWriteArrayList<>();
    private final List<Long> instantes = new CopyOnWriteArrayList<>();
    private volatile List<Transcritor.Trecho> fala = List.of(new Transcritor.Trecho(0, "Oi, o sistema travou."),
            new Transcritor.Trecho(65_500, "Já reiniciei e continua."));
    private volatile Optional<MemoriaDaPlaca.Uso> usoDaPlaca = Optional.empty();
    private volatile int utilizacaoDaPlaca;
    private volatile boolean semAudio;

    @AfterEach
    void parar() {
        ollama.close();
    }

    private IaLocalProperties propriedades(URI url, boolean esperarPlaca) {
        return new IaLocalProperties(url, "gemma4:12b", "embeddinggemma", 24576, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofMinutes(2), new IaLocalProperties.Whisper("", "", "pt", Duration.ofMinutes(1)),
                new IaLocalProperties.Video(4), new IaLocalProperties.Pdf(2, 1000),
                new IaLocalProperties.Placa(esperarPlaca, DataSize.ofGigabytes(8), 60, Duration.ofMinutes(2), "nvidia-smi"));
    }

    private ModeloLocal modelo() {
        return modelo(ollama.base(), true);
    }

    private ModeloLocal modelo(URI url, boolean esperarPlaca) {
        IaLocalProperties p = propriedades(url, esperarPlaca);
        Transcritor transcritor = new Transcritor() {
            @Override
            public boolean disponivel() {
                return true;
            }

            @Override
            public List<Trecho> transcrever(Path wav) {
                transcritos.add(wav);
                return fala;
            }
        };
        MemoriaDaPlaca placa = new MemoriaDaPlaca(List.of("nvidia-smi")) {
            @Override
            Optional<Uso> agora() {
                return usoDaPlaca;
            }

            @Override
            java.util.OptionalInt menorUtilizacao(int leituras, long intervaloMs) {
                return usoDaPlaca.isEmpty() ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(utilizacaoDaPlaca);
            }
        };
        ExtratorQuadros quadros = new ExtratorQuadros() {
            @Override
            public Situacao situacao() {
                return new Situacao(true, "de mentira", "ffmpeg");
            }

            @Override
            public void extrair(Path video, long instanteMs, Path destino) {
                instantes.add(instanteMs);
                try {
                    Files.write(destino, PNG);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        ConversorDeMidia conversor = new ConversorDeMidia() {
            @Override
            public boolean disponivel() {
                return true;
            }

            @Override
            public void paraWav(Path origem, Path destino) {
                if (semAudio) {
                    throw new FalhaNoQuadro(FalhaNoQuadro.FFMPEG_FALHOU, "sem áudio");
                }
                try {
                    Files.write(destino, new byte[]{1, 2, 3});
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public boolean paraMp3(Path wav, Path destino) {
                return false;
            }

            @Override
            public Optional<Long> duracaoMs(Path midia) {
                return Optional.of(40_000L);
            }
        };
        return new ModeloLocal(p, JSON, new ClienteOllama(url, JSON, p.tempoConexao(), p.tempoLimite()), transcritor, placa,
                quadros, conversor);
    }

    private static PedidoAoModelo pedido(PedidoAoModelo.Midia midia, String esquema) {
        return new PedidoAoModelo("gemma4:12b", "regras comuns", "analise o arquivo", esquema, midia, false, null, null, 32768);
    }

    private Path arquivo(String nome, byte[] conteudo) throws IOException {
        Path arquivo = pasta.resolve(nome);
        Files.write(arquivo, conteudo);
        return arquivo;
    }

    private JsonNode ultimaConversa() throws IOException {
        List<OllamaDeMentira.Pedido> conversas = ollama.pedidos("/api/chat");
        return JSON.readTree(conversas.get(conversas.size() - 1).corpo());
    }

    // ------------------------------------------------------------------------------------------------ pedido

    @Test
    void imagemVaiEmBase64ComOEsquemaNoFormatSemRaciocinioEComOModeloDaConfiguracao() throws IOException {
        Path png = arquivo("print.png", PNG);

        RespostaDoModelo resposta = modelo().gerar("ia-local", pedido(PedidoAoModelo.Midia.doDisco("image/png", png), ESQUEMA));

        assertThat(resposta.texto()).isEqualTo("{\"descricao\":\"Tela de pedidos\"}");
        assertThat(resposta.tokensEntrada()).isEqualTo(1200);
        assertThat(resposta.tokensSaida()).isEqualTo(80);
        JsonNode corpo = ultimaConversa();
        assertThat(corpo.path("model").asText()).isEqualTo("gemma4:12b");
        assertThat(corpo.path("stream").asBoolean(true)).isFalse();
        assertThat(corpo.path("think").asBoolean(true)).isFalse();
        assertThat(corpo.path("keep_alive").asLong()).isEqualTo(120);
        assertThat(corpo.path("format").path("required").get(0).asText()).isEqualTo("descricao");
        assertThat(corpo.path("options").path("num_ctx").asInt()).isEqualTo(24576);
        assertThat(corpo.path("options").path("num_predict").asInt()).isEqualTo(4096);
        assertThat(corpo.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(corpo.path("messages").get(0).path("content").asText()).isEqualTo("regras comuns");
        JsonNode usuario = corpo.path("messages").get(1);
        assertThat(usuario.path("content").asText()).isEqualTo("analise o arquivo");
        assertThat(usuario.path("images").get(0).asText()).isEqualTo(Base64.getEncoder().encodeToString(PNG));
    }

    @Test
    void imagemNoProprioPedidoTambemVai() throws IOException {
        modelo().gerar("ia-local", pedido(PedidoAoModelo.Midia.noPedido("image/png", PNG), ESQUEMA));

        assertThat(ultimaConversa().path("messages").get(1).path("images")).hasSize(1);
    }

    @Test
    void webpViraPngPeloFfmpegAntesDeIr() throws IOException {
        Path webp = arquivo("foto.webp", new byte[]{'R', 'I', 'F', 'F'});

        modelo().gerar("ia-local", pedido(PedidoAoModelo.Midia.doDisco("image/webp", webp), ESQUEMA));

        assertThat(instantes).containsExactly(0L);
        assertThat(ultimaConversa().path("messages").get(1).path("images").get(0).asText())
                .isEqualTo(Base64.getEncoder().encodeToString(PNG));
    }

    @Test
    void semEsquemaARespostaETextoLivre() throws IOException {
        ollama.responder(200, OllamaDeMentira.conversa("Assunto: Digisac: Loja : Erro\n\nTexto corrido.", "stop"));

        RespostaDoModelo resposta = modelo().gerar("ia-local", pedido(null, PedidoAoModelo.SEM_ESQUEMA));

        assertThat(resposta.texto()).startsWith("Assunto: Digisac");
        JsonNode corpo = ultimaConversa();
        assertThat(corpo.has("format")).isFalse();
        assertThat(corpo.path("options").path("temperature").asDouble()).isEqualTo(0.3);
        assertThat(corpo.path("messages").get(1).has("images")).isFalse();
    }

    @Test
    void audioVaiTranscritoComOInicioDeCadaTrecho() throws IOException {
        Path ogg = arquivo("audio.oga", new byte[]{'O', 'g', 'g', 'S'});

        modelo().gerar("ia-local", pedido(PedidoAoModelo.Midia.doDisco("audio/ogg", ogg), ESQUEMA));

        assertThat(transcritos).hasSize(1);
        JsonNode usuario = ultimaConversa().path("messages").get(1);
        assertThat(usuario.has("images")).isFalse();
        assertThat(usuario.path("content").asText()).contains("já foi transcrito").contains("[00:00] Oi, o sistema travou.")
                .contains("[01:05] Já reiniciei e continua.").endsWith("analise o arquivo");
    }

    @Test
    void videoVaiEmQuadrosEspalhadosComOsInstantesEAFalaTranscrita() throws IOException {
        Path mp4 = arquivo("gravacao.mp4", new byte[]{0, 0, 0, 24});

        modelo().gerar("ia-local", pedido(PedidoAoModelo.Midia.doDisco("video/mp4", mp4), ESQUEMA));

        assertThat(instantes).containsExactly(5_000L, 15_000L, 25_000L, 35_000L); // 4 quadros em 40 s
        JsonNode usuario = ultimaConversa().path("messages").get(1);
        assertThat(usuario.path("images")).hasSize(4);
        assertThat(usuario.path("content").asText()).contains("O vídeo tem 00:40")
                .contains("imagem 1 = 00:05; imagem 2 = 00:15; imagem 3 = 00:25; imagem 4 = 00:35")
                .contains("[00:00] Oi, o sistema travou.");
    }

    @Test
    void videoSemAudioVaiSoComOsQuadros() throws IOException {
        semAudio = true;
        Path mp4 = arquivo("gravacao.mp4", new byte[]{0, 0, 0, 24});

        modelo().gerar("ia-local", pedido(PedidoAoModelo.Midia.doDisco("video/mp4", mp4), ESQUEMA));

        assertThat(ultimaConversa().path("messages").get(1).path("content").asText()).contains("(o vídeo não tem áudio)");
        assertThat(transcritos).isEmpty();
    }

    @Test
    void pdfVaiComOTextoEAsPrimeirasPaginasComoImagem() throws IOException {
        Path pdf = pasta.resolve("relatorio.pdf");
        try (PDDocument documento = new PDDocument()) {
            for (int i = 1; i <= 3; i++) {
                PDPage pagina = new PDPage();
                documento.addPage(pagina);
                try (PDPageContentStream escrita = new PDPageContentStream(documento, pagina)) {
                    escrita.beginText();
                    escrita.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    escrita.newLineAtOffset(72, 700);
                    escrita.showText("Pagina " + i + " do relatorio de vendas");
                    escrita.endText();
                }
            }
            documento.save(pdf.toFile());
        }

        modelo().gerar("ia-local", pedido(PedidoAoModelo.Midia.doDisco("application/pdf", pdf), ESQUEMA));

        JsonNode usuario = ultimaConversa().path("messages").get(1);
        assertThat(usuario.path("images")).as("as 2 primeiras páginas, pela configuração").hasSize(2);
        assertThat(usuario.path("content").asText()).contains("O documento tem 3 página(s)")
                .contains("Pagina 1 do relatorio de vendas").contains("Pagina 3 do relatorio de vendas");
    }

    @Test
    void tipoQueAIaLocalNaoLeERecusado() throws IOException {
        Path zip = arquivo("arquivos.zip", new byte[]{'P', 'K'});

        assertThatThrownBy(() -> modelo().gerar("ia-local", pedido(PedidoAoModelo.Midia.doDisco("application/zip", zip), ESQUEMA)))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.tipo()).isEqualTo(FalhaDoModelo.Tipo.PEDIDO_RECUSADO));
    }

    // ------------------------------------------------------------------------------------------------ placa

    private static final long GB = 1024L * 1024 * 1024;

    @Test
    void comOModeloForaDaPlacaValeAMemoriaLivreENaoPrecisaTirarNada() {
        usoDaPlaca = Optional.of(new MemoriaDaPlaca.Uso(12 * GB, 2 * GB + GB / 2, 5)); // 9,5 GB livres
        modelo().gerar("ia-local", pedido(null, ESQUEMA));
        assertThat(ollama.pedidos("/api/chat")).hasSize(1);

        usoDaPlaca = Optional.of(new MemoriaDaPlaca.Uso(12 * GB, 10 * GB, 98)); // um jogo com 10 GB
        assertThatThrownBy(() -> modelo().gerar("ia-local", pedido(null, ESQUEMA)))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> {
                    assertThat(f.tipo()).isEqualTo(FalhaDoModelo.Tipo.PLACA_OCUPADA);
                    assertThat(f.esperar()).isEqualTo(Duration.ofMinutes(2));
                });
        assertThat(ollama.pedidos("/api/chat")).as("nem pediu").hasSize(1);
        assertThat(ollama.pedidos("/api/generate")).as("o modelo não estava na placa").isEmpty();
    }

    @Test
    void comOModeloNaPlacaAMemoriaNaoContaEValeOUsoComAIaParada() {
        // o caso real: o modelo ocupa quase toda a placa e o Ollama informa só 1,4 GB dele
        ollama.memoriaDoModelo(GB + GB / 2);
        usoDaPlaca = Optional.of(new MemoriaDaPlaca.Uso(12 * GB, 10 * GB, 4));
        utilizacaoDaPlaca = 4;
        modelo().gerar("ia-local", pedido(null, ESQUEMA));
        assertThat(ollama.pedidos("/api/chat")).as("a placa está parada: é o próprio modelo").hasSize(1);

        utilizacaoDaPlaca = 95; // um jogo abriu
        assertThatThrownBy(() -> modelo().gerar("ia-local", pedido(null, ESQUEMA)))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.tipo()).isEqualTo(FalhaDoModelo.Tipo.PLACA_OCUPADA));
        assertThat(ollama.pedidos("/api/chat")).hasSize(1);
        assertThat(ollama.pedidos("/api/generate")).singleElement()
                .satisfies(p -> assertThat(p.corpo()).contains("\"keep_alive\":0").contains("gemma4:12b"));
    }

    @Test
    void semSaberOUsoDaPlacaOuComAEsperaDesligadaPedeDoMesmoJeito() {
        usoDaPlaca = Optional.empty();
        modelo().gerar("ia-local", pedido(null, ESQUEMA));

        usoDaPlaca = Optional.of(new MemoriaDaPlaca.Uso(12 * GB, 11 * GB, 99));
        modelo(ollama.base(), false).gerar("ia-local", pedido(null, ESQUEMA));

        assertThat(ollama.pedidos("/api/chat")).hasSize(2);
    }

    @Test
    void aMemoriaLivreEOTotalMenosOUsado() {
        assertThat(new MemoriaDaPlaca.Uso(12_000, 11_000, 0).livre()).isEqualTo(1_000);
        assertThat(new MemoriaDaPlaca.Uso(12_000, 13_000, 0).livre()).isZero();
    }

    // ------------------------------------------------------------------------------------------------ áudio e vídeo

    private static final String ESQUEMA_DO_AUDIO = "{\"type\":\"object\",\"properties\":{\"falantes\":{\"type\":\"array\"},"
            + "\"trechos\":{\"type\":\"array\"}},\"required\":[\"falantes\",\"trechos\"]}";

    @Test
    void aAnaliseDoAudioEATranscricaoDoWhisperSemOModeloReescrever() throws IOException {
        Path mp3 = arquivo("ligacao.mp3", new byte[]{'I', 'D', '3'});

        RespostaDoModelo resposta = modelo().gerar("ia-local", pedido(PedidoAoModelo.Midia.doDisco("audio/mpeg", mp3), ESQUEMA_DO_AUDIO));

        assertThat(ollama.pedidos("/api/chat")).as("o modelo não é chamado").isEmpty();
        JsonNode lido = JSON.readTree(resposta.texto());
        assertThat(lido.path("duracao").asText()).isEqualTo("00:40");
        assertThat(lido.path("falantes").get(0).path("papel").asText()).isEqualTo("nao_identificado");
        assertThat(lido.path("trechos")).hasSize(2);
        assertThat(lido.path("trechos").get(1).path("inicio").asText()).isEqualTo("01:05");
        assertThat(lido.path("trechos").get(1).path("texto").asText()).isEqualTo("Já reiniciei e continua.");
        assertThat(lido.path("temTrechoInaudivel").asBoolean(true)).isFalse();
    }

    @Test
    void aNarracaoDoVideoVemDoWhisperNoLugarDaDoModelo() throws IOException {
        Path mp4 = arquivo("gravacao.mp4", new byte[]{0, 0, 0, 24});
        ollama.responder(200, OllamaDeMentira.conversa("{\"descricao\":\"Tela\",\"passos\":[],\"narracao\":"
                + "[{\"inicio\":\"00:00\",\"falante\":\"x\",\"texto\":\"inventado\"}]}", "stop"));

        RespostaDoModelo resposta = modelo().gerar("ia-local", pedido(PedidoAoModelo.Midia.doDisco("video/mp4", mp4),
                "{\"type\":\"object\",\"properties\":{\"narracao\":{\"type\":\"array\"}}}"));

        JsonNode narracao = JSON.readTree(resposta.texto()).path("narracao");
        assertThat(narracao).hasSize(2);
        assertThat(narracao.get(0).path("texto").asText()).isEqualTo("Oi, o sistema travou.");
        assertThat(ultimaConversa().path("messages").get(1).path("content").asText()).contains("deixe a narração vazia");
    }

    @Test
    void respostaCortadaFazAProximaVezDoMesmoPedidoTerMaisVariacao() throws IOException {
        ollama.responder(200, OllamaDeMentira.conversa("{\"descricao\":\"Tela Tela Tela", "length"));
        ModeloLocal modelo = modelo();
        assertThatThrownBy(() -> modelo.gerar("ia-local", pedido(null, ESQUEMA)))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.tipo()).isEqualTo(FalhaDoModelo.Tipo.RESPOSTA_INVALIDA));
        assertThat(ultimaConversa().path("options").path("repeat_penalty").asDouble()).isEqualTo(1.1);

        modelo.gerar("ia-local", pedido(null, ESQUEMA));

        JsonNode opcoes = ultimaConversa().path("options");
        assertThat(opcoes.path("temperature").asDouble()).isEqualTo(0.6);
        assertThat(opcoes.path("repeat_penalty").asDouble()).isEqualTo(1.3);
    }

    // ------------------------------------------------------------------------------------------------ falhas

    @Test
    void ollamaParadoViraIaLocalParada() throws IOException {
        int porta;
        try (ServerSocket livre = new ServerSocket(0)) {
            porta = livre.getLocalPort();
        }

        assertThatThrownBy(() -> modelo(URI.create("http://127.0.0.1:" + porta), true).gerar("ia-local", pedido(null, ESQUEMA)))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.tipo()).isEqualTo(FalhaDoModelo.Tipo.IA_LOCAL_PARADA));
    }

    @Test
    void modeloQueNaoEstaInstaladoViraModeloInexistente() {
        ollama.responder(404, "{\"error\":\"model 'gemma4:12b' not found\"}");

        assertThatThrownBy(() -> modelo().gerar("ia-local", pedido(null, ESQUEMA)))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.tipo()).isEqualTo(FalhaDoModelo.Tipo.MODELO_INEXISTENTE));
    }

    @Test
    void respostaVaziaOuCortadaEInvalida() {
        ollama.responder(200, OllamaDeMentira.conversa("", "stop"))
                .responder(200, OllamaDeMentira.conversa("{\"descricao\":\"Tela", "length"));

        assertThatThrownBy(() -> modelo().gerar("ia-local", pedido(null, ESQUEMA)))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.tipo()).isEqualTo(FalhaDoModelo.Tipo.RESPOSTA_INVALIDA));
        assertThatThrownBy(() -> modelo().gerar("ia-local", pedido(null, ESQUEMA)))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.getMessage()).contains("cortada"));
    }

    @Test
    void modeloQueNaoAceitaOThinkRecebeOPedidoDeNovoSemEle() throws IOException {
        ollama.responder(400, "{\"error\":\"\\\"gemma4:12b\\\" does not support thinking\"}");

        RespostaDoModelo resposta = modelo().gerar("ia-local", pedido(null, ESQUEMA));

        assertThat(resposta.texto()).contains("Tela de pedidos");
        List<OllamaDeMentira.Pedido> conversas = ollama.pedidos("/api/chat");
        assertThat(conversas).hasSize(2);
        assertThat(JSON.readTree(conversas.get(0).corpo()).has("think")).isTrue();
        assertThat(JSON.readTree(conversas.get(1).corpo()).has("think")).isFalse();
    }

    @Test
    void erroDoOllamaViraIndisponivel() {
        ollama.responder(500, "{\"error\":\"CUDA error: out of memory\"}");

        assertThatThrownBy(() -> modelo().gerar("ia-local", pedido(null, ESQUEMA)))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> {
                    assertThat(f.tipo()).isEqualTo(FalhaDoModelo.Tipo.INDISPONIVEL);
                    assertThat(f.getMessage()).contains("out of memory");
                });
    }

    // ------------------------------------------------------------------------------------------------ vetores

    @Test
    void vetorSaiNormalizadoECortadoNaDimensaoDaConfiguracao() throws IOException {
        ollama.responder(200, "{\"model\":\"embeddinggemma\",\"embeddings\":[[3.0,4.0,12.0]]}");

        float[] vetor = modelo().vetor("ia-local", "embeddinggemma", 2, "nota fiscal");

        assertThat(vetor).containsExactly(0.6f, 0.8f);
        JsonNode corpo = JSON.readTree(ollama.pedidos("/api/embed").get(0).corpo());
        assertThat(corpo.path("model").asText()).isEqualTo("embeddinggemma");
        assertThat(corpo.path("input").asText()).isEqualTo("nota fiscal");
    }

    @Test
    void vetorMenorQueAConfiguracaoERecusado() {
        ollama.responder(200, "{\"embeddings\":[[1.0,0.0]]}");

        assertThatThrownBy(() -> modelo().vetor("ia-local", "embeddinggemma", 768, "nota"))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> {
                    assertThat(f.tipo()).isEqualTo(FalhaDoModelo.Tipo.PEDIDO_RECUSADO);
                    assertThat(f.getMessage()).contains("devolve 2 valores");
                });
    }

    @Test
    void naoUsaChaveNemEnviaArquivos() {
        ModeloLocal modelo = modelo();
        assertThat(modelo.local()).isTrue();
        assertThat(modelo.enviar("x", pasta.resolve("a.png"), "image/png", 10, "a").uri()).startsWith("file:");
        modelo.apagar("x", "local"); // não falha: nada foi enviado
    }
}
