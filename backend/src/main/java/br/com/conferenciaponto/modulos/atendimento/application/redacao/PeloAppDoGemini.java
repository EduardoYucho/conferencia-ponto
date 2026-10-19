package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.application.ia.ChamadasAoGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.AtendimentoGuardado;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.ItemDaLinha;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.LinhaDoTempo;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.MaterialDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.pacote.JuntadorDePdf;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.ConferenciaDoTexto;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.ModeloDoTexto;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.MontagemDoHtml;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SaidaGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SituacaoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TextoRedigido;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.video.ConversorDeMidia;
import br.com.conferenciaponto.modulos.atendimento.domain.video.ExtratorQuadros;
import br.com.conferenciaponto.modulos.atendimento.domain.video.FalhaNoQuadro;
import br.com.conferenciaponto.modulos.atendimento.domain.video.InstanteDoVideo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * O texto pelo app do Gemini (gemini.google.com), sem usar a cota da API: o sistema monta o pedido (as regras, o
 * prompt do tipo, o formato da resposta e o material do atendimento) e os arquivos para anexar — as imagens e os
 * documentos num PDF só, os áudios juntos num MP3 com o mapa de tempos, os vídeos à parte, porque o app aceita até 10
 * arquivos por pedido. A pessoa cola o pedido no app, anexa os arquivos e cola a resposta de volta: ela passa pela
 * mesma conferência da API e vira uma versão do texto, igual às outras (edição, cópia, base de conhecimento).
 */
@Service
public class PeloAppDoGemini {

    private static final Logger log = LoggerFactory.getLogger(PeloAppDoGemini.class);

    /** O "modelo" gravado no texto que veio do app. */
    public static final String MODELO_DO_APP = "app do Gemini (colado)";
    static final String PDF = "Anexos.pdf";
    static final String AUDIOS = "Audios";
    /** Os limites do app do Gemini no plano sem assinatura (ajuda do Google, outubro de 2026). */
    static final int ARQUIVOS_POR_PEDIDO = 10;
    static final long AUDIO_MAXIMO_MS = 10 * 60_000L;
    static final long VIDEO_MAXIMO_MS = 5 * 60_000L;
    static final long ARQUIVO_MAXIMO = 100L * 1024 * 1024;

    /** Os formatos que já vêm compactados: no .zip vão como estão (compactar de novo só gasta tempo). */
    private static final Set<String> JA_COMPACTADOS = Set.of("mp4", "mov", "webm", "mkv", "3gp", "mp3", "ogg", "oga",
            "opus", "m4a", "aac", "jpg", "jpeg", "png", "webp", "gif", "heic", "zip");
    private static final Set<String> IMAGENS_DO_PDF = Set.of("jpg", "jpeg", "png", "gif", "bmp");
    private static final DateTimeFormatter DIA_E_HORA = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm");
    private static final Pattern BLOCO_JSON = Pattern.compile("```(?:json|JSON)?\\s*(\\{.*})\\s*```", Pattern.DOTALL);
    private static final Pattern QUADRO = Pattern.compile("^([A-Za-z]+\\d+)@(\\d{1,2}:\\d{2}(?::\\d{2})?)$");

    private final Atendimentos atendimentos;
    private final Saidas saidas;
    private final MontarLinhaDoTempo linhaDoTempo;
    private final RedigirSaida redigir;
    private final ChamadasAoGemini gemini;
    private final PastaDosAtendimentos pasta;
    private final JuntadorDePdf juntador;
    private final ConversorDeMidia conversor;
    private final ExtratorQuadros extrator;
    private final ObjectMapper json;
    private final TransactionOperations transacao;
    private final Clock clock;

    public PeloAppDoGemini(Atendimentos atendimentos, Saidas saidas, MontarLinhaDoTempo linhaDoTempo, RedigirSaida redigir,
                           ChamadasAoGemini gemini, PastaDosAtendimentos pasta, JuntadorDePdf juntador,
                           ConversorDeMidia conversor, ExtratorQuadros extrator, ObjectMapper json,
                           TransactionOperations transacao, Clock clock) {
        this.atendimentos = atendimentos;
        this.saidas = saidas;
        this.linhaDoTempo = linhaDoTempo;
        this.redigir = redigir;
        this.gemini = gemini;
        this.pasta = pasta;
        this.juntador = juntador;
        this.conversor = conversor;
        this.extrator = extrator;
        this.json = json;
        this.transacao = transacao;
        this.clock = clock;
    }

    /** Um arquivo para anexar no app: o nome no pedido, onde está no servidor e o que tem dentro. */
    public record ArquivoDoPedido(String nome, Path local, long tamanho, String conteudo) {
    }

    /** O que a tela mostra: o pedido para colar, os arquivos para anexar e o que conferir antes. */
    public record PedidoView(String tipo, String pedido, List<Arquivo> arquivos, List<String> avisos, long audioMs,
                             long videoMs) {

        public record Arquivo(String nome, long tamanho, String conteudo) {
        }
    }

