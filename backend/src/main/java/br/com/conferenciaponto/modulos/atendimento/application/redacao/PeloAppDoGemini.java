package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.application.ia.ArquivosDePrompt;
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
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TextoDoGem;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TextoRedigido;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.video.ConversorDeMidia;
import br.com.conferenciaponto.modulos.atendimento.domain.video.ExtratorQuadros;
import br.com.conferenciaponto.modulos.atendimento.domain.video.FalhaNoQuadro;
import br.com.conferenciaponto.modulos.atendimento.domain.video.InstanteDoVideo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O texto pelo Gem do Gemini (gemini.google.com), sem usar a cota da API. A pessoa cria uma vez um Gem com as
 * instruções do sistema; em cada atendimento, baixa o pacote — um PDF com a conversa (cada item com o identificador e
 * o lugar do conteúdo) e, depois dela, as imagens e os documentos, um MP3 com os áudios juntos e os vídeos à parte —,
 * arrasta no Gem e escreve "resumo", "erro" ou "alteração". O Gem responde em texto pronto para o chamado. Se a pessoa
 * colar a resposta de volta, ela passa pela mesma conferência da API (imagens numeradas, assunto, dados de acesso
 * remoto) e vira uma versão do texto, como as outras (edição, cópia, base de conhecimento).
 */
@Service
public class PeloAppDoGemini {

    private static final Logger log = LoggerFactory.getLogger(PeloAppDoGemini.class);

    /** O "modelo" gravado no texto que veio do Gem (texto) ou do app (JSON). */
    public static final String MODELO_DO_GEM = "Gem do Gemini (colado)";
    public static final String MODELO_DO_APP = "app do Gemini (colado)";
    static final String AUDIOS = "Audios";
    private static final String INDICE = "conteudo.json";
    /** Os limites do app do Gemini no plano sem assinatura (ajuda do Google, outubro de 2026). */
    static final int ARQUIVOS_POR_PEDIDO = 10;
    static final long AUDIO_MAXIMO_MS = 10 * 60_000L;
    static final long VIDEO_MAXIMO_MS = 5 * 60_000L;
    static final long ARQUIVO_MAXIMO = 100L * 1024 * 1024;
    /** As instruções do Gem: mudar o texto é criar a versão seguinte (a tela avisa quem tem a anterior). */
    static final InstrucoesDoGem INSTRUCOES = new InstrucoesDoGem(1, ArquivosDePrompt.prompt("gem", 1));

    private static final Set<String> IMAGENS_DO_PDF = Set.of("jpg", "jpeg", "png", "gif", "bmp");
    private static final DateTimeFormatter DIA_E_HORA = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm");
    private static final Pattern BLOCO_JSON = Pattern.compile("```(?:json|JSON)?\\s*(\\{.*})\\s*```", Pattern.DOTALL);
    private static final Pattern QUADRO = Pattern.compile("^([A-Za-z]+\\d+)@(\\d{1,3}:\\d{2}(?::\\d{2})?)$");

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

    /** O texto para colar nas instruções do Gem, com a versão (a tela avisa quando ela muda). */
    public record InstrucoesDoGem(int versao, String texto) {
    }

    /** Um arquivo do pacote: o nome para anexar, onde está no servidor e o que tem dentro. */
    public record ArquivoDoPacote(String nome, Path local, long tamanho, String conteudo) {
    }

    /** O que a tela mostra: os arquivos para baixar e anexar no Gem e o que conferir antes. */
    public record PacoteView(List<Arquivo> arquivos, List<String> avisos, long audioMs, long videoMs,
                             int versaoDasInstrucoes) {

        public record Arquivo(String nome, long tamanho, String conteudo) {
        }
    }

    public InstrucoesDoGem instrucoesDoGem() {
        return INSTRUCOES;
    }

    // --------------------------------------------------------------------------------------------- o pacote

    /**
     * Monta o pacote para o Gem: a linha do tempo de agora (guardada, para a resposta usar os mesmos identificadores)
     * e os arquivos, de novo a cada vez.
     */
    public PacoteView pacote(Usuario usuario, UUID atendimentoId) {
        AtendimentoGuardado atendimento = doDono(usuario, atendimentoId);
        LinhaDoTempo linha = linhaDoTempo.montar(atendimento);
        Pacote pacote = montarPacote(atendimento, linha);
        guardarIndice(atendimentoId, pacote);
        log.info("Atendimento {}: pacote para o Gem do Gemini montado ({} arquivo(s), {} aviso(s))", atendimentoId,
                pacote.arquivos().size(), pacote.avisos().size());
        return new PacoteView(pacote.arquivos().stream()
                .map(a -> new PacoteView.Arquivo(a.nome(), a.tamanho(), a.conteudo())).toList(), pacote.avisos(),
                pacote.audioMs(), pacote.videoMs(), INSTRUCOES.versao());
    }

