package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.application.evento.TextoConfirmado;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.AnaliseGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.CamposInformados;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.ItemDaLinha;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.LinhaDoTempo;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.CabecalhoDoChamado;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.ConferenciaDoTexto;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.ImagemDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Marcacao;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.MarcadorDeImagens;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SaidaGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SituacaoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TextoRedigido;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A tela de resultado: abrir uma saída (texto, imagens, fontes), mexer no assunto, no texto e nas imagens, copiar
 * nos três modos, baixar as imagens e confirmar o texto para a base de conhecimento. Cada pessoa só vê as saídas dos
 * próprios atendimentos (a de outra pessoa dá 404). "Gerar de novo" fica no processamento, que cuida da situação do
 * atendimento. Depois de confirmado, o texto não muda mais aqui (continua dando para copiar e baixar as imagens).
 */
@Service
public class GerenciarSaidas {

    private static final Logger log = LoggerFactory.getLogger(GerenciarSaidas.class);
    static final int LIMITE_DA_TELA = 200;

    private final Atendimentos atendimentos;
    private final Saidas saidas;
    private final PastaDosAtendimentos pasta;
    private final ConfiguracaoDaRedacao configuracao;
    private final ObjectMapper json;
    private final ApplicationEventPublisher eventos;
    private final TransactionOperations transacao;
    private final Clock clock;
    private final Analises analises;
    private final MarcadorDeImagens marcador;
    private final boolean marcacoes;

    @Autowired
    public GerenciarSaidas(Atendimentos atendimentos, Saidas saidas, PastaDosAtendimentos pasta,
                           ConfiguracaoDaRedacao configuracao, ObjectMapper json, ApplicationEventPublisher eventos,
                           TransactionOperations transacao, Clock clock, Analises analises, MarcadorDeImagens marcador,
                           @Value("${atendimento.imagens.marcacoes:true}") boolean marcacoes) {
        this.atendimentos = atendimentos;
        this.saidas = saidas;
        this.pasta = pasta;
        this.configuracao = configuracao;
        this.json = json;
        this.eventos = eventos;
        this.transacao = transacao;
        this.clock = clock;
        this.analises = analises;
        this.marcador = marcador;
        this.marcacoes = marcacoes && marcador != null && analises != null;
    }

    /** Sem as marcações em vermelho. */
    public GerenciarSaidas(Atendimentos atendimentos, Saidas saidas, PastaDosAtendimentos pasta,
                           ConfiguracaoDaRedacao configuracao, ObjectMapper json, ApplicationEventPublisher eventos,
                           TransactionOperations transacao, Clock clock) {
        this(atendimentos, saidas, pasta, configuracao, json, eventos, transacao, clock, null, null, false);
    }

    /** Sem a base de conhecimento (testes do gerador): confirmar só marca a saída. */
    public GerenciarSaidas(Atendimentos atendimentos, Saidas saidas, PastaDosAtendimentos pasta,
                           ConfiguracaoDaRedacao configuracao, ObjectMapper json) {
        this(atendimentos, saidas, pasta, configuracao, json, evento -> { }, TransactionOperations.withoutTransaction(),
                Clock.systemDefaultZone());
    }

    /**
     * O resultado da confirmação.
     *
     * @param avisos o que não foi para a base (ex.: uma imagem que a retenção já apagou)
     */
    public record ConfirmacaoView(SaidaView saida, List<String> avisos) {
    }

    /** Uma imagem para abrir na tela ou ir no .zip. */
    public record ArquivoDaImagem(Path local, String tipoDeMidia, String nome) {
    }

    public List<EstadoDaSaida> listar(Usuario usuario, UUID atendimentoId) {
        dono(usuario, atendimentoId);
        return saidas.ultimas(atendimentoId).stream().map(EstadoDaSaida::de).toList();
    }

    public SaidaView detalhe(Usuario usuario, UUID atendimentoId, UUID saidaId) {
        return view(daPessoa(usuario, atendimentoId, saidaId));
    }