    // --------------------------------------------------------------------------------------------- o pedido

    /**
     * Monta o pedido para o app: a linha do tempo de agora (guardada, para a resposta usar os mesmos
     * identificadores) e os arquivos do pedido, de novo a cada vez.
     */
    public PedidoView pedido(Usuario usuario, UUID atendimentoId, TipoDeSaida tipo) {
        AtendimentoGuardado atendimento = doDono(usuario, atendimentoId);
        PromptsDaRedacao.Prompt prompt = promptDo(tipo);
        LinhaDoTempo linha = linhaDoTempo.montar(atendimento);
        Pacote pacote = montarPacote(atendimento, linha);
        guardarOndeEsta(atendimentoId, pacote);
        String material = MaterialDaRedacao.texto(atendimento.resumo().chamado(), atendimento.resumo().contato(),
                atendimento.resumo().inicio(), atendimento.resumo().fim(), comOndeEsta(linha, pacote.ondeEsta()),
                imagensDoApp(atendimento, linha), clock.getZone());
        Optional<String> video = linha.todos().filter(i -> "video".equals(i.categoria()))
                .map(ItemDaLinha::id).filter(pacote.ondeEsta()::containsKey).findFirst();
        if (video.isPresent() && extrator.situacao().disponivel()) {
            material += "\n\nQuadros dos vídeos: para ilustrar um passo mostrado num vídeo, use em \"imagens\" o "
                    + "identificador do vídeo com o instante (minutos:segundos), como " + video.get() + "@00:15 (o "
                    + "sistema tira a imagem do vídeo nesse instante).";
        }
        String contato = atendimento.resumo().contato();
        String instrucao = prompt.instrucao(Map.of(
                "CONTATO", contato == null || contato.isBlank() ? "contato não identificado" : contato.strip(),
                "LIMITE_DO_ASSUNTO", String.valueOf(ConferenciaDoTexto.limiteDoAssunto(contato))));
        String texto = PromptsDaRedacao.SISTEMA + "\n\n" + instrucao + "\n\n" + comoResponder(pacote, prompt) + "\n\n---\n\n"
                + material;
        log.info("Atendimento {}: pedido de {} para o app do Gemini montado ({} arquivo(s), {} aviso(s))", atendimentoId,
                tipo.codigo(), pacote.arquivos().size(), pacote.avisos().size());
        return new PedidoView(tipo.codigo(), texto, pacote.arquivos().stream()
                .map(a -> new PedidoView.Arquivo(a.nome(), a.tamanho(), a.conteudo())).toList(), pacote.avisos(),
                pacote.audioMs(), pacote.videoMs());
    }

    /** Um arquivo do último pedido montado. */
    public ArquivoDoPedido arquivo(Usuario usuario, UUID atendimentoId, String nome) {
        AtendimentoGuardado atendimento = doDono(usuario, atendimentoId);
        return arquivosDoUltimoPedido(atendimento).stream().filter(a -> a.nome().equals(nome)).findFirst()
                .orElseThrow(() -> new RecursoNaoEncontradoException("ARQUIVO_DO_PEDIDO_NAO_ENCONTRADO",
                        "Este arquivo não está no pedido. Clique em Montar o pedido de novo."));
    }

    /** Os arquivos do último pedido montado, para o .zip. */
    public List<ArquivoDoPedido> arquivos(Usuario usuario, UUID atendimentoId) {
        List<ArquivoDoPedido> arquivos = arquivosDoUltimoPedido(doDono(usuario, atendimentoId));
        if (arquivos.isEmpty()) {
            throw new RegraNegocioException("PEDIDO_SEM_ARQUIVOS", "O pedido não tem arquivos para anexar (ou ainda não "
                    + "foi montado).");
        }
        return arquivos;
    }