    /** Um arquivo do último pacote montado. */
    public ArquivoDoPacote arquivo(Usuario usuario, UUID atendimentoId, String nome) {
        AtendimentoGuardado atendimento = doDono(usuario, atendimentoId);
        return arquivosDoUltimoPacote(atendimento).stream().filter(a -> a.nome().equals(nome)).findFirst()
                .orElseThrow(() -> new RecursoNaoEncontradoException("ARQUIVO_DO_PACOTE_NAO_ENCONTRADO",
                        "Este arquivo não está no pacote. Clique em Baixar os arquivos de novo."));
    }

    // --------------------------------------------------------------------------------------------- a resposta

    /**
     * A resposta colada vira o texto: o texto do Gem (o formato das instruções) ou, se vier, o JSON do esquema da API.
     * Preenche a versão que estava esperando (o atendimento pausado pela cota, por exemplo) ou cria a seguinte.
     *
     * @return o id do texto
     */
    public UUID usarResposta(Usuario usuario, UUID atendimentoId, TipoDeSaida tipo, String resposta) {
        AtendimentoGuardado atendimento = doDono(usuario, atendimentoId);
        PromptsDaRedacao.Prompt prompt = promptDo(tipo);
        LinhaDoTempo linha = atendimentos.linhaDoTempo(atendimentoId).orElseThrow(() -> new RegraNegocioException(
                "PACOTE_NAO_MONTADO", "Baixe os arquivos do pacote primeiro: a resposta usa os identificadores dele "
                + "(M1, A3...)."));
        String colado = resposta == null ? "" : resposta.strip();
        if (colado.isEmpty()) {
            throw new RegraNegocioException("RESPOSTA_VAZIA", "Cole a resposta do Gemini no campo.");
        }
        Optional<String> emJson = jsonDaResposta(colado);
        ModeloDoTexto modelo = ModeloDoTexto.de(tipo);
        ConferenciaDoTexto.Bruto bruto;
        if (emJson.isPresent()) {
            try {
                JsonNode lido = gemini.lerObjeto(emJson.get(), prompt.obrigatorios(), prompt.listas());
                bruto = RedigirSaida.bruto(lido, modelo);
            } catch (FalhaDoModelo falha) {
                throw foraDoFormato(falha.getMessage());
            }
        } else {
            bruto = TextoDoGem.ler(colado, modelo);
        }
        boolean doGem = emJson.isEmpty();
        String contato = atendimento.resumo().contato();
        List<ConferenciaDoTexto.ImagemDisponivel> imagens = new ArrayList<>(imagensDoPacote(atendimento, linha));
        imagens.addAll(quadrosCitados(atendimento, linha, bruto));
        ConferenciaDoTexto.Conferido conferido;
        try {
            conferido = redigir.conferir(tipo, contato, linha, imagens, bruto);
        } catch (FalhaDoModelo falha) {
            throw foraDoFormato(falha.getMessage());
        }
        TextoRedigido texto = conferido.texto();
        if (doGem) {
            // o texto do Gem vai direto para o chamado e não traz as fontes de cada trecho: não é um aviso
            texto = texto.comAssunto(texto.assunto(), texto.avisos().stream().filter(a -> !a.contains("sem fonte")).toList());
            if (bruto.assunto().isBlank()) {
                texto = texto.comMaisAvisos(List.of("A resposta não tinha a linha \"Assunto:\": escreva o assunto acima."));
            }
        }
        texto = texto.comMaisAvisos(foraDoPacote(atendimento, linha));
        if (conferido.assuntoLongo()) {
            texto = ConferenciaDoTexto.comAssunto(texto, contato, conferido.assuntoDoModelo());
        }
        if (texto.blocos().stream().allMatch(b -> b.texto().isBlank())) {
            throw new RegraNegocioException("RESPOSTA_SEM_TEXTO", "A resposta não tem nenhum trecho de texto. Confira se "
                    + "colou a resposta inteira do Gemini.");
        }
        String html = MontagemDoHtml.montar(texto.blocos(), modelo);
        String estrutura = EstruturaDaSaida.escrever(json, texto);
        TextoRedigido gravado = texto;
        String modeloGravado = doGem ? MODELO_DO_GEM : MODELO_DO_APP;
        int versaoDoPrompt = doGem ? INSTRUCOES.versao() : prompt.versao();
        UUID saidaId = transacao.execute(status -> {
            UUID id = saidaParaPreencher(atendimentoId, tipo);
            if (!saidas.salvarRedacao(id, estrutura, html, gravado.assunto(), modeloGravado, versaoDoPrompt, 0, 0,
                    conferido.imagens())) {
                throw mudouNoMeio();
            }
            return id;
        });
        log.info("Atendimento {}: {} vindo do {} gravado ({} trechos, {} imagens, {} avisos)", atendimentoId,
                tipo.codigo(), doGem ? "Gem" : "app do Gemini", texto.blocos().size(), conferido.imagens().size(),
                texto.avisos().size());
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

    /** O JSON, se o que foi colado é JSON (num bloco de código ou inteiro); texto comum é o formato do Gem. */
    static Optional<String> jsonDaResposta(String colado) {
        String texto = colado == null ? "" : colado.strip();
        Matcher bloco = BLOCO_JSON.matcher(texto);
        if (bloco.find()) {
            return Optional.of(bloco.group(1));
        }
        if (texto.startsWith("{") && texto.endsWith("}")) {
            return Optional.of(texto);
        }
        return Optional.empty();
    }

    private static RegraNegocioException foraDoFormato(String detalhe) {
        return new RegraNegocioException("RESPOSTA_FORA_DO_FORMATO", "A resposta colada veio em JSON, mas fora do "
                + "formato (" + detalhe + "). No Gemini, peça o texto no formato das instruções do Gem e cole a nova "
                + "resposta inteira.");
    }

    private static ConflitoException mudouNoMeio() {
        return new ConflitoException("TEXTO_MUDOU", "Este texto mudou enquanto a resposta era gravada (outra janela ou a "
                + "API). Recarregue a tela e cole de novo.");
    }

    /** Os momentos de vídeo (A4@01:15) que a resposta cita: tirados do vídeo agora (sem o ffmpeg, a imagem fica de fora). */
    private List<ConferenciaDoTexto.ImagemDisponivel> quadrosCitados(AtendimentoGuardado atendimento, LinhaDoTempo linha,
                                                                     ConferenciaDoTexto.Bruto bruto) {
        Set<String> citadas = new LinkedHashSet<>();
        bruto.blocos().forEach(b -> b.imagens().forEach(i -> citadas.add(i.id() == null ? "" : i.id().strip())));
        Map<UUID, ArquivoDoAtendimento> arquivos = porId(atendimento);
        List<ConferenciaDoTexto.ImagemDisponivel> quadros = new ArrayList<>();
        for (String id : citadas) {
            Matcher m = QUADRO.matcher(id);
            if (!m.matches()) {
                continue;
            }
            Optional<ItemDaLinha> video = linha.todos().filter(i -> i.arquivo() && i.id().equalsIgnoreCase(m.group(1))
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
                log.info("Quadro {} citado pelo Gemini não saiu: {}", id, falha.getMessage());
            }
        }
        return quadros;
    }

    /** Os arquivos que não foram no pacote (não baixados, não suportados): o texto foi escrito sem eles. */
    private List<String> foraDoPacote(AtendimentoGuardado atendimento, LinhaDoTempo linha) {
        Map<UUID, ArquivoDoAtendimento> arquivos = porId(atendimento);
        List<String> avisos = new ArrayList<>();
        linha.todos().filter(ItemDaLinha::arquivo).forEach(i -> {
            ArquivoDoAtendimento arquivo = i.arquivoId() == null ? null : arquivos.get(i.arquivoId());
            if (arquivo == null || noServidor(arquivo) == null || !vaiNoPacote(i)) {
                avisos.add("Escrito sem o conteúdo " + RedigirSaida.doArquivo(i.categoria(), i.nome())
                        + " (não foi no pacote para o Gemini): confira se faltou alguma coisa.");
            }
        });
        return avisos;
    }

    // --------------------------------------------------------------------------------------------- os arquivos

    /**
     * O pacote montado: os arquivos (o PDF primeiro), onde está o conteúdo de cada item da linha e o que avisar.
     *
     * @param pdf o nome do PDF da conversa
     */
    record Pacote(String pdf, List<ArquivoDoPacote> arquivos, Map<String, String> ondeEsta, List<String> avisos,
                  long audioMs, long videoMs) {
    }

    private Pacote montarPacote(AtendimentoGuardado atendimento, LinhaDoTempo linha) {
        UUID id = atendimento.resumo().id();
        pasta.apagarPedidoDoApp(id);
        Map<UUID, ArquivoDoAtendimento> arquivos = porId(atendimento);
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
            if (!vaiNoPacote(item)) {
                avisos.add(item.id() + " (" + nomeOuArquivo(item) + ") é de um tipo que o Gemini não lê e não vai no pacote.");
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

        // os áudios um depois do outro, com o mapa de tempos
        long audioMs = 0;
        ArquivoDoPacote audio = null;
        if (!audios.isEmpty() && conversor.disponivel()) {
            try {
                Audios juntos = juntarAudios(id, audios, arquivos, ondeEsta, separados);
                audio = juntos.arquivo();
                audioMs = juntos.ms();
            } catch (IOException e) {
                log.warn("Atendimento {}: não foi possível juntar os áudios ({}); vão um por um", id, e.getClass().getSimpleName());
                separados.addAll(audios.stream().filter(a -> !ondeEsta.containsKey(a.id())).toList());
            }
        } else {
            separados.addAll(audios);
        }

        // a conversa e, depois dela, as imagens e os documentos, cada um com o identificador no alto
        String pdf = nomeDoPdf(atendimento.resumo().chamado());
        Path destino = pasta.localDoArquivo(pasta.caminhoNoPedidoDoApp(id, pdf));
        JuntadorDePdf.Resultado resultado = juntador.juntar(paginas -> {
            Map<String, String> comPaginas = new LinkedHashMap<>(ondeEsta);
            nomearSeparados(separados, arquivos, comPaginas);
            for (int i = 0; i < doPdf.size(); i++) {
                ItemDaLinha item = doPdf.get(i);
                if (paginas.get(i) > 0) {
                    comPaginas.put(item.id(), noPdf(item, paginas.get(i)));
                } else {
                    // não abriu no PDF: vai à parte, com o nome que terá no pacote
                    Path local = noServidor(arquivos.get(item.arquivoId()));
                    comPaginas.put(item.id(), "no arquivo " + item.id() + "_" + nomeComExtensaoDoApp(item,
                            local != null ? local : itensDoPdf.get(i).arquivo()));
                }
            }
            return cabecalho(atendimento, pdf) + "\n\n" + MaterialDaRedacao.paraOGem(atendimento.resumo().chamado(),
                    atendimento.resumo().contato(), atendimento.resumo().inicio(), atendimento.resumo().fim(),
                    comOndeEsta(linha, comPaginas), imagensDoPacote(atendimento, linha), primeiroVideo(linha, comPaginas),
                    clock.getZone());
        }, itensDoPdf, destino);
        for (int i = 0; i < doPdf.size(); i++) {
            if (resultado.paginas().get(i) > 0) {
                ondeEsta.put(doPdf.get(i).id(), noPdf(doPdf.get(i), resultado.paginas().get(i)));
            } else {
                separados.add(doPdf.get(i)); // não abriu no PDF: vai como está, à parte
                avisos.add(doPdf.get(i).id() + " (" + nomeOuArquivo(doPdf.get(i)) + ") não abriu para entrar no PDF e vai "
                        + "à parte.");
            }
        }

        List<ArquivoDoPacote> doPacote = new ArrayList<>();
        if (resultado.total() > 0) {
            doPacote.add(new ArquivoDoPacote(pdf, destino, tamanho(destino), "a conversa" + (resultado.paginas().stream()
                    .anyMatch(p -> p > 0) ? " e, depois dela, as imagens e os documentos (" + String.join(", ", doPdf.stream()
                    .map(ItemDaLinha::id).filter(ondeEsta::containsKey).toList()) + "), cada um com o identificador no alto"
                    : "")));
        } else {
            avisos.add("O PDF com a conversa não pôde ser gravado: tente de novo.");
        }
        if (audio != null) {
            doPacote.add(audio);
        }

        // o resto, cada um com o identificador no nome, na ordem da conversa
        separados.sort(Comparator.comparingInt(naOrdem::indexOf));
        nomearSeparados(separados, arquivos, ondeEsta);
        for (ItemDaLinha item : separados) {
            Path local = noServidor(arquivos.get(item.arquivoId()));
            String nome = item.id() + "_" + nomeComExtensaoDoApp(item, local);
            doPacote.add(new ArquivoDoPacote(nome, local, tamanho(local), descricao(item)));
            if ("audio".equals(item.categoria())) {
                audioMs += conversor.duracaoMs(local).orElse(0L);
            }
        }

        if (doPacote.size() > ARQUIVOS_POR_PEDIDO) {
            avisos.add("São " + doPacote.size() + " arquivos e o app do Gemini aceita até " + ARQUIVOS_POR_PEDIDO
                    + " por mensagem: anexe os " + ARQUIVOS_POR_PEDIDO + " primeiros com \"espere os outros arquivos\", "
                    + "e o resto na mensagem seguinte, com o comando.");
        }
        if (audioMs > AUDIO_MAXIMO_MS) {
            avisos.add("Os áudios somam " + duracao(audioMs) + ": no app do Gemini sem assinatura o limite é de 10 min "
                    + "de áudio por mensagem (no Google AI Pro, 3 h).");
        }
        if (videoMs > VIDEO_MAXIMO_MS) {
            avisos.add("Os vídeos somam " + duracao(videoMs) + ": no app do Gemini sem assinatura o limite é de 5 min "
                    + "de vídeo por mensagem (no Google AI Pro, 1 h).");
        }
        doPacote.stream().filter(a -> a.tamanho() > ARQUIVO_MAXIMO && !a.nome().toLowerCase(Locale.ROOT).endsWith(".mp4"))
                .forEach(a -> avisos.add(a.nome() + " passa de 100 MB, o limite do app do Gemini por arquivo."));
        if (!audios.isEmpty() && !conversor.disponivel()) {
            avisos.add("O ffmpeg não está instalado: os áudios vão um por um (contam no limite de arquivos) e o app pode "
                    + "não abrir o formato .oga do WhatsApp.");
        }
        return new Pacote(pdf, List.copyOf(doPacote), ondeEsta, List.copyOf(avisos), audioMs, videoMs);
    }

    /** O começo do PDF: o que é, os arquivos do pacote e como escrever o assunto. */
    private static String cabecalho(AtendimentoGuardado atendimento, String pdf) {
        String contato = atendimento.resumo().contato() == null || atendimento.resumo().contato().isBlank()
                ? "contato não identificado" : atendimento.resumo().contato().strip();
        return "PACOTE DO ATENDIMENTO PARA O GEM\n"
                + "Contato: " + contato + "\n"
                + "Assunto: comece por \"" + ConferenciaDoTexto.prefixoDoAssunto(contato).strip()
                + " \" e use no máximo " + ConferenciaDoTexto.LIMITE_DO_ASSUNTO + " caracteres na linha toda.\n"
                + "Este arquivo (" + pdf + ") tem a conversa e, depois dela, as imagens e os documentos. Cada arquivo da "
                + "conversa diz onde está o conteúdo dele: numa página deste PDF, num trecho do arquivo dos áudios ou num "
                + "arquivo à parte, anexado junto.";
    }

    private static String noPdf(ItemDaLinha item, int pagina) {
        return "documento".equals(item.categoria()) ? "neste PDF, a partir da página " + pagina : "neste PDF, na página " + pagina;
    }

    private void nomearSeparados(List<ItemDaLinha> separados, Map<UUID, ArquivoDoAtendimento> arquivos,
                                 Map<String, String> ondeEsta) {
        for (ItemDaLinha item : separados) {
            Path local = noServidor(arquivos.get(item.arquivoId()));
            if (local != null) {
                ondeEsta.put(item.id(), "no arquivo " + item.id() + "_" + nomeComExtensaoDoApp(item, local));
            }
        }
    }

    /** O primeiro vídeo do pacote (para o exemplo do momento do vídeo), se o ffmpeg tira quadros. */
    private String primeiroVideo(LinhaDoTempo linha, Map<String, String> ondeEsta) {
        if (!extrator.situacao().disponivel()) {
            return null;
        }
        return linha.todos().filter(i -> "video".equals(i.categoria())).map(ItemDaLinha::id).filter(ondeEsta::containsKey)
                .findFirst().orElse(null);
    }

    static String nomeDoPdf(String chamado) {
        String limpo = chamado == null ? "" : chamado.replaceAll("[^0-9A-Za-z-]", "");
        return limpo.isEmpty() ? "Atendimento.pdf" : "Atendimento_" + limpo + ".pdf";
    }

    private record Audios(ArquivoDoPacote arquivo, long ms) {
    }

    /** Converte cada áudio para WAV, junta com 1 s entre eles e codifica em MP3 (ou fica o WAV). */
    private Audios juntarAudios(UUID id, List<ItemDaLinha> audios, Map<UUID, ArquivoDoAtendimento> arquivos,
                                Map<String, String> ondeEsta, List<ItemDaLinha> separados) throws IOException {
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
            return new Audios(null, 0);
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
        return new Audios(new ArquivoDoPacote(nome, final_, tamanho(final_), "os áudios, um depois do outro com 1 s de "
                + "silêncio entre eles (" + String.join("; ", mapa) + ")"), trechos.get(trechos.size() - 1).fimMs());
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

    /** Os arquivos do último pacote que ainda estão no disco, na mesma ordem e com os mesmos nomes. */
    private List<ArquivoDoPacote> arquivosDoUltimoPacote(AtendimentoGuardado atendimento) {
        UUID id = atendimento.resumo().id();
        Optional<LinhaDoTempo> linha = atendimentos.linhaDoTempo(id);
        Optional<Indice> indice = lerIndice(id);
        if (linha.isEmpty() || indice.isEmpty()) {
            return List.of();
        }
        Map<UUID, ArquivoDoAtendimento> arquivos = porId(atendimento);
        List<ArquivoDoPacote> lista = new ArrayList<>();
        for (String montado : List.of(indice.get().pdf(), AUDIOS + ".mp3", AUDIOS + ".wav")) {
            Path local = pasta.localDoArquivo(pasta.caminhoNoPedidoDoApp(id, montado));
            if (Files.isRegularFile(local)) {
                lista.add(new ArquivoDoPacote(montado, local, tamanho(local), ""));
            }
        }
        linha.get().todos().filter(ItemDaLinha::arquivo).forEach(item -> {
            ArquivoDoAtendimento arquivo = item.arquivoId() == null ? null : arquivos.get(item.arquivoId());
            Path local = arquivo == null ? null : noServidor(arquivo);
            if (local != null && vaiNoPacote(item) && !indice.get().juntos().contains(item.id())) {
                lista.add(new ArquivoDoPacote(item.id() + "_" + nomeComExtensaoDoApp(item, local), local, tamanho(local), ""));
            }
        });
        return lista;
    }

    /**
     * O nome do PDF e quais itens foram para ele ou para o áudio junto no último pacote (guardado num arquivo pequeno
     * ao lado, para os downloads saberem o que vai à parte sem montar tudo de novo).
     */
    private record Indice(String pdf, Set<String> juntos) {
    }

    private Optional<Indice> lerIndice(UUID id) {
        Path arquivo = pasta.localDoArquivo(pasta.caminhoNoPedidoDoApp(id, INDICE));
        if (!Files.isRegularFile(arquivo)) {
            return Optional.empty();
        }
        try {
            JsonNode lido = json.readTree(arquivo.toFile());
            Set<String> juntos = new LinkedHashSet<>();
            lido.path("juntos").forEach(i -> juntos.add(i.asText()));
            String pdf = lido.path("pdf").asText("");
            return pdf.isBlank() ? Optional.empty() : Optional.of(new Indice(pdf, juntos));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void guardarIndice(UUID id, Pacote pacote) {
        ObjectNode indice = json.createObjectNode();
        indice.put("pdf", pacote.pdf());
        pacote.ondeEsta().entrySet().stream()
                .filter(e -> e.getValue().startsWith("neste PDF") || e.getValue().startsWith("no arquivo " + AUDIOS + "."))
                .forEach(e -> indice.withArray("juntos").add(e.getKey()));
        try {
            Path arquivo = pasta.localDoArquivo(pasta.caminhoNoPedidoDoApp(id, INDICE));
            Files.createDirectories(arquivo.getParent());
            Files.writeString(arquivo, json.writeValueAsString(indice));
        } catch (IOException e) {
            log.warn("Atendimento {}: índice do pacote para o Gem não gravado ({})", id, e.getClass().getSimpleName());
        }
    }

    // --------------------------------------------------------------------------------------------- o texto do PDF

    /** A linha do tempo com o lugar de cada arquivo no pacote (a análise feita antes, se houver, vai junto). */
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

    /** As imagens que o texto pode mostrar: as do atendimento que estão no servidor (no PDF ou à parte). */
    private List<ConferenciaDoTexto.ImagemDisponivel> imagensDoPacote(AtendimentoGuardado atendimento, LinhaDoTempo linha) {
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

    private static boolean vaiNoPacote(ItemDaLinha item) {
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