    public LinhaDoTempoView linhaDoTempo(Usuario usuario, UUID atendimentoId) {
        dono(usuario, atendimentoId);
        LinhaDoTempo linha = atendimentos.linhaDoTempo(atendimentoId).orElseThrow(() -> new RecursoNaoEncontradoException(
                "SEM_LINHA_DO_TEMPO", "A linha do tempo deste atendimento ainda não foi montada (ela é montada antes da "
                + "redação)."));
        List<LinhaDoTempoView.Item> itens = new ArrayList<>();
        linha.itens().forEach(i -> itens.add(item(i, false)));
        linha.semHorario().forEach(i -> itens.add(item(i, true)));
        return new LinhaDoTempoView(linha.montadaEm(), itens);
    }

    public SaidaView salvarAssunto(Usuario usuario, UUID atendimentoId, UUID saidaId, String assunto) {
        SaidaGuardada saida = editavel(daPessoa(usuario, atendimentoId, saidaId));
        String limpo = assunto == null ? "" : assunto.strip().replaceAll("\\s+", " ");
        if (limpo.isEmpty()) {
            throw new RegraNegocioException("ASSUNTO_VAZIO", "Escreva o assunto.");
        }
        if (limpo.length() > ConferenciaDoTexto.LIMITE_DO_ASSUNTO) {
            throw new RegraNegocioException("ASSUNTO_LONGO", "O assunto tem " + limpo.length() + " caracteres; o limite é "
                    + ConferenciaDoTexto.LIMITE_DO_ASSUNTO + ".");
        }
        saidas.salvarAssunto(saida.id(), limpo);
        return detalhe(usuario, atendimentoId, saidaId);
    }

    /** O texto editado na tela: limpo no servidor e com as imagens renumeradas na ordem em que ficaram. */
    public SaidaView salvarTexto(Usuario usuario, UUID atendimentoId, UUID saidaId, String html) {
        SaidaGuardada saida = editavel(daPessoa(usuario, atendimentoId, saidaId));
        String limpo = HtmlDaSaida.limpar(html);
        if (!HtmlDaSaida.temConteudo(limpo)) {
            throw new RegraNegocioException("TEXTO_VAZIO", "O texto ficou vazio. Para voltar ao texto gerado, use "
                    + "Desfazer as mudanças.");
        }
        HtmlDaSaida.Renumerado renumerado = HtmlDaSaida.renumerar(limpo, saidas.imagens(saida.id()));
        saidas.salvarTexto(saida.id(), renumerado.html(), renumerado.ordem());
        log.info("Saída {}: texto editado ({} imagens no texto)", saida.id(),
                renumerado.ordem().stream().filter(Saidas.ImagemNumerada::incluida).count());
        return detalhe(usuario, atendimentoId, saidaId);
    }

    /**
     * As imagens do texto na ordem pedida (as que não estão na lista saem do texto; as que voltam entram no fim).
     * O servidor renumera.
     */
    public SaidaView ordenarImagens(Usuario usuario, UUID atendimentoId, UUID saidaId, List<UUID> incluidas) {
        SaidaGuardada saida = editavel(daPessoa(usuario, atendimentoId, saidaId));
        List<ImagemDaSaida> imagens = saidas.imagens(saida.id());
        Set<UUID> daSaida = new HashSet<>(imagens.stream().map(ImagemDaSaida::id).toList());
        List<UUID> pedidas = incluidas == null ? List.of() : incluidas;
        if (!daSaida.containsAll(pedidas) || new HashSet<>(pedidas).size() != pedidas.size()) {
            throw new RegraNegocioException("IMAGENS_INVALIDAS", "A lista de imagens não confere com as deste texto. "
                    + "Recarregue a tela.");
        }
        String permutado = HtmlDaSaida.permutar(saida.htmlAtual(), pedidas, imagens);
        HtmlDaSaida.Renumerado renumerado = HtmlDaSaida.renumerar(permutado, imagens);
        saidas.salvarTexto(saida.id(), renumerado.html(), renumerado.ordem());
        return detalhe(usuario, atendimentoId, saidaId);
    }