    /** Escreve os arquivos num .zip (sem compactar de novo o que já é compactado: o vídeo e o MP3 vão como estão). */
    public static void escreverZip(List<ArquivoDoPedido> arquivos, OutputStream saida) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(saida)) {
            for (ArquivoDoPedido arquivo : arquivos) {
                String extensao = arquivo.nome().substring(arquivo.nome().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                zip.setLevel(JA_COMPACTADOS.contains(extensao) ? java.util.zip.Deflater.NO_COMPRESSION
                        : java.util.zip.Deflater.DEFAULT_COMPRESSION);
                zip.putNextEntry(new ZipEntry(arquivo.nome()));
                Files.copy(arquivo.local(), zip);
                zip.closeEntry();
            }
        }
    }

    // --------------------------------------------------------------------------------------------- a resposta

    /**
     * A resposta colada do app vira o texto: preenche a versão que estava esperando (o atendimento pausado pela cota,
     * por exemplo) ou cria a seguinte.
     *
     * @return o id do texto
     */
    public UUID usarResposta(Usuario usuario, UUID atendimentoId, TipoDeSaida tipo, String resposta) {
        AtendimentoGuardado atendimento = doDono(usuario, atendimentoId);
        PromptsDaRedacao.Prompt prompt = promptDo(tipo);
        LinhaDoTempo linha = atendimentos.linhaDoTempo(atendimentoId).orElseThrow(() -> new RegraNegocioException(
                "PEDIDO_NAO_MONTADO", "Monte o pedido primeiro: a resposta usa os identificadores dele (M1, A3...)."));
        JsonNode lido;
        try {
            lido = gemini.lerObjeto(jsonDaResposta(resposta), prompt.obrigatorios(), prompt.listas());
        } catch (FalhaDoModelo falha) {
            throw foraDoFormato(falha.getMessage());
        }
        String contato = atendimento.resumo().contato();
        List<ConferenciaDoTexto.ImagemDisponivel> imagens = new ArrayList<>(imagensDoApp(atendimento, linha));
        imagens.addAll(quadrosCitados(atendimento, linha, lido));
        ConferenciaDoTexto.Conferido conferido;
        try {
            conferido = redigir.conferir(tipo, contato, linha, imagens, lido);
        } catch (FalhaDoModelo falha) {
            throw foraDoFormato(falha.getMessage());
        }
        TextoRedigido texto = conferido.texto().comMaisAvisos(foraDoPedido(atendimento, linha));
        if (conferido.assuntoLongo()) {
            texto = ConferenciaDoTexto.comAssunto(texto, contato, conferido.assuntoDoModelo());
        }
        if (texto.blocos().isEmpty()) {
            throw new RegraNegocioException("RESPOSTA_SEM_TEXTO", "A resposta não tem nenhum trecho de texto. Confira se "
                    + "colou a resposta inteira do Gemini.");
        }
        String html = MontagemDoHtml.montar(texto.blocos(), ModeloDoTexto.de(tipo));
        String estrutura = EstruturaDaSaida.escrever(json, texto);
        TextoRedigido gravado = texto;
        UUID saidaId = transacao.execute(status -> {
            UUID id = saidaParaPreencher(atendimentoId, tipo);
            if (!saidas.salvarRedacao(id, estrutura, html, gravado.assunto(), MODELO_DO_APP, prompt.versao(), 0, 0,
                    conferido.imagens())) {
                throw mudouNoMeio();
            }
            return id;
        });
        log.info("Atendimento {}: {} vindo do app do Gemini gravado ({} trechos, {} imagens, {} avisos)", atendimentoId,
                tipo.codigo(), texto.blocos().size(), conferido.imagens().size(), texto.avisos().size());
        return saidaId;
    }

    /** A versão que esperava a redação (pendente) recebe o texto; senão, uma versão nova. */
    private UUID saidaParaPreencher(UUID atendimentoId, TipoDeSaida tipo) {
        Optional<SaidaGuardada> ultima = saidas.ultimas(atendimentoId).stream().filter(s -> s.tipo() == tipo).findFirst();
        if (ultima.isPresent() && ultima.get().situacao() == SituacaoDaSaida.PENDENTE) {
            return ultima.get().id();
        }
        if (ultima.isPresent() && ultima.get().situacao() == SituacaoDaSaida.REDIGINDO) {
            throw new ConflitoException("TEXTO_SENDO_ESCRITO", "Este texto está sendo escrito pela API do Gemini agora. "
                    + "Espere terminar (ou falhar) e cole a resposta de novo.");
        }
        return saidas.criar(atendimentoId, tipo, EnumSet.of(SituacaoDaSaida.PRONTA, SituacaoDaSaida.FALHOU,
                SituacaoDaSaida.CONFIRMADA), clock.instant()).orElseThrow(PeloAppDoGemini::mudouNoMeio);
    }

    /** O JSON dentro do que foi colado: o bloco de código, ou do primeiro "{" ao último "}". */
    static String jsonDaResposta(String colado) {
        String texto = colado == null ? "" : colado.strip();
        if (texto.isEmpty()) {
            throw new RegraNegocioException("RESPOSTA_VAZIA", "Cole a resposta do Gemini no campo.");
        }
        Matcher bloco = BLOCO_JSON.matcher(texto);
        if (bloco.find()) {
            return bloco.group(1);
        }
        int inicio = texto.indexOf('{');
        int fim = texto.lastIndexOf('}');
        if (inicio < 0 || fim <= inicio) {
            throw foraDoFormato("não há JSON");
        }
        return texto.substring(inicio, fim + 1);
    }

    private static RegraNegocioException foraDoFormato(String detalhe) {
        return new RegraNegocioException("RESPOSTA_FORA_DO_FORMATO", "A resposta colada não está no formato pedido ("
                + detalhe + "). No Gemini, peça: \"Responda só com o bloco de código JSON no formato pedido\" e cole a "
                + "nova resposta inteira.");
    }

    private static ConflitoException mudouNoMeio() {
        return new ConflitoException("TEXTO_MUDOU", "Este texto mudou enquanto a resposta era gravada (outra janela ou a "
                + "API). Recarregue a tela e cole de novo.");
    }

    /** Os quadros V1@02:15 que a resposta cita: tirados do vídeo agora (sem o ffmpeg, a conferência tira a imagem). */
    private List<ConferenciaDoTexto.ImagemDisponivel> quadrosCitados(AtendimentoGuardado atendimento, LinhaDoTempo linha,
                                                                     JsonNode lido) {
        Set<String> citadas = new LinkedHashSet<>();
        lido.findValues("imagens").forEach(lista -> lista.forEach(i -> citadas.add(i.path("id").asText("").strip())));
        Map<UUID, ArquivoDoAtendimento> arquivos = porId(atendimento);
        List<ConferenciaDoTexto.ImagemDisponivel> quadros = new ArrayList<>();
        for (String id : citadas) {
            Matcher m = QUADRO.matcher(id);
            if (!m.matches()) {
                continue;
            }
            Optional<ItemDaLinha> video = linha.todos().filter(i -> i.arquivo() && i.id().equals(m.group(1))
                    && "video".equals(i.categoria())).findFirst();
            Optional<Long> instante = InstanteDoVideo.lerMs(m.group(2));
            if (video.isEmpty() || instante.isEmpty()) {
                continue;
            }
            ArquivoDoAtendimento arquivo = arquivos.get(video.get().arquivoId());
            Path local = arquivo == null ? null : noServidor(arquivo);
            if (local == null) {
                continue;
            }
            String caminho = pasta.caminhoDoQuadro(atendimento.resumo().id(), arquivo.id(), instante.get());
            try {
                if (!Files.isRegularFile(pasta.localDoArquivo(caminho))) {
                    extrator.extrair(local, instante.get(), pasta.localDoArquivo(caminho));
                }
                quadros.add(new ConferenciaDoTexto.ImagemDisponivel(id, arquivo.id(), caminho,
                        "quadro do vídeo em " + InstanteDoVideo.texto(instante.get()), instante.get()));
            } catch (FalhaNoQuadro falha) {
                log.info("Quadro {} citado pelo app do Gemini não saiu: {}", id, falha.getMessage());
            }
        }
        return quadros;
    }

    /** Os arquivos que não foram no pedido (não baixados, não suportados): o texto foi escrito sem eles. */
    private List<String> foraDoPedido(AtendimentoGuardado atendimento, LinhaDoTempo linha) {
        Map<UUID, ArquivoDoAtendimento> arquivos = porId(atendimento);
        List<String> avisos = new ArrayList<>();
        linha.todos().filter(ItemDaLinha::arquivo).forEach(i -> {
            ArquivoDoAtendimento arquivo = i.arquivoId() == null ? null : arquivos.get(i.arquivoId());
            if (arquivo == null || noServidor(arquivo) == null || !vaiNoPedido(i)) {
                avisos.add("Escrito sem o conteúdo " + RedigirSaida.doArquivo(i.categoria(), i.nome())
                        + " (não foi no pedido ao app do Gemini): confira se faltou alguma coisa.");
            }
        });
        return avisos;
    }

    // --------------------------------------------------------------------------------------------- os arquivos

    /** O pedido montado: os arquivos, onde está cada item da linha e o que avisar. */
    record Pacote(List<ArquivoDoPedido> arquivos, Map<String, String> ondeEsta, List<String> avisos, long audioMs,
                  long videoMs) {
    }

    private Pacote montarPacote(AtendimentoGuardado atendimento, LinhaDoTempo linha) {
        UUID id = atendimento.resumo().id();
        pasta.apagarPedidoDoApp(id);
        Map<UUID, ArquivoDoAtendimento> arquivos = porId(atendimento);
        List<ArquivoDoPedido> doPedido = new ArrayList<>();
        Map<String, String> ondeEsta = new LinkedHashMap<>();
        List<String> avisos = new ArrayList<>();
        List<ItemDaLinha> doPdf = new ArrayList<>();
        List<JuntadorDePdf.Item> itensDoPdf = new ArrayList<>();
        List<ItemDaLinha> audios = new ArrayList<>();
        List<ItemDaLinha> separados = new ArrayList<>();
        long videoMs = 0;
        List<ItemDaLinha> naOrdem = linha.todos().filter(ItemDaLinha::arquivo).toList();
        for (ItemDaLinha item : naOrdem) {
            ArquivoDoAtendimento arquivo = item.arquivoId() == null ? null : arquivos.get(item.arquivoId());
            Path local = arquivo == null ? null : noServidor(arquivo);
            if (local == null) {
                avisos.add(item.id() + " (" + nomeOuArquivo(item) + ") não está no servidor"
                        + (arquivo != null && arquivo.erroMensagem() != null ? ": " + arquivo.erroMensagem() : "")
                        + ". O texto sai sem ele.");
                continue;
            }
            if (!vaiNoPedido(item)) {
                avisos.add(item.id() + " (" + nomeOuArquivo(item) + ") é de um tipo que o Gemini não lê e não vai no pedido.");
                continue;
            }
            switch (item.categoria()) {
                case "imagem" -> {
                    Path paraOPdf = IMAGENS_DO_PDF.contains(extensao(local)) ? local : convertidaParaPng(id, item, local);
                    if (paraOPdf != null) {
                        doPdf.add(item);
                        itensDoPdf.add(new JuntadorDePdf.Item(rotulo(item), paraOPdf, false));
                    } else {
                        separados.add(item);
                    }
                }
                case "documento" -> {
                    if ("pdf".equals(extensao(local))) {
                        doPdf.add(item);
                        itensDoPdf.add(new JuntadorDePdf.Item(rotulo(item), local, true));
                    } else {
                        separados.add(item);
                    }
                }
                case "audio" -> audios.add(item);
                case "video" -> {
                    separados.add(item);
                    videoMs += conversor.duracaoMs(local).orElse(0L);
                }
                default -> separados.add(item);
            }
        }

        // as imagens e os documentos num PDF só, cada um com o identificador no alto
        if (!itensDoPdf.isEmpty()) {
            Path destino = pasta.localDoArquivo(pasta.caminhoNoPedidoDoApp(id, PDF));
            JuntadorDePdf.Resultado resultado = juntador.juntar(itensDoPdf, destino);
            for (int i = 0; i < doPdf.size(); i++) {
                int pagina = resultado.paginas().get(i);
                ItemDaLinha item = doPdf.get(i);
                if (pagina > 0) {
                    ondeEsta.put(item.id(), "documento".equals(item.categoria())
                            ? "no arquivo " + PDF + ", a partir da página " + pagina
                            : "no arquivo " + PDF + ", página " + pagina);
                } else {
                    separados.add(item);
                }
            }
            if (resultado.total() > 0) {
                doPedido.add(arquivoDoPedido(PDF, destino, "as imagens e os documentos, um por página, com o "
                        + "identificador no alto (" + String.join(", ", doPdf.stream().map(ItemDaLinha::id)
                        .filter(ondeEsta::containsKey).toList()) + ")"));
            }
        }

        // os áudios um depois do outro, com o mapa de tempos
        long audioMs = 0;
        if (!audios.isEmpty() && conversor.disponivel()) {
            try {
                audioMs = juntarAudios(id, audios, arquivos, doPedido, ondeEsta, separados);
            } catch (IOException e) {
                log.warn("Atendimento {}: não foi possível juntar os áudios ({}); vão um por um", id, e.getClass().getSimpleName());
                separados.addAll(audios.stream().filter(a -> !ondeEsta.containsKey(a.id())).toList());
            }
        } else {
            separados.addAll(audios);
        }

        // o resto, cada um com o identificador no nome, na ordem da conversa (a mesma da lista depois)
        separados.sort(java.util.Comparator.comparingInt(naOrdem::indexOf));
        for (ItemDaLinha item : separados) {
            Path local = noServidor(arquivos.get(item.arquivoId()));
            String nome = item.id() + "_" + nomeComExtensaoDoApp(item, local);
            doPedido.add(new ArquivoDoPedido(nome, local, tamanho(local), descricao(item)));
            ondeEsta.put(item.id(), "no arquivo " + nome);
            if ("audio".equals(item.categoria())) {
                audioMs += conversor.duracaoMs(local).orElse(0L);
            }
        }

        if (doPedido.size() > ARQUIVOS_POR_PEDIDO) {
            avisos.add("São " + doPedido.size() + " arquivos e o app do Gemini aceita até " + ARQUIVOS_POR_PEDIDO
                    + " por pedido: anexe os " + ARQUIVOS_POR_PEDIDO + " primeiros, mande, e anexe o resto na mensagem "
                    + "seguinte pedindo para esperar antes de responder.");
        }
        if (audioMs > AUDIO_MAXIMO_MS) {
            avisos.add("Os áudios somam " + duracao(audioMs) + ": no app do Gemini sem assinatura o limite é de 10 min "
                    + "de áudio por pedido (no Google AI Pro, 3 h).");
        }
        if (videoMs > VIDEO_MAXIMO_MS) {
            avisos.add("Os vídeos somam " + duracao(videoMs) + ": no app do Gemini sem assinatura o limite é de 5 min "
                    + "de vídeo por pedido (no Google AI Pro, 1 h).");
        }
        doPedido.stream().filter(a -> a.tamanho() > ARQUIVO_MAXIMO && !a.nome().toLowerCase(Locale.ROOT).endsWith(".mp4"))
                .forEach(a -> avisos.add(a.nome() + " passa de 100 MB, o limite do app do Gemini por arquivo."));
        if (!audios.isEmpty() && !conversor.disponivel()) {
            avisos.add("O ffmpeg não está instalado: os áudios vão um por um (contam no limite de arquivos) e o app pode "
                    + "não abrir o formato .oga do WhatsApp.");
        }
        return new Pacote(List.copyOf(doPedido), ondeEsta, List.copyOf(avisos), audioMs, videoMs);
    }

    /** Converte cada áudio para WAV, junta com 1 s entre eles e codifica em MP3 (ou fica o WAV). */
    private long juntarAudios(UUID id, List<ItemDaLinha> audios, Map<UUID, ArquivoDoAtendimento> arquivos,
                              List<ArquivoDoPedido> doPedido, Map<String, String> ondeEsta, List<ItemDaLinha> separados)
            throws IOException {
        List<ItemDaLinha> convertidos = new ArrayList<>();
        List<Path> wavs = new ArrayList<>();
        for (ItemDaLinha item : audios) {
            Path wav = pasta.localDoArquivo(pasta.caminhoNoPedidoDoApp(id, ".audio-" + item.id() + ".wav"));
            try {
                conversor.paraWav(noServidor(arquivos.get(item.arquivoId())), wav);
                convertidos.add(item);
                wavs.add(wav);
            } catch (FalhaNoQuadro falha) {
                separados.add(item); // vai como está
            }
        }
        if (wavs.isEmpty()) {
            return 0;
        }
        Path junto = pasta.localDoArquivo(pasta.caminhoNoPedidoDoApp(id, AUDIOS + ".wav"));
        List<JuncaoDeAudios.Trecho> trechos = JuncaoDeAudios.juntar(wavs, junto);
        for (Path wav : wavs) {
            Files.deleteIfExists(wav);
        }
        Path mp3 = pasta.localDoArquivo(pasta.caminhoNoPedidoDoApp(id, AUDIOS + ".mp3"));
        Path final_ = junto;
        if (conversor.paraMp3(junto, mp3)) {
            Files.deleteIfExists(junto);
            final_ = mp3;
        }
        String nome = final_.getFileName().toString();
        List<String> mapa = new ArrayList<>();
        for (int i = 0; i < convertidos.size(); i++) {
            String trecho = "de " + InstanteDoVideo.texto(trechos.get(i).inicioMs()) + " a "
                    + InstanteDoVideo.texto(trechos.get(i).fimMs());
            ondeEsta.put(convertidos.get(i).id(), "no arquivo " + nome + ", " + trecho);
            mapa.add(convertidos.get(i).id() + " " + trecho);
        }
        doPedido.add(arquivoDoPedido(nome, final_, "os áudios, um depois do outro com 1 s de silêncio entre eles ("
                + String.join("; ", mapa) + ")"));
        return trechos.get(trechos.size() - 1).fimMs();
    }

    /** WebP, HEIC e outros que o PDF não aceita: o ffmpeg tira um PNG (sem ele, a imagem vai à parte). */
    private Path convertidaParaPng(UUID id, ItemDaLinha item, Path local) {
        if (!extrator.situacao().disponivel()) {
            return null;
        }
        Path png = pasta.localDoArquivo(pasta.caminhoNoPedidoDoApp(id, ".imagem-" + item.id() + ".png"));
        try {
            extrator.extrair(local, 0, png);
            return png;
        } catch (FalhaNoQuadro falha) {
            return null;
        }
    }

    /** Os arquivos do último pedido que ainda estão no disco, na mesma ordem e com os mesmos nomes. */
    private List<ArquivoDoPedido> arquivosDoUltimoPedido(AtendimentoGuardado atendimento) {
        UUID id = atendimento.resumo().id();
        Optional<LinhaDoTempo> linha = atendimentos.linhaDoTempo(id);
        if (linha.isEmpty()) {
            return List.of();
        }
        Map<UUID, ArquivoDoAtendimento> arquivos = porId(atendimento);
        List<ArquivoDoPedido> lista = new ArrayList<>();
        for (String montado : List.of(PDF, AUDIOS + ".mp3", AUDIOS + ".wav")) {
            Path local = pasta.localDoArquivo(pasta.caminhoNoPedidoDoApp(id, montado));
            if (Files.isRegularFile(local)) {
                lista.add(new ArquivoDoPedido(montado, local, tamanho(local), ""));
            }
        }
        Set<String> noPdfOuNosAudios = ondeEstaNoDisco(id);
        linha.get().todos().filter(ItemDaLinha::arquivo).forEach(item -> {
            ArquivoDoAtendimento arquivo = item.arquivoId() == null ? null : arquivos.get(item.arquivoId());
            Path local = arquivo == null ? null : noServidor(arquivo);
            if (local != null && vaiNoPedido(item) && !noPdfOuNosAudios.contains(item.id())) {
                lista.add(new ArquivoDoPedido(item.id() + "_" + nomeComExtensaoDoApp(item, local), local, tamanho(local), ""));
            }
        });
        return lista;
    }

    /**
     * Quais itens foram para o PDF ou para o áudio junto no último pedido (guardado num arquivo pequeno ao lado, para
     * os downloads saberem o que vai à parte sem montar tudo de novo).
     */
    private Set<String> ondeEstaNoDisco(UUID id) {
        Path indice = pasta.localDoArquivo(pasta.caminhoNoPedidoDoApp(id, "conteudo.json"));
        if (!Files.isRegularFile(indice)) {
            return Set.of();
        }
        try {
            Set<String> ids = new LinkedHashSet<>();
            json.readTree(indice.toFile()).forEach(i -> ids.add(i.asText()));
            return ids;
        } catch (IOException e) {
            return Set.of();
        }
    }

    private void guardarOndeEsta(UUID id, Pacote pacote) {
        List<String> juntos = pacote.ondeEsta().entrySet().stream()
                .filter(e -> e.getValue().startsWith("no arquivo " + PDF) || e.getValue().startsWith("no arquivo " + AUDIOS + "."))
                .map(Entry::getKey).toList();
        try {
            Path indice = pasta.localDoArquivo(pasta.caminhoNoPedidoDoApp(id, "conteudo.json"));
            Files.createDirectories(indice.getParent());
            Files.writeString(indice, json.writeValueAsString(juntos));
        } catch (IOException e) {
            log.warn("Atendimento {}: índice do pedido ao app não gravado ({})", id, e.getClass().getSimpleName());
        }
    }

    // --------------------------------------------------------------------------------------------- o texto do pedido

    /** O formato da resposta e o que está em cada arquivo anexado. */
    private String comoResponder(Pacote pacote, PromptsDaRedacao.Prompt prompt) {
        StringBuilder t = new StringBuilder("Como responder, aqui no app do Gemini:\n");
        if (pacote.arquivos().isEmpty()) {
            t.append("- Este atendimento não tem arquivos anexados: o material é só o que está abaixo.\n");
        } else {
            t.append("- Os arquivos do atendimento estão anexados a este pedido:\n");
            for (ArquivoDoPedido arquivo : pacote.arquivos()) {
                t.append("  - ").append(arquivo.nome()).append(": ").append(arquivo.conteudo()).append(";\n");
            }
            t.append("  Leia e ouça todos antes de escrever: o conteúdo deles faz parte do material tanto quanto as "
                    + "mensagens, e cada item do material diz em que arquivo ele está. Arquivo que não abrir: diga isso "
                    + "no texto, no ponto em que ele entrou.\n");
        }
        String esquema;
        try {
            esquema = json.writeValueAsString(json.readTree(prompt.esquema()));
        } catch (JsonProcessingException e) {
            esquema = prompt.esquema();
        }
        t.append("- Responda só com um bloco de código JSON, sem nada antes nem depois, seguindo este esquema (JSON Schema):\n")
                .append("```json\n").append(esquema).append("\n```");
        return t.toString();
    }

    /** A linha do tempo com o lugar de cada arquivo no pedido no lugar do conteúdo (a análise, se houver, vai junto). */
    private static LinhaDoTempo comOndeEsta(LinhaDoTempo linha, Map<String, String> ondeEsta) {
        return new LinhaDoTempo(linha.versao(), linha.montadaEm(), comOndeEsta(linha.itens(), ondeEsta),
                comOndeEsta(linha.semHorario(), ondeEsta));
    }

    private static List<ItemDaLinha> comOndeEsta(List<ItemDaLinha> itens, Map<String, String> ondeEsta) {
        return itens.stream().map(i -> {
            if (!i.arquivo() || !ondeEsta.containsKey(i.id())) {
                return i;
            }
            String texto = "(o conteúdo está " + ondeEsta.get(i.id()) + ")";
            if ("analisado".equals(i.situacao()) && !i.texto().isBlank()) {
                texto += "\nAnálise feita antes pelo sistema (confira no arquivo):\n" + i.texto().strip();
            }
            return new ItemDaLinha(i.id(), i.tipo(), i.momento(), i.lado(), i.remetente(), texto, i.arquivoId(), i.origem(),
                    i.categoria(), i.nome(), i.mensagem(), i.situacao(), i.resumo());
        }).toList();
    }

    /** As imagens que o texto pode citar: as do atendimento que estão no servidor (no PDF ou à parte). */
    private List<ConferenciaDoTexto.ImagemDisponivel> imagensDoApp(AtendimentoGuardado atendimento, LinhaDoTempo linha) {
        Map<UUID, ArquivoDoAtendimento> arquivos = porId(atendimento);
        List<ConferenciaDoTexto.ImagemDisponivel> imagens = new ArrayList<>();
        linha.todos().filter(i -> i.arquivo() && "imagem".equals(i.categoria()) && i.arquivoId() != null).forEach(i -> {
            ArquivoDoAtendimento arquivo = arquivos.get(i.arquivoId());
            if (arquivo != null && noServidor(arquivo) != null) {
                imagens.add(new ConferenciaDoTexto.ImagemDisponivel(i.id(), arquivo.id(), arquivo.caminho(),
                        i.resumo() != null ? i.resumo() : "imagem " + nomeOuArquivo(i)));
            }
        });
        return imagens;
    }

    private String rotulo(ItemDaLinha item) {
        StringBuilder r = new StringBuilder(item.id()).append(" · ").append(descricao(item));
        if (item.momento() != null) {
            r.append(" ").append(DIA_E_HORA.format(item.momento().atZone(clock.getZone())));
        }
        return r.append(" · ").append(nomeOuArquivo(item)).toString();
    }

    private static String descricao(ItemDaLinha item) {
        String tipo = switch (item.categoria() == null ? "" : item.categoria()) {
            case "imagem" -> "imagem";
            case "audio" -> "áudio";
            case "video" -> "vídeo";
            case "documento" -> "documento";
            default -> "arquivo";
        };
        String quem = "cliente".equals(item.lado()) ? " do cliente" : "atendente".equals(item.lado()) ? " do atendente" : "";
        return switch (item.origem() == null ? "" : item.origem()) {
            case MaterialDoArquivo.LIGACAO -> "gravação de ligação";
            case MaterialDoArquivo.VIDEO -> "vídeo de reprodução";
            case MaterialDoArquivo.PRINT -> "print do atendente";
            default -> tipo + quem;
        };
    }

    // --------------------------------------------------------------------------------------------- apoio

    private AtendimentoGuardado doDono(Usuario usuario, UUID atendimentoId) {
        return atendimentos.buscar(atendimentoId, usuario.id()).orElseThrow(() -> new RecursoNaoEncontradoException(
                "ATENDIMENTO_NAO_ENCONTRADO", "Atendimento não encontrado. Ele pode ter sido apagado."));
    }

    private static PromptsDaRedacao.Prompt promptDo(TipoDeSaida tipo) {
        return PromptsDaRedacao.para(tipo).orElseThrow(() -> new RegraNegocioException("TIPO_INDISPONIVEL",
                "Este tipo de texto ainda não existe nesta versão do sistema."));
    }

    private static Map<UUID, ArquivoDoAtendimento> porId(AtendimentoGuardado atendimento) {
        Map<UUID, ArquivoDoAtendimento> mapa = new LinkedHashMap<>();
        atendimento.arquivos().forEach(a -> mapa.put(a.id(), a));
        return mapa;
    }

    /** O arquivo no disco, ou null (não baixado, apagado pela retenção, tirado). */
    private Path noServidor(ArquivoDoAtendimento arquivo) {
        if (arquivo == null || arquivo.caminho() == null || !SituacaoDoArquivo.PRONTO.codigo().equals(arquivo.situacao())) {
            return null;
        }
        Path local = pasta.localDoArquivo(arquivo.caminho());
        return Files.isRegularFile(local) ? local : null;
    }

    private static boolean vaiNoPedido(ItemDaLinha item) {
        return Set.of("imagem", "audio", "video", "documento").contains(item.categoria() == null ? "" : item.categoria());
    }

    private static String nomeOuArquivo(ItemDaLinha item) {
        return item.nome() == null || item.nome().isBlank() ? "arquivo" : item.nome();
    }

    /** O nome original (saneado no disco) com a extensão que o app reconhece (.oga do WhatsApp vira .ogg). */
    private static String nomeComExtensaoDoApp(ItemDaLinha item, Path local) {
        String nome = item.nome() == null || item.nome().isBlank() ? local.getFileName().toString() : item.nome();
        nome = nome.replaceAll("[\\\\/:*?\"<>|]", "_");
        return nome.toLowerCase(Locale.ROOT).endsWith(".oga") ? nome.substring(0, nome.length() - 4) + ".ogg" : nome;
    }

    private ArquivoDoPedido arquivoDoPedido(String nome, Path local, String conteudo) {
        return new ArquivoDoPedido(nome, local, tamanho(local), conteudo);
    }

    private static String extensao(Path arquivo) {
        String nome = arquivo.getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        return ponto < 0 ? "" : nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
    }

    private static long tamanho(Path arquivo) {
        try {
            return Files.size(arquivo);
        } catch (IOException e) {
            return 0;
        }
    }

    static String duracao(long ms) {
        long segundos = ms / 1000;
        return segundos >= 60 ? (segundos / 60) + " min " + (segundos % 60) + " s" : segundos + " s";
    }
}
