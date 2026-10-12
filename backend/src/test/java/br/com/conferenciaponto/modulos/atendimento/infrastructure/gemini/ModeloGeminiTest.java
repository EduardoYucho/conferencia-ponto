package br.com.conferenciaponto.modulos.atendimento.infrastructure.gemini;

import br.com.conferenciaponto.modulos.atendimento.ServidorDoGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.ArquivoRemoto;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo.Tipo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.PedidoAoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.RespostaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.GeminiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O adaptador do Gemini contra a API de mentira: o pedido, a leitura da resposta, cada erro e o envio retomável. */
class ModeloGeminiTest {

    private static final String CHAVE = "AQ.Ab8RN6Kc-chave_de-teste.0123456789wxyz";
    private static final String ESQUEMA = "{\"type\":\"object\",\"properties\":{\"descricao\":{\"type\":\"string\"}},\"required\":[\"descricao\"]}";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path pasta;

    private final ServidorDoGemini gemini = new ServidorDoGemini();

    @AfterEach
    void parar() {
        gemini.close();
    }

    private ModeloGemini modelo() {
        return modelo(gemini.base(), Duration.ofSeconds(5));
    }

    private static ModeloGemini modelo(URI base, Duration tempoLimite) {
        return new ModeloGemini(new GeminiProperties(base, Duration.ofSeconds(2), Duration.ofSeconds(2), tempoLimite,
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(10)), JSON, 1024);
    }

    private static PedidoAoModelo pedido(PedidoAoModelo.Midia midia, boolean altaResolucao) {
        return new PedidoAoModelo("gemini-3.8-flash", "regras comuns", "analise a imagem", ESQUEMA, midia, altaResolucao,
                null, null, 4096);
    }

    private static PedidoAoModelo imagem() {
        return pedido(PedidoAoModelo.Midia.noPedido("image/png", new byte[]{1, 2, 3}), true);
    }

    private FalhaDoModelo falhaCom(int status, String corpo) {
        gemini.responder(status, corpo);
        try {
            modelo().gerar(CHAVE, imagem());
        } catch (FalhaDoModelo falha) {
            return falha;
        }
        throw new AssertionError("era para falhar");
    }

    // ------------------------------------------------------------------------------------------- geração

    @Test
    void geraComAChaveSoNoCabecalhoEOPedidoNoFormatoDoGemini() throws IOException {
        gemini.responder(200, ServidorDoGemini.sucesso("{\"descricao\":\"Tela de pedidos\"}", 1200, 80));

        RespostaDoModelo resposta = modelo().gerar(CHAVE, imagem());

        assertThat(resposta.texto()).isEqualTo("{\"descricao\":\"Tela de pedidos\"}");
        assertThat(resposta.tokensEntrada()).isEqualTo(1200);
        assertThat(resposta.tokensSaida()).isEqualTo(80);
        ServidorDoGemini.Pedido feito = gemini.geracoes().get(0);
        assertThat(feito.caminho()).isEqualTo("/v1beta/models/gemini-3.8-flash:generateContent");
        assertThat(feito.consulta()).isNull();
        assertThat(feito.cabecalhos()).containsEntry("x-goog-api-key", CHAVE);
        assertThat(feito.texto()).doesNotContain(CHAVE);
        JsonNode corpo = JSON.readTree(feito.corpo());
        assertThat(corpo.at("/systemInstruction/parts/0/text").asText()).isEqualTo("regras comuns");
        assertThat(corpo.at("/contents/0/parts/0/inlineData/mimeType").asText()).isEqualTo("image/png");
        assertThat(Base64.getDecoder().decode(corpo.at("/contents/0/parts/0/inlineData/data").asText())).containsExactly(1, 2, 3);
        assertThat(corpo.at("/contents/0/parts/1/text").asText()).isEqualTo("analise a imagem");
        assertThat(corpo.at("/generationConfig/responseMimeType").asText()).isEqualTo("application/json");
        assertThat(corpo.at("/generationConfig/responseJsonSchema/required/0").asText()).isEqualTo("descricao");
        assertThat(corpo.at("/generationConfig/mediaResolution").asText()).isEqualTo("MEDIA_RESOLUTION_HIGH");
        assertThat(corpo.at("/generationConfig/maxOutputTokens").asInt()).isEqualTo(4096);
        assertThat(corpo.at("/generationConfig").has("temperature")).isFalse();
        assertThat(corpo.at("/generationConfig").has("thinkingConfig")).isFalse();
    }