    /** Volta ao texto gerado (e às imagens na numeração dele). */
    public SaidaView desfazerEdicao(Usuario usuario, UUID atendimentoId, UUID saidaId) {
        SaidaGuardada saida = editavel(daPessoa(usuario, atendimentoId, saidaId));
        List<ImagemDaSaida> imagens = saidas.imagens(saida.id());
        TextoRedigido texto = EstruturaDaSaida.ler(json, saida.estrutura());
        // as legendas do texto gerado (a renumeração lê do HTML original)
        HtmlDaSaida.Renumerado original = HtmlDaSaida.renumerar(saida.html(), imagens);
        saidas.salvarTexto(saida.id(), null, original.ordem());
        log.info("Saída {}: mudanças desfeitas ({} trechos)", saida.id(), texto.blocos().size());
        return detalhe(usuario, atendimentoId, saidaId);
    }

    /**
     * O texto para colar no sistema de chamados, no modo pedido (nos chamados para o desenvolvimento, com o cabeçalho
     * e o rodapé montados com os campos de agora).
     */
    public CopiaView copia(Usuario usuario, UUID atendimentoId, UUID saidaId, ModoDeCopia modo) {
        SaidaGuardada saida = comTexto(daPessoa(usuario, atendimentoId, saidaId));
        String completo = completo(saida);
        List<String> avisos = new ArrayList<>();
        String html;
        if (modo == ModoDeCopia.COM_IMAGENS) {
            Map<UUID, ImagemDaSaida> imagens = new HashMap<>();
            imagensQueValem(saida).forEach(i -> imagens.put(i.id(), i));
            long[] embutidos = {0};
            html = HtmlDaSaida.paraCopiar(completo, id -> embutida(imagens.get(id), avisos, embutidos));
        } else {
            html = HtmlDaSaida.paraCopiar(completo, null);
        }
        return new CopiaView(modo.name().toLowerCase(Locale.ROOT), html, HtmlDaSaida.textoSimples(completo),
                List.copyOf(avisos));
    }

    /** A Tela/Funcionalidade do cabeçalho de um chamado (a sugestão do sistema pode ser trocada). */
    public SaidaView salvarTela(Usuario usuario, UUID atendimentoId, UUID saidaId, String tela) {
        SaidaGuardada saida = editavel(daPessoa(usuario, atendimentoId, saidaId));
        if (!saida.tipo().paraODesenvolvimento()) {
            throw new RegraNegocioException("SO_NOS_CHAMADOS", "A Tela/Funcionalidade só existe nos chamados para o "
                    + "desenvolvimento.");
        }
        String limpa = tela == null ? "" : tela.strip().replaceAll("\\s+", " ");
        if (limpa.length() > LIMITE_DA_TELA) {
            throw new RegraNegocioException("TELA_LONGA", "A Tela/Funcionalidade tem " + limpa.length() + " caracteres; o "
                    + "limite é " + LIMITE_DA_TELA + ".");
        }
        saidas.salvarTela(saida.id(), limpa.isEmpty() ? null : limpa);
        return detalhe(usuario, atendimentoId, saidaId);
    }

    /**
     * Confirma o texto e o envia para a base de conhecimento com o formulário revisado pelo autor: a saída fica
     * confirmada e sai o {@link TextoConfirmado} (com o texto como vai para o chamado e as imagens que ainda estão no
     * servidor), numa transação só. Se a base recusar, a saída continua pronta.
     */
    public ConfirmacaoView confirmar(Usuario usuario, UUID atendimentoId, UUID saidaId, TextoConfirmado.Formulario formulario) {
        SaidaGuardada saida = daPessoa(usuario, atendimentoId, saidaId);
        if (saida.situacao() == SituacaoDaSaida.CONFIRMADA) {
            throw jaConfirmada(saida);
        }
        comTexto(saida);
        if (formulario == null) {
            throw new RegraNegocioException("REGISTRO_VAZIO", "Preencha o registro antes de confirmar.");
        }
        String html = HtmlDaSaida.paraCopiar(completo(saida), null);
        List<TextoConfirmado.Imagem> imagens = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        for (ImagemDaSaida imagem : imagensQueValem(saida)) {
            if (!imagem.incluida()) {
                continue;
            }
            Path local = pasta.localDoArquivo(imagem.caminho());
            if (Files.isRegularFile(local)) {
                imagens.add(new TextoConfirmado.Imagem(imagem.numero(), local, imagem.legenda()));
            } else {
                avisos.add("A imagem " + imagem.numero() + " não está mais no servidor (prazo de retenção) e não foi "
                        + "para a base.");
            }
        }
        Instant agora = clock.instant();
        transacao.executeWithoutResult(status -> {
            if (!saidas.confirmar(saida.id(), agora)) {
                throw new ConflitoException("TEXTO_MUDOU", "Este texto mudou enquanto você revisava (foi confirmado em "
                        + "outra janela ou está sendo gerado de novo). Recarregue a tela.");
            }
            eventos.publishEvent(new TextoConfirmado(atendimentoId, saida.id(), saida.tipo().codigo(), usuario.id(),
                    usuario.login(), formulario, html, imagens, agora));
        });
        log.info("Saída {}: confirmada e enviada para a base ({} imagens)", saida.id(), imagens.size());
        return new ConfirmacaoView(detalhe(usuario, atendimentoId, saidaId), List.copyOf(avisos));
    }

