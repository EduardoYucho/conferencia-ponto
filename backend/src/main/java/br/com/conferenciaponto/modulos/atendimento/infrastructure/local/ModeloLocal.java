package br.com.conferenciaponto.modulos.atendimento.infrastructure.local;

import br.com.conferenciaponto.modulos.atendimento.domain.ia.ArquivoRemoto;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo.Tipo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.ModeloDeIA;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.PedidoAoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.RespostaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.video.ConversorDeMidia;
import br.com.conferenciaponto.modulos.atendimento.domain.video.ExtratorQuadros;
import br.com.conferenciaponto.modulos.atendimento.domain.video.FalhaNoQuadro;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.IaLocalProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * A IA local: o modelo roda neste computador, pelo Ollama, sem chave, sem cota e sem nada sair do computador. O que
 * o modelo não lê sozinho chega pronto: a fala dos áudios e dos vídeos, transcrita pelo Whisper; o vídeo, em quadros
 * espalhados do começo ao fim (pelo ffmpeg); o PDF, em texto e nas primeiras páginas como imagem (pelo PDFBox);
 * WebP e HEIC, em PNG. A resposta sai no esquema JSON pedido (o Ollama obriga o formato) ou em texto, sem esquema.
 *
 * <p>Com a placa de vídeo ocupada por outro programa (um jogo), o pedido nem começa: vem {@link Tipo#PLACA_OCUPADA},
 * e a tarefa espera a placa ficar livre sem gastar tentativa. O modelo sai da placa pouco depois do último uso.
 */
@Component
@ConditionalOnProperty(name = "atendimento.ia.motor", havingValue = "local", matchIfMissing = true)
public class ModeloLocal implements ModeloDeIA {

    private static final Logger log = LoggerFactory.getLogger(ModeloLocal.class);
    /** Uma análise ou um texto cabem com folga; acima disso, é o modelo repetindo a mesma coisa. */
    private static final int SAIDA_MAXIMA = 4096;
    private static final String FALANTE = "Falante";

    private final IaLocalProperties propriedades;
    private final ObjectMapper json;
    private final ClienteOllama ollama;
    private final Transcritor transcritor;
    private final MemoriaDaPlaca placa;
    private final ExtratorQuadros quadros;
    private final ConversorDeMidia conversor;
    /** Um pedido de cada vez na placa de vídeo (e a conferência dela sem um pedido nosso rodando). */
    private final ReentrantLock vez = new ReentrantLock(true);
    /** Pedidos cuja resposta já saiu cortada (o modelo repetindo): na próxima vez, com mais variação. */
    private final Set<Integer> cortados = ConcurrentHashMap.newKeySet();

    @Autowired
    public ModeloLocal(IaLocalProperties propriedades, ObjectMapper json, ExtratorQuadros quadros, ConversorDeMidia conversor) {
        this(propriedades, json, new ClienteOllama(propriedades.url(), json, propriedades.tempoConexao(), propriedades.tempoLimite()),
                new TranscritorWhisper(List.of(propriedades.whisper().executavelOuPadrao().toString()),
                        propriedades.whisper().modeloOuPadrao(), propriedades.whisper().idioma(),
                        propriedades.whisper().tempoLimite(), json),
                new MemoriaDaPlaca(List.of(propriedades.placa().nvidiaSmi())), quadros, conversor);
        log.info("IA local: {} no Ollama ({}), vetores com {}, áudios pelo Whisper ({}{})", propriedades.modelo(),
                propriedades.url(), propriedades.modeloVetor(), propriedades.whisper().executavelOuPadrao(),
                transcritor.disponivel() ? "" : ", não encontrado");
    }

    ModeloLocal(IaLocalProperties propriedades, ObjectMapper json, ClienteOllama ollama, Transcritor transcritor,
                MemoriaDaPlaca placa, ExtratorQuadros quadros, ConversorDeMidia conversor) {
        this.propriedades = propriedades;
        this.json = json;
        this.ollama = ollama;
        this.transcritor = transcritor;
        this.placa = placa;
        this.quadros = quadros;
        this.conversor = conversor;
    }

    @Override
    public boolean local() {
        return true;
    }

    // ------------------------------------------------------------------- arquivos: a IA local lê direto do disco

    @Override
    public ArquivoRemoto enviar(String chave, Path arquivo, String tipoDeMidia, long tamanho, String nomeParaExibir) {
        return new ArquivoRemoto("local", arquivo.toAbsolutePath().toUri().toString(), tipoDeMidia,
                ArquivoRemoto.Estado.ATIVO, null);
    }

    @Override
    public ArquivoRemoto consultar(String chave, String nome) {
        throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "a IA local não guarda arquivos enviados");
    }

    @Override
    public void apagar(String chave, String nome) {
        // nada foi enviado para lugar nenhum
    }

    // ------------------------------------------------------------------------------------------------ geração

    @Override
    public RespostaDoModelo gerar(String chave, PedidoAoModelo pedido) {
        vez.lock();
        try {
            esperarPlacaLivre();
            if (transcricaoPronta(pedido)) {
                return transcricaoDoAudio(pedido); // o Whisper já faz a análise do áudio: o modelo não precisa reescrever
            }
            return gerarNoOllama(pedido);
        } finally {
            vez.unlock();
        }
    }

    private RespostaDoModelo gerarNoOllama(PedidoAoModelo pedido) {
        Path temporaria = pastaTemporaria();
        int marca = marca(pedido);
        try {
            Conteudo conteudo = conteudo(pedido.midia(), temporaria);
            ObjectNode corpo = json.createObjectNode();
            corpo.put("model", propriedades.modelo());
            ArrayNode mensagens = corpo.putArray("messages");
            if (pedido.instrucaoDoSistema() != null && !pedido.instrucaoDoSistema().isBlank()) {
                mensagens.addObject().put("role", "system").put("content", pedido.instrucaoDoSistema());
            }
            ObjectNode usuario = mensagens.addObject().put("role", "user")
                    .put("content", conteudo.texto().isBlank() ? pedido.instrucao() : conteudo.texto() + "\n\n" + pedido.instrucao());
            if (!conteudo.imagens().isEmpty()) {
                ArrayNode imagens = usuario.putArray("images");
                conteudo.imagens().forEach(imagens::add);
            }
            boolean comEsquema = pedido.esquemaJson() != null && !pedido.esquemaJson().isBlank();
            if (comEsquema) {
                corpo.set("format", esquema(pedido.esquemaJson()));
            }
            boolean jaCortou = cortados.contains(marca);
            ObjectNode opcoes = corpo.putObject("options");
            opcoes.put("temperature", jaCortou ? 0.6 : pedido.temperatura() != null ? pedido.temperatura() : comEsquema ? 0.2 : 0.3);
            opcoes.put("num_ctx", propriedades.contexto());
            opcoes.put("num_predict", pedido.maxTokensSaida() > 0 ? Math.min(pedido.maxTokensSaida(), SAIDA_MAXIMA) : SAIDA_MAXIMA);
            opcoes.put("repeat_penalty", jaCortou ? 1.3 : 1.1);
            if (jaCortou) {
                opcoes.put("repeat_last_n", 256);
            }
            corpo.put("keep_alive", ClienteOllama.segundos(propriedades.manterCarregado()));

            JsonNode resposta = ollama.conversar(corpo);
            String texto = resposta.path("message").path("content").asText("").strip();
            if (texto.isEmpty()) {
                throw new FalhaDoModelo(Tipo.RESPOSTA_INVALIDA, "a IA local devolveu uma resposta vazia");
            }
            if ("length".equals(resposta.path("done_reason").asText(""))) {
                cortados.add(marca);
                throw new FalhaDoModelo(Tipo.RESPOSTA_INVALIDA, "a resposta da IA local foi cortada no limite de tamanho");
            }
            cortados.remove(marca);
            if (comEsquema && conteudo.fala() != null && pedido.esquemaJson().contains("\"narracao\"")) {
                texto = comANarracao(texto, conteudo.fala()); // a fala do vídeo vem do Whisper, não do modelo
            }
            return new RespostaDoModelo(texto, resposta.path("prompt_eval_count").asInt(0), resposta.path("eval_count").asInt(0));
        } finally {
            apagarPasta(temporaria);
        }
    }

    @Override
    public float[] vetor(String chave, String modelo, int dimensao, String texto) {
        String nome = modelo == null || modelo.isBlank() ? propriedades.modeloVetor() : modelo;
        JsonNode resposta = ollama.vetores(nome, texto, propriedades.manterCarregado());
        JsonNode valores = resposta.path("embeddings").path(0);
        if (!valores.isArray() || valores.isEmpty()) {
            throw new FalhaDoModelo(Tipo.RESPOSTA_INVALIDA, "a IA local não devolveu o vetor");
        }
        if (valores.size() < dimensao) {
            throw new FalhaDoModelo(Tipo.PEDIDO_RECUSADO, "O modelo de vetores " + nome + " devolve " + valores.size()
                    + " valores, menos que os " + dimensao + " da configuração (atendimento.ia.local.dimensao-vetor).");
        }
        float[] vetor = new float[dimensao]; // a mais, fica de fora (os modelos de vetores guardam o principal no começo)
        double soma = 0;
        for (int i = 0; i < dimensao; i++) {
            vetor[i] = (float) valores.get(i).asDouble();
            soma += vetor[i] * (double) vetor[i];
        }
        double norma = Math.sqrt(soma);
        if (norma == 0) {
            throw new FalhaDoModelo(Tipo.RESPOSTA_INVALIDA, "a IA local devolveu um vetor zerado");
        }
        for (int i = 0; i < dimensao; i++) {
            vetor[i] = (float) (vetor[i] / norma);
        }
        return vetor;
    }

    // ------------------------------------------------------------------------------------------ placa de vídeo

    /**
     * A placa está ocupada por outro programa (um jogo)? Com o modelo fora dela, pela memória livre: o modelo precisa
     * caber. Com o modelo carregado, a memória não diz nada (o Ollama não informa direito a parte dele, e o nvidia-smi
     * não separa por programa no Windows): vale o uso da placa com a IA parada (esta conferência roda sem pedido nosso
     * em andamento). Ocupada: tira o modelo da placa e avisa para a tarefa esperar.
     */
    void esperarPlacaLivre() {
        IaLocalProperties.Placa config = propriedades.placa();
        if (!config.esperarLivre()) {
            return;
        }
        Optional<MemoriaDaPlaca.Uso> uso = placa.agora();
        if (uso.isEmpty()) {
            return;
        }
        boolean carregado = ollama.algumCarregado();
        String motivo = null;
        if (!carregado) {
            long necessaria = config.memoriaNecessaria().toBytes();
            if (uso.get().livre() < necessaria) {
                motivo = (uso.get().livre() / (1024 * 1024)) + " MB livres; o modelo precisa de " + (necessaria / (1024 * 1024));
            }
        } else {
            OptionalInt menor = placa.menorUtilizacao(3, 400);
            if (menor.isPresent() && menor.getAsInt() >= config.usoMaximo()) {
                motivo = "em uso a " + menor.getAsInt() + "% com a IA parada";
            }
        }
        if (motivo != null) {
            if (carregado) {
                ollama.descarregar(propriedades.modelo());
            }
            log.info("IA local: placa de vídeo ocupada por outro programa ({}): esperando", motivo);
            throw new FalhaDoModelo(Tipo.PLACA_OCUPADA, "a placa de vídeo está ocupada", config.intervalo(), 0);
        }
    }

    // ------------------------------------------------------------------------------------- áudio: só o Whisper

    /** A análise de um áudio (o esquema com os trechos falados): a transcrição do Whisper já é a resposta. */
    private static boolean transcricaoPronta(PedidoAoModelo pedido) {
        PedidoAoModelo.Midia midia = pedido.midia();
        return midia != null && midia.uri() != null && midia.tipoDeMidia() != null
                && midia.tipoDeMidia().toLowerCase(Locale.ROOT).startsWith("audio/")
                && pedido.esquemaJson() != null && pedido.esquemaJson().contains("\"trechos\"")
                && pedido.esquemaJson().contains("\"falantes\"");
    }

    /**
     * O JSON da análise do áudio montado com os trechos do Whisper, sem o modelo reescrever a transcrição (numa
     * ligação longa, ele começava a repetir trechos e a resposta saía cortada). Quem fala não é separado: os trechos
     * ficam com "Falante" (a mensagem da conversa já diz quem mandou o áudio).
     */
    private RespostaDoModelo transcricaoDoAudio(PedidoAoModelo pedido) {
        Path temporaria = pastaTemporaria();
        try {
            Path arquivo = doDisco(pedido.midia().uri());
            List<Transcritor.Trecho> trechos = transcrever(arquivo, temporaria.resolve("audio.wav"));
            long duracao = conversor.duracaoMs(arquivo).filter(d -> d > 0).orElse(0L);
            ObjectNode lido = json.createObjectNode();
            lido.put("idioma", "pt-BR");
            lido.put("duracao", duracao > 0 ? minutos(duracao) : "não identificado");
            ArrayNode falantes = lido.putArray("falantes");
            if (!trechos.isEmpty()) {
                falantes.addObject().put("rotulo", FALANTE).put("papel", "nao_identificado");
            }
            ArrayNode lista = lido.putArray("trechos");
            for (Transcritor.Trecho t : trechos) {
                lista.addObject().put("inicio", minutos(t.inicioMs())).put("falante", FALANTE).put("texto", t.texto());
            }
            lido.put("temTrechoInaudivel", false);
            return new RespostaDoModelo(json.writeValueAsString(lido), 0, 0);
        } catch (IOException e) {
            throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "não foi possível montar a transcrição");
        } finally {
            apagarPasta(temporaria);
        }
    }

    private List<Transcritor.Trecho> transcrever(Path arquivo, Path wav) {
        try {
            conversor.paraWav(arquivo, wav);
        } catch (FalhaNoQuadro falha) {
            throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "o áudio não abriu no ffmpeg");
        }
        return transcritor.transcrever(wav);
    }

    /** A narração do vídeo trocada pela fala do Whisper (o modelo só descreve os passos). */
    private String comANarracao(String texto, List<Transcritor.Trecho> fala) {
        try {
            JsonNode lido = json.readTree(texto);
            if (!(lido instanceof ObjectNode objeto)) {
                return texto;
            }
            ArrayNode narracao = objeto.putArray("narracao");
            for (Transcritor.Trecho t : fala) {
                narracao.addObject().put("inicio", minutos(t.inicioMs())).put("falante", FALANTE).put("texto", t.texto());
            }
            return json.writeValueAsString(objeto);
        } catch (IOException e) {
            return texto; // fora do JSON: quem chama confere e pede de novo
        }
    }

    private static int marca(PedidoAoModelo pedido) {
        return java.util.Objects.hash(pedido.instrucao(), pedido.esquemaJson(),
                pedido.midia() == null ? null : pedido.midia().uri(),
                pedido.midia() == null || pedido.midia().dados() == null ? 0 : java.util.Arrays.hashCode(pedido.midia().dados()));
    }

    // --------------------------------------------------------------------------------- o conteúdo de cada mídia

    /** O que vai junto da instrução: texto (transcrição, texto do PDF, legenda dos quadros) e imagens em base64. */
    record Conteudo(String texto, List<String> imagens, List<Transcritor.Trecho> fala) {

        static final Conteudo NADA = new Conteudo("", List.of(), null);

        Conteudo(String texto, List<String> imagens) {
            this(texto, imagens, null);
        }
    }

    Conteudo conteudo(PedidoAoModelo.Midia midia, Path temporaria) {
        if (midia == null) {
            return Conteudo.NADA;
        }
        String tipo = midia.tipoDeMidia() == null ? "" : midia.tipoDeMidia().toLowerCase(Locale.ROOT);
        try {
            if (midia.dados() != null) {
                if (!tipo.startsWith("image/")) {
                    throw new FalhaDoModelo(Tipo.PEDIDO_RECUSADO, "A IA local só recebe imagens no próprio pedido.");
                }
                Path arquivo = temporaria.resolve("imagem" + extensao(tipo));
                Files.write(arquivo, midia.dados());
                return new Conteudo("", List.of(imagem(arquivo, tipo, temporaria)));
            }
            Path arquivo = doDisco(midia.uri());
            if (tipo.startsWith("image/")) {
                return new Conteudo("", List.of(imagem(arquivo, tipo, temporaria)));
            }
            if (tipo.startsWith("audio/")) {
                return audio(arquivo, temporaria);
            }
            if (tipo.startsWith("video/")) {
                return video(arquivo, temporaria);
            }
            if (tipo.equals("application/pdf")) {
                return pdf(arquivo);
            }
            if (tipo.startsWith("text/") || tipo.equals("application/json") || tipo.equals("application/xml")) {
                String texto = Files.readString(arquivo, StandardCharsets.UTF_8);
                return new Conteudo("Conteúdo do arquivo de texto:\n" + cortar(texto, propriedades.pdf().caracteres()), List.of());
            }
            throw new FalhaDoModelo(Tipo.PEDIDO_RECUSADO, "A IA local não lê arquivos do tipo " + midia.tipoDeMidia() + ".");
        } catch (IOException | UncheckedIOException e) {
            throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "não foi possível ler o arquivo (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static Path doDisco(String uri) {
        try {
            return Path.of(URI.create(uri));
        } catch (RuntimeException e) {
            throw new FalhaDoModelo(Tipo.PEDIDO_RECUSADO, "A IA local lê só arquivos deste computador.");
        }
    }

    /** PNG e JPEG vão como estão; os outros (WebP, HEIC, GIF...) viram PNG pelo ffmpeg. */
    private String imagem(Path arquivo, String tipo, Path temporaria) throws IOException {
        if (tipo.equals("image/png") || tipo.equals("image/jpeg") || tipo.equals("image/jpg")) {
            return base64(arquivo);
        }
        Path png = temporaria.resolve("convertida-" + System.nanoTime() + ".png");
        try {
            quadros.extrair(arquivo, 0, png);
        } catch (FalhaNoQuadro falha) {
            throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "a imagem " + tipo + " não pôde ser convertida para PNG (o ffmpeg "
                    + "é necessário para WebP e HEIC)");
        }
        return base64(png);
    }

    private Conteudo audio(Path arquivo, Path temporaria) {
        List<Transcritor.Trecho> trechos = transcrever(arquivo, temporaria.resolve("audio.wav"));
        return new Conteudo("O áudio já foi transcrito por um programa de transcrição (a transcrição pode ter erros "
                + "em nomes e números). Use esta transcrição como o conteúdo do áudio; o início de cada trecho está entre "
                + "colchetes, em minutos e segundos:\n" + transcricao(trechos, "(o programa não encontrou fala neste áudio)"),
                List.of());
    }

    private Conteudo video(Path arquivo, Path temporaria) {
        long duracao = conversor.duracaoMs(arquivo).filter(d -> d > 0).orElse(0L);
        if (duracao == 0) {
            throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "não foi possível saber a duração do vídeo (o ffmpeg é "
                    + "necessário para a IA local ver os vídeos)");
        }
        int quantos = Math.max(1, Math.min(propriedades.video().quadros(), (int) Math.max(1, duracao / 1000)));
        List<String> imagens = new ArrayList<>();
        List<String> instantes = new ArrayList<>();
        for (int i = 0; i < quantos; i++) {
            long instante = duracao * (2L * i + 1) / (2L * quantos);
            Path quadro = temporaria.resolve(String.format(Locale.ROOT, "quadro-%03d.png", i));
            try {
                quadros.extrair(arquivo, instante, quadro);
                imagens.add(base64(quadro));
                instantes.add("imagem " + imagens.size() + " = " + minutos(instante));
            } catch (FalhaNoQuadro | IOException e) {
                log.debug("IA local: o quadro em {} ms não saiu ({})", instante, e.getClass().getSimpleName());
            }
        }
        if (imagens.isEmpty()) {
            throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "não foi possível tirar os quadros do vídeo (o ffmpeg é "
                    + "necessário para a IA local ver os vídeos)");
        }
        String fala;
        List<Transcritor.Trecho> trechos = List.of();
        Path wav = temporaria.resolve("audio-do-video.wav");
        try {
            conversor.paraWav(arquivo, wav);
            trechos = transcritor.transcrever(wav);
            fala = transcricao(trechos, "(sem fala no vídeo)");
        } catch (FalhaNoQuadro falha) {
            fala = "(o vídeo não tem áudio)";
        } catch (FalhaDoModelo falha) {
            if (falha.tipo() == Tipo.MODELO_INEXISTENTE) {
                throw falha;
            }
            fala = "(o áudio do vídeo não pôde ser transcrito)";
        }
        return new Conteudo("O vídeo tem " + minutos(duracao) + ". As imagens são quadros tirados dele, em ordem ("
                + String.join("; ", instantes) + "): use esses instantes nos passos. A fala do vídeo, transcrita por um "
                + "programa (pode ter erros em nomes e números), com o início de cada trecho:\n" + fala
                + "\nNa resposta, deixe a narração vazia ([]): o sistema põe a fala transcrita no lugar.", imagens, trechos);
    }

    private Conteudo pdf(Path arquivo) throws IOException {
        try (PDDocument documento = Loader.loadPDF(arquivo.toFile())) {
            PDFTextStripper extrator = new PDFTextStripper();
            String texto = extrator.getText(documento).strip();
            PDFRenderer renderizador = new PDFRenderer(documento);
            int paginas = Math.min(documento.getNumberOfPages(), Math.max(0, propriedades.pdf().paginas()));
            List<String> imagens = new ArrayList<>();
            for (int i = 0; i < paginas; i++) {
                BufferedImage pagina = renderizador.renderImageWithDPI(i, 110);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(pagina, "png", png);
                imagens.add(Base64.getEncoder().encodeToString(png.toByteArray()));
            }
            String sobre = "O documento tem " + documento.getNumberOfPages() + " página(s)"
                    + (paginas > 0 ? "; as imagens são as " + paginas + " primeiras" : "") + ". O texto extraído dele:\n"
                    + (texto.isEmpty() ? "(o PDF não tem texto selecionável: leia pelas imagens)" : cortar(texto, propriedades.pdf().caracteres()));
            return new Conteudo(sobre, imagens);
        } catch (IOException e) {
            throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "o PDF não abriu");
        }
    }

    static String transcricao(List<Transcritor.Trecho> trechos, String vazio) {
        if (trechos.isEmpty()) {
            return vazio;
        }
        StringBuilder t = new StringBuilder();
        for (Transcritor.Trecho trecho : trechos) {
            t.append('[').append(minutos(trecho.inicioMs())).append("] ").append(trecho.texto()).append('\n');
        }
        return t.toString().stripTrailing();
    }

    static String minutos(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }

    private JsonNode esquema(String esquemaJson) {
        try {
            return json.readTree(esquemaJson);
        } catch (IOException e) {
            throw new FalhaDoModelo(Tipo.PEDIDO_RECUSADO, "O esquema da resposta não é JSON.");
        }
    }

    private static String cortar(String texto, int maximo) {
        return texto.length() <= maximo ? texto : texto.substring(0, maximo) + "\n(… o resto do texto ficou de fora)";
    }

    private static String extensao(String tipo) {
        return switch (tipo) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/heic", "image/heif" -> ".heic";
            case "image/gif" -> ".gif";
            default -> ".img";
        };
    }

    private static String base64(Path arquivo) throws IOException {
        return Base64.getEncoder().encodeToString(Files.readAllBytes(arquivo));
    }

    private static Path pastaTemporaria() {
        try {
            return Files.createTempDirectory("ia-local-");
        } catch (IOException e) {
            throw new FalhaDoModelo(Tipo.ARQUIVO_FALHOU, "não foi possível criar a pasta temporária");
        }
    }

    private static void apagarPasta(Path pasta) {
        try (Stream<Path> dentro = Files.walk(pasta)) {
            dentro.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // fica na pasta temporária do sistema
                }
            });
        } catch (IOException e) {
            // idem
        }
    }

}