    @Test
    void arquivoEnviadoVaiPeloEnderecoETemperaturaERaciocinioSoQuandoConfigurados() throws IOException {
        PedidoAoModelo comArquivo = new PedidoAoModelo("gemini-3.8-flash", null, "transcreva", ESQUEMA,
                PedidoAoModelo.Midia.enviada(new ArquivoRemoto("files/abc", "https://g/v1beta/files/abc", "audio/ogg",
                        ArquivoRemoto.Estado.ATIVO, null)), false, 0.2, "low", 0);

        modelo().gerar(CHAVE, comArquivo);

        JsonNode corpo = JSON.readTree(gemini.geracoes().get(0).corpo());
        assertThat(corpo.has("systemInstruction")).isFalse();
        assertThat(corpo.at("/contents/0/parts/0/fileData/fileUri").asText()).isEqualTo("https://g/v1beta/files/abc");
        assertThat(corpo.at("/contents/0/parts/0/fileData/mimeType").asText()).isEqualTo("audio/ogg");
        assertThat(corpo.at("/generationConfig/temperature").asDouble()).isEqualTo(0.2);
        assertThat(corpo.at("/generationConfig/thinkingConfig/thinkingLevel").asText()).isEqualTo("low");
        assertThat(corpo.at("/generationConfig").has("mediaResolution")).isFalse();
        assertThat(corpo.at("/generationConfig").has("maxOutputTokens")).isFalse();
    }

    @Test
    void ignoraAsPartesDeRaciocinioEContaOsTokensDelesComoSaida() {
        gemini.responder(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"pensando...\",\"thought\":true},"
                + "{\"text\":\"{\\\"descricao\\\":\\\"ok\\\"}\"}]},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5,\"thoughtsTokenCount\":20}}");

        RespostaDoModelo resposta = modelo().gerar(CHAVE, imagem());

        assertThat(resposta.texto()).isEqualTo("{\"descricao\":\"ok\"}");
        assertThat(resposta.tokensSaida()).isEqualTo(25);
    }

    @Test
    void limitePorMinutoComOTempoPedidoPeloGoogle() {
        FalhaDoModelo falha = falhaCom(429, ServidorDoGemini.erro(429, "RESOURCE_EXHAUSTED", "Quota exceeded", """
                [{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":
                "generativelanguage.googleapis.com/generate_content_free_tier_requests",
                "quotaId":"GenerateRequestsPerMinutePerProjectPerModel-FreeTier"}]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]"""));

        assertThat(falha.tipo()).isEqualTo(Tipo.LIMITE);
        assertThat(falha.esperar()).isEqualTo(Duration.ofSeconds(37));
    }

    @Test
    void cotaDoDiaAcabou() {
        FalhaDoModelo falha = falhaCom(429, ServidorDoGemini.erro(429, "RESOURCE_EXHAUSTED", "Quota exceeded", """
                [{"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[{"quotaMetric":
                "generativelanguage.googleapis.com/generate_content_free_tier_requests",
                "quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"14s"}]"""));

        assertThat(falha.tipo()).isEqualTo(Tipo.COTA_DIARIA);
    }

    @Test
    void chaveRecusadaNosFormatosQueOGoogleUsa() {
        assertThat(falhaCom(400, ServidorDoGemini.erro(400, "INVALID_ARGUMENT", "API key not valid.",
                "[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"API_KEY_INVALID\"}]")).tipo())
                .isEqualTo(Tipo.CHAVE_RECUSADA);
        FalhaDoModelo tipoDeChave = falhaCom(401, ServidorDoGemini.erro(401, "UNAUTHENTICATED", "Expected OAuth 2 access token",
                "[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"ACCESS_TOKEN_TYPE_UNSUPPORTED\"}]"));
        assertThat(tipoDeChave.tipo()).isEqualTo(Tipo.CHAVE_RECUSADA);
        assertThat(tipoDeChave.getMessage()).contains("tipo de chave");
        FalhaDoModelo desativada = falhaCom(403, ServidorDoGemini.erro(403, "PERMISSION_DENIED", "API not enabled",
                "[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"SERVICE_DISABLED\"}]"));
        assertThat(desativada.tipo()).isEqualTo(Tipo.CHAVE_RECUSADA);
        assertThat(desativada.getMessage()).contains("não está ativada");
    }