    /**
     * Liga (ou desliga) as marcações em vermelho de uma imagem do texto (experimental): as áreas que a análise da
     * imagem apontou (a mensagem de erro, o campo com problema) ganham uma borda vermelha numa cópia; o original não
     * muda. Com a marcação ligada, a cópia é a imagem que vale na tela, na cópia para o chamado, no .zip e na base.
     */
    public SaidaView marcar(Usuario usuario, UUID atendimentoId, UUID saidaId, UUID imagemId, boolean ligar) {
        SaidaGuardada saida = editavel(daPessoa(usuario, atendimentoId, saidaId));
        if (!marcacoes) {
            throw new RegraNegocioException("MARCACOES_DESLIGADAS", "As marcações em vermelho estão desligadas neste "
                    + "servidor (atendimento.imagens.marcacoes).");
        }
        ImagemDaSaida imagem = daSaida(saida, imagemId);
        String caminhoDaMarcada = pasta.caminhoDaMarcada(saida.atendimentoId(), imagem.id());
        if (!ligar) {
            saidas.salvarMarcacao(imagem.id(), null, false, null, null);
            try {
                pasta.apagarArquivo(caminhoDaMarcada);
            } catch (RuntimeException e) {
                log.warn("Não foi possível apagar a cópia marcada da imagem {}: {}", imagem.id(), e.toString());
            }
            return detalhe(usuario, atendimentoId, saidaId);
        }
        if (imagem.doVideo() || imagem.arquivoId() == null) {
            throw new RegraNegocioException("SEM_AREAS", imagem.doVideo()
                    ? "Os quadros do vídeo não têm áreas apontadas pela análise: não dá para marcar."
                    : "A imagem não tem mais a análise de origem (o arquivo foi tirado): não dá para marcar.");
        }
        List<Marcacao> areas = imagem.marcacoes() != null ? lerMarcacoes(imagem.marcacoes()) : daAnalise(imagem.arquivoId());
        if (areas.isEmpty()) {
            throw new RegraNegocioException("SEM_AREAS", "A análise desta imagem não apontou nenhuma área para marcar.");
        }
        Path origem = pasta.localDoArquivo(imagem.caminho());
        if (!Files.isRegularFile(origem)) {
            throw imagemApagada();
        }
        MarcadorDeImagens.Tamanho tamanho;
        try {
            tamanho = marcador.marcar(origem, pasta.localDoArquivo(caminhoDaMarcada), areas);
        } catch (MarcadorDeImagens.FalhaNaMarcacao falha) {
            throw new RegraNegocioException("MARCACAO_FALHOU", falha.getMessage());
        }
        try {
            saidas.salvarMarcacao(imagem.id(), json.writeValueAsString(areas), true, tamanho.largura(), tamanho.altura());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("marcações sem JSON", e);
        }
        log.info("Saída {}: imagem {} marcada ({} área(s))", saida.id(), imagem.numero(), areas.size());
        return detalhe(usuario, atendimentoId, saidaId);
    }

    /** As áreas da análise mais recente do arquivo ("regioes": [ymin, xmin, ymax, xmax] de 0 a 1000), as válidas. */
    private List<Marcacao> daAnalise(UUID arquivoId) {
        Optional<AnaliseGuardada> analise = analises.maisRecente(arquivoId);
        if (analise.isEmpty() || analise.get().resultado() == null) {
            return List.of();
        }
        List<Marcacao> areas = new ArrayList<>();
        try {
            for (JsonNode regiao : json.readTree(analise.get().resultado()).path("regioes")) {
                JsonNode caixa = regiao.path("caixa");
                if (caixa.isArray() && caixa.size() == 4 && areas.size() < 5) {
                    Marcacao m = new Marcacao(regiao.path("descricao").asText(""), caixa.get(0).asInt(-1),
                            caixa.get(1).asInt(-1), caixa.get(2).asInt(-1), caixa.get(3).asInt(-1));
                    if (m.valida()) {
                        areas.add(m);
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("Análise do arquivo {} sem JSON legível para as marcações", arquivoId);
        }
        return areas;
    }

    private List<Marcacao> lerMarcacoes(String guardadas) {
        try {
            return json.readValue(guardadas, new TypeReference<List<Marcacao>>() { }).stream().filter(Marcacao::valida).toList();
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private ImagemDaSaida daSaida(SaidaGuardada saida, UUID imagemId) {
        return saidas.imagens(saida.id()).stream().filter(i -> i.id().equals(imagemId)).findFirst()
                .orElseThrow(() -> new RecursoNaoEncontradoException("IMAGEM_NAO_ENCONTRADA", "Imagem não encontrada."));
    }

    /** A imagem como vale agora: a cópia marcada, se a marcação está ligada e a cópia existe; senão, o original. */
    private ImagemDaSaida queVale(SaidaGuardada saida, ImagemDaSaida i) {
        if (!i.comMarcacao()) {
            return i;
        }
        String marcada = pasta.caminhoDaMarcada(saida.atendimentoId(), i.id());
        if (!Files.isRegularFile(pasta.localDoArquivo(marcada))) {
            return i;
        }
        return new ImagemDaSaida(i.id(), i.saidaId(), i.numero(), i.arquivoId(), marcada, i.legenda(), i.incluida(),
                i.instanteVideoMs(), i.marcacoes(), true);
    }

    private List<ImagemDaSaida> imagensQueValem(SaidaGuardada saida) {
        return saidas.imagens(saida.id()).stream().map(i -> queVale(saida, i)).toList();
    }

    /** O texto inteiro: cabeçalho, corpo (o editado, se houver) e rodapé. */
    private String completo(SaidaGuardada saida) {
        if (!saida.tipo().paraODesenvolvimento()) {
            return saida.htmlAtual();
        }
        CamposInformados campos = atendimentos.camposInformados(saida.atendimentoId());
        TextoRedigido texto = EstruturaDaSaida.ler(json, saida.estrutura());
        return CabecalhoDoChamado.cabecalho(saida.tipo(), campos, texto.telaFuncionalidade()) + saida.htmlAtual() + "\n"
                + CabecalhoDoChamado.rodape(saida.tipo(), campos);
    }

    /** O arquivo de uma imagem da saída (para a miniatura na tela). */
    public ArquivoDaImagem imagem(Usuario usuario, UUID atendimentoId, UUID saidaId, UUID imagemId) {
        SaidaGuardada saida = daPessoa(usuario, atendimentoId, saidaId);
        ImagemDaSaida imagem = queVale(saida, daSaida(saida, imagemId));
        Path local = pasta.localDoArquivo(imagem.caminho());
        if (!Files.isRegularFile(local)) {
            throw imagemApagada();
        }
        return new ArquivoDaImagem(local, tipoDeMidia(imagem.caminho()), nomeNoZip(imagem));
    }

    /** As imagens do texto, na ordem, para o .zip (Imagem_01.png, Imagem_02.jpeg...). */
    public List<ArquivoDaImagem> imagensParaOZip(Usuario usuario, UUID atendimentoId, UUID saidaId) {
        SaidaGuardada saida = comTexto(daPessoa(usuario, atendimentoId, saidaId));
        List<ArquivoDaImagem> arquivos = new ArrayList<>();
        boolean faltou = false;
        for (ImagemDaSaida imagem : imagensQueValem(saida)) {
            if (!imagem.incluida()) {
                continue;
            }
            Path local = pasta.localDoArquivo(imagem.caminho());
            if (Files.isRegularFile(local)) {
                arquivos.add(new ArquivoDaImagem(local, tipoDeMidia(imagem.caminho()), nomeNoZip(imagem)));
            } else {
                faltou = true;
            }
        }
        if (arquivos.isEmpty()) {
            if (faltou) {
                throw imagemApagada();
            }
            throw new RegraNegocioException("SEM_IMAGENS", "Este texto não tem imagens.");
        }
        return arquivos;
    }

    // --------------------------------------------------------------------------------------------- apoio

    private SaidaView view(SaidaGuardada saida) {
        TextoRedigido texto = EstruturaDaSaida.ler(json, saida.estrutura());
        List<SaidaView.Imagem> imagens = imagensQueValem(saida).stream()
                .map(i -> new SaidaView.Imagem(i.id(), i.numero(), i.legenda(), i.incluida(),
                        Files.isRegularFile(pasta.localDoArquivo(i.caminho())), nomeNoZip(i), i.comMarcacao(),
                        marcacoes && !i.doVideo() && i.arquivoId() != null))
                .toList();
        boolean apagadas = !imagens.isEmpty() && imagens.stream().noneMatch(SaidaView.Imagem::disponivel);
        CamposInformados campos = atendimentos.camposInformados(saida.atendimentoId());
        TextoConfirmado.Formulario registro = saida.situacao() == SituacaoDaSaida.PRONTA
                ? RascunhoDoRegistro.de(saida, texto, atendimentos.contato(saida.atendimentoId()).orElse(null)) : null;
        SaidaView.VersaoNaBase naBase = saidas.ultimaConfirmada(saida.atendimentoId(), saida.tipo())
                .filter(c -> !c.id().equals(saida.id()))
                .map(c -> new SaidaView.VersaoNaBase(c.id(), c.versao(), c.confirmadaEm()))
                .orElse(null);
        return new SaidaView(saida.id(), saida.tipo().codigo(), saida.tipo().rotulo(), saida.versao(),
                saida.situacao().codigo(), saida.erroMensagem(), saida.assunto(), ConferenciaDoTexto.LIMITE_DO_ASSUNTO,
                saida.htmlAtual(), saida.htmlEditado() != null, texto.avisos(), texto.base(), texto.telaFuncionalidade(),
                imagens, saida.modelo(), saida.versaoPrompt(), saida.tokensEntrada(), saida.tokensSaida(), saida.criadaEm(),
                apagadas, CabecalhoDoChamado.cabecalho(saida.tipo(), campos, texto.telaFuncionalidade()),
                CabecalhoDoChamado.rodape(saida.tipo(), campos), texto.variantes(), saida.confirmadaEm(), registro, naBase);
    }

    /**
     * A imagem em base64, para a cópia "com imagens". Além do limite de cada uma, a soma também tem teto (uma vez e meia
     * o limite de uma: 15 MB no padrão): o texto da cópia vira várias cópias na memória (base64, HTML, JSON), e muitas
     * imagens grandes juntas derrubariam o sistema. Passou do teto, a imagem vai só com a legenda e o aviso para anexá-la.
     */
    private Optional<String> embutida(ImagemDaSaida imagem, List<String> avisos, long[] embutidos) {
        if (imagem == null) {
            return Optional.empty();
        }
        Path local = pasta.localDoArquivo(imagem.caminho());
        try {
            if (!Files.isRegularFile(local)) {
                avisos.add("A imagem " + imagem.numero() + " não está mais no servidor (prazo de retenção) e foi só a legenda.");
                return Optional.empty();
            }
            long tamanho = Files.size(local);
            if (tamanho > configuracao.imagemMaximaNaCopia()) {
                avisos.add("A imagem " + imagem.numero() + " é grande demais para ir na cópia (" + (tamanho / (1024 * 1024))
                        + " MB): anexe o arquivo " + nomeNoZip(imagem) + ".");
                return Optional.empty();
            }
            if (embutidos[0] + tamanho > configuracao.imagemMaximaNaCopia() * 3 / 2) {
                avisos.add("A imagem " + imagem.numero() + " passou do tamanho total das imagens que vão na cópia: "
                        + "anexe o arquivo " + nomeNoZip(imagem) + ".");
                return Optional.empty();
            }
            embutidos[0] += tamanho;
            return Optional.of("data:" + tipoDeMidia(imagem.caminho()) + ";base64,"
                    + Base64.getEncoder().encodeToString(Files.readAllBytes(local)));
        } catch (IOException e) {
            log.warn("Não foi possível ler a imagem {} da saída {}: {}", imagem.id(), imagem.saidaId(), e.toString());
            avisos.add("A imagem " + imagem.numero() + " não pôde ser lida e foi só a legenda.");
            return Optional.empty();
        }
    }

    private SaidaGuardada daPessoa(Usuario usuario, UUID atendimentoId, UUID saidaId) {
        dono(usuario, atendimentoId);
        return saidas.buscar(saidaId)
                .filter(s -> s.atendimentoId().equals(atendimentoId))
                .orElseThrow(() -> new RecursoNaoEncontradoException("SAIDA_NAO_ENCONTRADA", "Texto não encontrado. Ele "
                        + "pode ter sido apagado junto com o atendimento."));
    }

    /** Tem texto para copiar (pronta ou já confirmada). */
    private static SaidaGuardada comTexto(SaidaGuardada saida) {
        if (!saida.situacao().comTexto()) {
            throw new RegraNegocioException("SAIDA_SEM_TEXTO", saida.situacao().emAndamento()
                    ? "O texto ainda está sendo gerado. Aguarde terminar."
                    : "Este texto não ficou pronto. Clique em Gerar de novo.");
        }
        return saida;
    }

    /** Pode mudar: pronta (depois de confirmado, o texto é o que foi para a base). */
    private SaidaGuardada editavel(SaidaGuardada saida) {
        if (saida.situacao() == SituacaoDaSaida.CONFIRMADA) {
            throw new RegraNegocioException("TEXTO_CONFIRMADO", "Este texto já foi enviado para a base de conhecimento e "
                    + "não muda mais aqui (dá para copiar). Para corrigir o que está na base, edite o registro lá; para "
                    + "um texto novo, gere de novo.");
        }
        return comTexto(saida);
    }

    private ConflitoException jaConfirmada(SaidaGuardada saida) {
        String quando = saida.confirmadaEm() == null ? "" : " em " + DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm")
                .withZone(clock.getZone()).format(saida.confirmadaEm());
        return new ConflitoException("TEXTO_JA_CONFIRMADO", "Este texto já foi enviado para a base" + quando + ".");
    }

    private void dono(Usuario usuario, UUID atendimentoId) {
        if (!atendimentos.existe(atendimentoId, usuario.id())) {
            throw new RecursoNaoEncontradoException("ATENDIMENTO_NAO_ENCONTRADO", "Atendimento não encontrado. Ele pode ter "
                    + "sido apagado.");
        }
    }

    private static LinhaDoTempoView.Item item(ItemDaLinha i, boolean semHorario) {
        return new LinhaDoTempoView.Item(i.id(), i.tipo().codigo(), i.momento(), i.lado(), i.remetente(), i.texto(),
                i.arquivoId(), i.origem(), i.categoria(), i.nome(), i.mensagem(), i.situacao(), semHorario);
    }

    private static RegraNegocioException imagemApagada() {
        return new RegraNegocioException("IMAGEM_APAGADA", "Os arquivos deste atendimento já foram apagados (prazo de "
                + "retenção). As imagens não estão mais no servidor.");
    }

    /** Imagem_01.png: o número com dois dígitos e a extensão do arquivo original. */
    static String nomeNoZip(ImagemDaSaida imagem) {
        return String.format(Locale.ROOT, "Imagem_%02d.%s", imagem.numero(), extensao(imagem.caminho()));
    }

    private static String extensao(String caminho) {
        String nome = caminho == null ? "" : Path.of(caminho).getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        String ext = ponto < 0 ? "" : nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,5}") ? ext : "img";
    }

    static String tipoDeMidia(String caminho) {
        return switch (extensao(caminho)) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "heic" -> "image/heic";
            case "heif" -> "image/heif";
            default -> "application/octet-stream";
        };
    }

    /** Escreve o .zip (quem chama já conferiu tudo; aqui só copia os bytes). */
    public static void escreverZip(List<ArquivoDaImagem> imagens, OutputStream saida) {
        try (ZipOutputStream zip = new ZipOutputStream(saida)) {
            for (ArquivoDaImagem imagem : imagens) {
                zip.putNextEntry(new ZipEntry(imagem.nome()));
                Files.copy(imagem.local(), zip);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