    @Test
    void creditosAcabaramModeloInexistenteEPedidoRecusado() {
        assertThat(falhaCom(402, ServidorDoGemini.erro(402, "FAILED_PRECONDITION", "Insufficient balance", null)).tipo())
                .isEqualTo(Tipo.SEM_CREDITO);
        assertThat(falhaCom(404, ServidorDoGemini.erro(404, "NOT_FOUND", "models/x is not found", null)).tipo())
                .isEqualTo(Tipo.MODELO_INEXISTENTE);
        FalhaDoModelo recusado = falhaCom(400, ServidorDoGemini.erro(400, "INVALID_ARGUMENT", "Unsupported MIME type", null));
        assertThat(recusado.tipo()).isEqualTo(Tipo.PEDIDO_RECUSADO);
        assertThat(recusado.getMessage()).contains("Unsupported MIME type");
    }

    @Test
    void googleComProblemaOuQueNaoRespondeATempo() {
        assertThat(falhaCom(503, ServidorDoGemini.erro(503, "UNAVAILABLE", "overloaded", null)).tipo()).isEqualTo(Tipo.INDISPONIVEL);
        assertThat(falhaCom(500, "erro qualquer").tipo()).isEqualTo(Tipo.INDISPONIVEL);

        gemini.responderDevagar(200, ServidorDoGemini.sucesso("{}", 1, 1), 1500);
        assertThatThrownBy(() -> modelo(gemini.base(), Duration.ofMillis(300)).gerar(CHAVE, imagem()))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.tipo()).isEqualTo(Tipo.INDISPONIVEL));
    }

    @Test
    void semConexaoComOGoogle() throws IOException {
        int portaFechada;
        try (ServerSocket s = new ServerSocket(0)) {
            portaFechada = s.getLocalPort();
        }
        assertThatThrownBy(() -> modelo(URI.create("http://127.0.0.1:" + portaFechada), Duration.ofSeconds(2)).gerar(CHAVE, imagem()))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.tipo()).isEqualTo(Tipo.SEM_INTERNET));
    }

    @Test
    void bloqueioDeConteudoComOMotivo() {
        FalhaDoModelo pedido = falhaCom(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
        assertThat(pedido.tipo()).isEqualTo(Tipo.BLOQUEOU);
        assertThat(pedido.getMessage()).isEqualTo("segurança");

        FalhaDoModelo resposta = falhaCom(200, "{\"candidates\":[{\"finishReason\":\"RECITATION\"}]}");
        assertThat(resposta.tipo()).isEqualTo(Tipo.BLOQUEOU);
        assertThat(resposta.getMessage()).contains("conteúdo protegido");
    }

    @Test
    void respostaCortadaOuVaziaEInvalida() {
        assertThat(falhaCom(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"desc\"}]},"
                + "\"finishReason\":\"MAX_TOKENS\"}]}").tipo()).isEqualTo(Tipo.RESPOSTA_INVALIDA);
        assertThat(falhaCom(200, "{\"candidates\":[]}").tipo()).isEqualTo(Tipo.RESPOSTA_INVALIDA);
        assertThat(falhaCom(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"  \"}]},\"finishReason\":\"STOP\"}]}")
                .tipo()).isEqualTo(Tipo.RESPOSTA_INVALIDA);
        assertThat(falhaCom(200, "isso não é json").tipo()).isEqualTo(Tipo.RESPOSTA_INVALIDA);
    }

    @Test
    void nomeDeModeloEstranhoNaConfiguracaoNemSai() {
        PedidoAoModelo estranho = new PedidoAoModelo("../../files", null, "x", ESQUEMA, null, false, null, null, 0);

        assertThatThrownBy(() -> modelo().gerar(CHAVE, estranho))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.tipo()).isEqualTo(Tipo.MODELO_INEXISTENTE));
        assertThat(gemini.pedidos()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------- arquivos

    private Path arquivo(int tamanho) throws IOException {
        byte[] bytes = new byte[tamanho];
        for (int i = 0; i < tamanho; i++) {
            bytes[i] = (byte) (i % 251);
        }
        return Files.write(pasta.resolve("audio.ogg"), bytes);
    }

    @Test
    void envioRetomavelEmPedacosComAChaveSoNoCabecalho() throws IOException {
        Path audio = arquivo(2500);

        ArquivoRemoto remoto = modelo().enviar(CHAVE, audio, "audio/ogg", 2500, "atendimento-arquivo-1");

        assertThat(remoto.nome()).startsWith("files/");
        assertThat(remoto.estado()).isEqualTo(ArquivoRemoto.Estado.ATIVO);
        assertThat(gemini.arquivoRecebido(remoto.nome())).isEqualTo(Files.readAllBytes(audio));
        List<ServidorDoGemini.Pedido> pedidos = gemini.pedidos();
        ServidorDoGemini.Pedido inicio = pedidos.get(0);
        assertThat(inicio.caminho()).isEqualTo("/upload/v1beta/files");
        assertThat(inicio.cabecalhos()).containsEntry("x-goog-upload-protocol", "resumable")
                .containsEntry("x-goog-upload-command", "start")
                .containsEntry("x-goog-upload-header-content-length", "2500")
                .containsEntry("x-goog-upload-header-content-type", "audio/ogg")
                .containsEntry("x-goog-api-key", CHAVE);
        assertThat(inicio.texto()).contains("atendimento-arquivo-1").doesNotContain(CHAVE);
        List<String> comandos = pedidos.stream().skip(1).map(p -> p.cabecalhos().get("x-goog-upload-command")).toList();
        assertThat(comandos).containsExactly("upload", "upload", "upload, finalize");
        assertThat(pedidos.stream().skip(1).map(p -> p.cabecalhos().get("x-goog-upload-offset")))
                .containsExactly("0", "1024", "2048");
    }

    @Test
    void conexaoQueCaiNoMeioDoEnvioPerguntaQuantoChegouEContinua() throws IOException {
        Path audio = arquivo(3000);
        gemini.cairNoPedaco(2);

        ArquivoRemoto remoto = modelo().enviar(CHAVE, audio, "audio/ogg", 3000, "atendimento-arquivo-1");

        assertThat(gemini.arquivoRecebido(remoto.nome())).isEqualTo(Files.readAllBytes(audio));
        assertThat(gemini.pedidos().stream().map(p -> p.cabecalhos().get("x-goog-upload-command")))
                .contains("query");
    }

    @Test
    void videoEnviadoEmPartesComQuedaVaiAoModeloComATaxaDeQuadros() throws IOException {
        Path video = arquivo(3500);
        gemini.cairNoPedaco(3);

        ArquivoRemoto remoto = modelo().enviar(CHAVE, video, "video/mp4", 3500, "atendimento-arquivo-2");
        assertThat(gemini.arquivoRecebido(remoto.nome())).isEqualTo(Files.readAllBytes(video));
        assertThat(gemini.pedidos().get(0).cabecalhos()).containsEntry("x-goog-upload-header-content-type", "video/mp4");

        gemini.responder(200, ServidorDoGemini.sucesso("{\"descricao\":\"Gravação da tela\"}", 9000, 300));
        modelo().gerar(CHAVE, new PedidoAoModelo("gemini-3.8-flash", "regras", "analise o vídeo", ESQUEMA,
                PedidoAoModelo.Midia.video(new ArquivoRemoto(remoto.nome(), "https://g/v1beta/" + remoto.nome(), "video/mp4",
                        ArquivoRemoto.Estado.ATIVO, null), 2), false, null, null, 0));

        JsonNode corpo = JSON.readTree(gemini.geracoes().get(0).corpo());
        assertThat(corpo.at("/contents/0/parts/0/fileData/mimeType").asText()).isEqualTo("video/mp4");
        assertThat(corpo.at("/contents/0/parts/0/videoMetadata/fps").asDouble()).isEqualTo(2.0);
        assertThat(corpo.at("/generationConfig").has("mediaResolution")).isFalse();
    }

    @Test
    void enderecoDeEnvioEmOutroHostNaoRecebeAChave() throws IOException {
        gemini.uploadEmOutroHost();

        ArquivoRemoto remoto = modelo().enviar(CHAVE, arquivo(500), "audio/ogg", 500, "atendimento-arquivo-1");

        assertThat(remoto.nome()).startsWith("files/");
        assertThat(gemini.pedidos().stream().skip(1)).allSatisfy(p -> assertThat(p.cabecalhos()).doesNotContainKey("x-goog-api-key"));
    }

    @Test
    void consultaASituacaoEApagaOArquivo() throws IOException {
        gemini.estados("PROCESSING", "ACTIVE");
        ArquivoRemoto enviado = modelo().enviar(CHAVE, arquivo(10), "audio/ogg", 10, "a");
        assertThat(enviado.estado()).isEqualTo(ArquivoRemoto.Estado.PROCESSANDO);

        assertThat(modelo().consultar(CHAVE, enviado.nome()).estado()).isEqualTo(ArquivoRemoto.Estado.PROCESSANDO);
        assertThat(modelo().consultar(CHAVE, enviado.nome()).estado()).isEqualTo(ArquivoRemoto.Estado.ATIVO);
        modelo().apagar(CHAVE, enviado.nome());
        modelo().apagar(CHAVE, enviado.nome()); // já apagado: não falha

        assertThat(gemini.apagados()).contains(enviado.nome());
        assertThatThrownBy(() -> modelo().consultar(CHAVE, enviado.nome()))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.tipo()).isEqualTo(Tipo.ARQUIVO_FALHOU));
        assertThatThrownBy(() -> modelo().consultar(CHAVE, "../segredo"))
                .isInstanceOfSatisfying(FalhaDoModelo.class, f -> assertThat(f.tipo()).isEqualTo(Tipo.ARQUIVO_FALHOU));
    }

    @Test
    void arquivoQueOGoogleNaoConseguiuProcessar() throws IOException {
        gemini.estados("FAILED");

        ArquivoRemoto enviado = modelo().enviar(CHAVE, arquivo(10), "audio/ogg", 10, "a");

        assertThat(enviado.estado()).isEqualTo(ArquivoRemoto.Estado.FALHOU);
        assertThat(gemini.arquivoRecebido(enviado.nome())).hasSize(10);
    }

    // ------------------------------------------------------------------------------------------- vetores

    @Test
    void vetorPeloEmbedContentNormalizadoComAChaveSoNoCabecalho() throws IOException {
        gemini.responder(200, "{\"embedding\":{\"values\":[3, 4, 0, 99]}}");

        float[] vetor = modelo().vetor(CHAVE, "gemini-embedding-2", 3, "title: Erro | text: nota duplicada");

        assertThat(vetor).containsExactly(0.6f, 0.8f, 0f);
        ServidorDoGemini.Pedido pedido = gemini.pedidos().get(0);
        assertThat(pedido.caminho()).isEqualTo("/v1beta/models/gemini-embedding-2:embedContent");
        assertThat(pedido.cabecalhos()).containsEntry("x-goog-api-key", CHAVE);
        assertThat(pedido.consulta()).isNull();
        JsonNode corpo = JSON.readTree(pedido.texto());
        assertThat(corpo.path("outputDimensionality").asInt()).isEqualTo(3);
        assertThat(corpo.path("content").path("parts").path(0).path("text").asText()).isEqualTo("title: Erro | text: nota duplicada");
    }

    @Test
    void vetorComFalhaTraduzida() {
        gemini.responder(200, "{\"embedding\":{\"values\":[1, 2]}}");
        assertThatThrownBy(() -> modelo().vetor(CHAVE, "gemini-embedding-2", 3, "texto"))
                .isInstanceOf(FalhaDoModelo.class).extracting("tipo").isEqualTo(Tipo.RESPOSTA_INVALIDA);
        gemini.responder(429, ServidorDoGemini.erro(429, "RESOURCE_EXHAUSTED", "Quota exceeded", null));
        assertThatThrownBy(() -> modelo().vetor(CHAVE, "gemini-embedding-2", 3, "texto"))
                .isInstanceOf(FalhaDoModelo.class).extracting("tipo").isEqualTo(Tipo.LIMITE);
        gemini.responder(403, ServidorDoGemini.erro(403, "PERMISSION_DENIED", "denied", null));
        assertThatThrownBy(() -> modelo().vetor(CHAVE, "gemini-embedding-2", 3, "texto"))
                .isInstanceOf(FalhaDoModelo.class).extracting("tipo").isEqualTo(Tipo.CHAVE_RECUSADA);
        assertThatThrownBy(() -> modelo().vetor(CHAVE, "modelo/../x", 3, "texto"))
                .isInstanceOf(FalhaDoModelo.class).extracting("tipo").isEqualTo(Tipo.MODELO_INEXISTENTE);
        assertThat(gemini.pedidos()).hasSize(3);
    }
}
