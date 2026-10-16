package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.application.ia.ChamadasAoGemini;
import br.com.conferenciaponto.modulos.atendimento.application.video.QuadrosDoVideo;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.AnaliseGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.AtendimentoGuardado;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Mascaramento;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.TelaDeAcessoRemoto;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ExecutorDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.ModeloDeIA;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.PedidoAoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.RespostaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.ItemDaLinha;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.LinhaDoTempo;
import br.com.conferenciaponto.modulos.atendimento.domain.linha.SugestaoDaTela;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.ConferenciaDoTexto;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.ModeloDoTexto;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.MontagemDoHtml;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SaidaGuardada;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.Saidas;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.SituacaoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TextoRedigido;
import br.com.conferenciaponto.modulos.atendimento.domain.saida.TipoDeSaida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A tarefa "redigir": uma chamada ao Gemini por saída, com a linha do tempo como único material. A resposta (JSON)
 * passa pela conferência do servidor (fontes, imagens, assunto, dados de acesso) e vira HTML montado aqui. As imagens
 * que o texto pode usar são os anexos e prints analisados e os quadros dos passos dos vídeos (os que faltarem são
 * tirados antes, se o ffmpeg estiver instalado).
 */
@Component
public class RedigirSaida implements ExecutorDeTarefa {

    private static final Logger log = LoggerFactory.getLogger(RedigirSaida.class);

    private final Saidas saidas;
    private final Atendimentos atendimentos;
    private final ChamadasAoGemini gemini;
    private final ModeloDeIA modelo;
    private final PastaDosAtendimentos pasta;
    private final ConfiguracaoDaRedacao configuracao;
    private final ObjectMapper json;
    private final Clock clock;
    private final Analises analises;
    private final QuadrosDoVideo quadros;
    private final Mascaramento mascaramento = new Mascaramento();

    /** Sem os quadros dos vídeos (só anexos e prints no catálogo de imagens). */
    public RedigirSaida(Saidas saidas, Atendimentos atendimentos, ChamadasAoGemini gemini, ModeloDeIA modelo,
                        PastaDosAtendimentos pasta, ConfiguracaoDaRedacao configuracao, ObjectMapper json, Clock clock) {
        this(saidas, atendimentos, gemini, modelo, pasta, configuracao, json, clock, null, null);
    }

    @Autowired
    public RedigirSaida(Saidas saidas, Atendimentos atendimentos, ChamadasAoGemini gemini, ModeloDeIA modelo,
                        PastaDosAtendimentos pasta, ConfiguracaoDaRedacao configuracao, ObjectMapper json, Clock clock,
                        Analises analises, QuadrosDoVideo quadros) {
        this.analises = analises;
        this.quadros = quadros;
        this.saidas = saidas;
        this.atendimentos = atendimentos;
        this.gemini = gemini;
        this.modelo = modelo;
        this.pasta = pasta;
        this.configuracao = configuracao;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public TipoDeTarefa tipo() {
        return TipoDeTarefa.REDIGIR;
    }

    @Override
    public ResultadoDaTarefa executar(Tarefa tarefa) {
        Optional<SaidaGuardada> talvez = tarefa.saidaId() == null ? Optional.empty() : saidas.buscar(tarefa.saidaId());
        if (talvez.isEmpty() || !talvez.get().situacao().emAndamento()) {
            return ResultadoDaTarefa.concluida(); // apagada, já redigida ou falhou de vez: nada a fazer
        }
        SaidaGuardada saida = talvez.get();
        Optional<PromptsDaRedacao.Prompt> prompt = PromptsDaRedacao.para(saida.tipo());
        if (prompt.isEmpty()) {
            return ResultadoDaTarefa.definitiva("TIPO_INDISPONIVEL", "Este tipo de texto ainda não existe nesta versão do sistema.");
        }
        Optional<AtendimentoGuardado> atendimento = atendimentos.buscar(tarefa.atendimentoId(), tarefa.usuarioId());
        Optional<LinhaDoTempo> linha = atendimentos.linhaDoTempo(tarefa.atendimentoId());
        if (atendimento.isEmpty() || linha.isEmpty()) {
            return ResultadoDaTarefa.definitiva("SEM_LINHA_DO_TEMPO", "A linha do tempo do atendimento não foi montada. "
                    + "Clique em Gerar de novo.");
        }
        saidas.mudarSituacaoSe(saida.id(), Set.of(SituacaoDaSaida.PENDENTE), SituacaoDaSaida.REDIGINDO, null);

        ChamadasAoGemini.ChaveDoDono dono = gemini.chaveDo(tarefa.usuarioId());
        if (!dono.aberta()) {
            return dono.falha();
        }
        String contato = atendimento.get().resumo().contato();
        Catalogo catalogo = catalogo(atendimento.get(), linha.get());
        List<ConferenciaDoTexto.ImagemDisponivel> imagens = catalogo.imagens();
        String material = MaterialDaRedacao.texto(atendimento.get().resumo().chamado(), contato,
                atendimento.get().resumo().inicio(), atendimento.get().resumo().fim(), linha.get(), imagens, clock.getZone());
        String instrucao = prompt.get().instrucao(Map.of(
                "CONTATO", contato == null || contato.isBlank() ? "contato não identificado" : contato.strip(),
                "LIMITE_DO_ASSUNTO", String.valueOf(ConferenciaDoTexto.limiteDoAssunto(contato))));
        PedidoAoModelo pedido = new PedidoAoModelo(configuracao.modelo(), PromptsDaRedacao.SISTEMA,
                instrucao + "\n\n---\n\n" + material, prompt.get().esquema(), null, false, configuracao.temperatura(),
                configuracao.raciocinio(), configuracao.maxTokensSaida());
        try {
            long inicio = System.nanoTime();
            Lida lida = gerarEConferir(dono.chave(), pedido, prompt.get());
            ModeloDoTexto modeloDoTexto = ModeloDoTexto.de(saida.tipo());
            ConferenciaDoTexto.Conferido conferido = conferir(saida.tipo(), contato, linha.get(), imagens, lida.resultado());
            TextoRedigido texto = conferido.texto();
            texto = texto.comMaisAvisos(catalogo.avisos());
            SemConteudo sem = semConteudo(linha.get());
            texto = texto.comMaisAvisos(sem.avisos()).comSemAnalise(sem.arquivos());
            int entrada = lida.tokensEntrada();
            int saidaDeTokens = lida.tokensSaida();
            if (conferido.assuntoLongo()) {
                Encurtado curto = encurtar(dono.chave(), conferido.assuntoDoModelo(), contato);
                entrada += curto.tokensEntrada();
                saidaDeTokens += curto.tokensSaida();
                texto = ConferenciaDoTexto.comAssunto(texto, contato, curto.assunto());
            }
            String html = MontagemDoHtml.montar(texto.blocos(), modeloDoTexto);
            boolean salva = saidas.salvarRedacao(saida.id(), EstruturaDaSaida.escrever(json, texto), html, texto.assunto(),
                    configuracao.modelo(), prompt.get().versao(), entrada, saidaDeTokens, conferido.imagens());
            gemini.usoDeuCerto(tarefa.usuarioId(), dono);
            log.info("Saída {} ({} v{}) redigida com {}: {} trechos, {} imagens, {} avisos, {} + {} tokens, {} ms{}",
                    saida.id(), saida.tipo().codigo(), saida.versao(), configuracao.modelo(), texto.blocos().size(),
                    conferido.imagens().size(), texto.avisos().size(), entrada, saidaDeTokens,
                    (System.nanoTime() - inicio) / 1_000_000, salva ? "" : " (a saída mudou no meio: nada gravado)");
            return ResultadoDaTarefa.concluida();
        } catch (FalhaDoModelo falha) {
            log.info("Saída {} ({}): o Gemini não redigiu ({}: {})", saida.id(), saida.tipo().codigo(), falha.tipo(),
                    falha.getMessage());
            return gemini.traduzir(falha, tarefa.usuarioId(), configuracao.modelo(), "redigir este texto");
        }
    }

    /**
     * O JSON do Gemini (da API ou colado do app) conferido pelo servidor: as fontes e as imagens que existem, os dados
     * de acesso mascarados, as partes na ordem do modelo (com o texto do sistema nas vazias que pedem) e a
     * Tela/Funcionalidade sugerida pelos códigos lidos nas imagens quando o Gemini não disse.
     *
     * @throws FalhaDoModelo RESPOSTA_INVALIDA quando faltam as partes do chamado
     */
    ConferenciaDoTexto.Conferido conferir(TipoDeSaida tipo, String contato, LinhaDoTempo linha,
                                          List<ConferenciaDoTexto.ImagemDisponivel> imagens, JsonNode resultado) {
        return conferir(tipo, contato, linha, imagens, bruto(resultado, ModeloDoTexto.de(tipo)));
    }

    /** A mesma conferência para um texto já lido (o que veio do app do Gemini, por exemplo). */
    ConferenciaDoTexto.Conferido conferir(TipoDeSaida tipo, String contato, LinhaDoTempo linha,
                                          List<ConferenciaDoTexto.ImagemDisponivel> imagens, ConferenciaDoTexto.Bruto bruto) {
        ConferenciaDoTexto conferencia = new ConferenciaDoTexto(linha.todos().map(ItemDaLinha::id).collect(Collectors.toSet()),
                imagens, mascaramento::mascararTrechos, UUID::randomUUID);
        ModeloDoTexto modeloDoTexto = ModeloDoTexto.de(tipo);
        ConferenciaDoTexto.Conferido conferido = conferencia.conferir(bruto, contato);
        TextoRedigido texto = conferido.texto().comBlocos(modeloDoTexto.completar(conferido.texto().blocos()));
        if (semValor(texto.telaFuncionalidade())) {
            texto = texto.comTela(SugestaoDaTela.de(linha).orElse(texto.telaFuncionalidade()));
        }
        return new ConferenciaDoTexto.Conferido(texto, conferido.imagens(), conferido.assuntoDoModelo(), conferido.assuntoLongo());
    }

    /** Os arquivos cujo conteúdo não entrou no texto e o aviso de cada um, para a pessoa ver antes de usar. */
    record SemConteudo(List<UUID> arquivos, List<String> avisos) {
    }

    private static final Set<String> SEM_CONTEUDO = Set.of("falhou", "sem_analise", "nao_baixado", "nao_suportado");

    /**
     * Os arquivos da linha do tempo que entraram sem conteúdo (a análise falhou de vez, não foi feita, ou o arquivo não
     * foi baixado ou não é suportado): o texto foi escrito sem eles, e isso aparece em "Confira antes de usar". Quando a
     * análise de um deles chegar depois, o texto é gerado de novo (menos o não suportado, que não vai mudar).
     */
    static SemConteudo semConteudo(LinhaDoTempo linha) {
        List<UUID> arquivos = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        linha.todos()
                .filter(ItemDaLinha::arquivo)
                .filter(i -> SEM_CONTEUDO.contains(i.situacao()))
                .forEach(i -> {
                    String porque = switch (i.situacao()) {
                        case "falhou" -> "a análise falhou";
                        case "nao_baixado" -> "o arquivo não foi baixado";
                        case "nao_suportado" -> "formato que não é analisado";
                        default -> "ainda sem análise";
                    };
                    String aviso = "Escrito sem o conteúdo " + doArquivo(i.categoria(), i.nome()) + " (" + porque + ")"
                            + (i.situacao().equals("nao_suportado") ? ": confira se faltou alguma coisa."
                            : ": confira se faltou alguma coisa; quando ele for analisado, o texto é gerado de novo sozinho.");
                    if (!avisos.contains(aviso)) {
                        avisos.add(aviso);
                    }
                    if (i.arquivoId() != null && !i.situacao().equals("nao_suportado") && !arquivos.contains(i.arquivoId())) {
                        arquivos.add(i.arquivoId());
                    }
                });
        return new SemConteudo(arquivos, avisos);
    }

    /** "do vídeo gravacao.mp4", "da imagem print.png". */
    static String doArquivo(String categoria, String nome) {
        String tipo = switch (categoria == null ? "" : categoria) {
            case "imagem" -> "da imagem";
            case "audio" -> "do áudio";
            case "video" -> "do vídeo";
            case "documento" -> "do documento";
            default -> "do arquivo";
        };
        return nome == null || nome.isBlank() ? tipo : tipo + " " + nome;
    }

    /** As imagens que o texto pode usar e o que avisar sobre elas (os passos de vídeo que ficaram sem quadro). */
    record Catalogo(List<ConferenciaDoTexto.ImagemDisponivel> imagens, List<String> avisos) {
    }

    /**
     * As imagens que o texto pode usar: os anexos e prints de imagem que foram analisados (o modelo sabe o que
     * mostram) e cujo arquivo ainda está no servidor, e os quadros dos passos dos vídeos analisados.
     */
    Catalogo catalogo(AtendimentoGuardado atendimento, LinhaDoTempo linha) {
        Map<UUID, ArquivoDoAtendimento> arquivos = atendimento.arquivos().stream()
                .collect(Collectors.toMap(ArquivoDoAtendimento::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<ConferenciaDoTexto.ImagemDisponivel> imagens = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        linha.todos()
                .filter(ItemDaLinha::arquivo)
                .filter(i -> "analisado".equals(i.situacao()) && i.arquivoId() != null)
                .forEach(i -> {
                    ArquivoDoAtendimento arquivo = arquivos.get(i.arquivoId());
                    if (arquivo == null) {
                        return;
                    }
                    if ("imagem".equals(i.categoria())) {
                        if (telaDeAcessoRemoto(i)) {
                            avisos.add(AVISO_DE_ACESSO_REMOTO.formatted(i.id()));
                        } else if (noServidor(arquivo)) {
                            imagens.add(new ConferenciaDoTexto.ImagemDisponivel(i.id(), arquivo.id(), arquivo.caminho(),
                                    i.resumo() != null ? i.resumo() : arquivo.nome()));
                        }
                    } else if ("video".equals(i.categoria())) {
                        quadrosDoVideo(atendimento.resumo().id(), i, arquivo, imagens, avisos);
                    }
                });
        return new Catalogo(imagens, avisos);
    }

    static final String AVISO_DE_ACESSO_REMOTO = "A imagem %s é a tela do programa de acesso remoto (mostra o ID e a "
            + "senha) e ficou fora do texto.";

    /**
     * A imagem é a foto ou o print da tela do programa de acesso remoto (pela descrição da análise): ela mostra o ID e
     * a senha e não entra no texto, na base nem no pacote para o Gemini.
     */
    static boolean telaDeAcessoRemoto(ItemDaLinha item) {
        if (!"imagem".equals(item.categoria())) {
            return false;
        }
        if (TelaDeAcessoRemoto.mostra(item.resumo())) {
            return true;
        }
        return item.texto() != null && item.texto().lines()
                .filter(l -> l.startsWith("O que mostra:") || l.startsWith("Tela:"))
                .anyMatch(TelaDeAcessoRemoto::mostra);
    }

    /** Os quadros do vídeo no catálogo (V1@02:15), tirando antes os que faltam. */
    private void quadrosDoVideo(UUID atendimentoId, ItemDaLinha item, ArquivoDoAtendimento arquivo,
                                List<ConferenciaDoTexto.ImagemDisponivel> imagens, List<String> avisos) {
        if (quadros == null || analises == null) {
            return;
        }
        Optional<JsonNode> analise = analises.maisRecente(arquivo.id()).flatMap(this::ler);
        if (analise.isEmpty()) {
            return;
        }
        QuadrosDoVideo.Preparo preparo = quadros.preparar(atendimentoId, arquivo.id(),
                noServidor(arquivo) ? arquivo.caminho() : null, analise.get());
        if (preparo.aviso() != null) {
            String aviso = preparo.semFfmpeg() ? QuadrosDoVideo.AVISO_SEM_FFMPEG.replace("do vídeo", "do vídeo " + item.id())
                    : item.id() + ": " + preparo.aviso();
            if (!avisos.contains(aviso)) {
                avisos.add(aviso);
            }
        }
        for (QuadrosDoVideo.Quadro quadro : quadros.quadros(atendimentoId, arquivo.id(), analise.get())) {
            if (quadro.disponivel()) {
                String legenda = "quadro do vídeo em " + quadro.instante()
                        + (quadro.passo().legenda() != null ? ": " + quadro.passo().legenda() : "");
                imagens.add(new ConferenciaDoTexto.ImagemDisponivel(item.id() + "@" + quadro.instante(), arquivo.id(),
                        quadro.caminho(), legenda, quadro.instanteMs()));
            }
        }
    }

    private boolean noServidor(ArquivoDoAtendimento arquivo) {
        return arquivo.caminho() != null && SituacaoDoArquivo.PRONTO.codigo().equals(arquivo.situacao())
                && Files.isRegularFile(pasta.localDoArquivo(arquivo.caminho()));
    }

    private Optional<JsonNode> ler(AnaliseGuardada analise) {
        try {
            return Optional.of(json.readTree(analise.resultado()));
        } catch (IOException e) {
            log.warn("A análise guardada do arquivo {} está ilegível", analise.arquivoId());
            return Optional.empty();
        }
    }

    private record Lida(JsonNode resultado, int tokensEntrada, int tokensSaida) {
    }

    private record Encurtado(String assunto, int tokensEntrada, int tokensSaida) {
    }

    /** Uma nova tentativa quando o JSON vem fora do esquema; na segunda, a falha segue. */
    private Lida gerarEConferir(String chave, PedidoAoModelo pedido, PromptsDaRedacao.Prompt prompt) {
        int entrada = 0;
        int saida = 0;
        for (int vez = 1; ; vez++) {
            try {
                RespostaDoModelo resposta = modelo.gerar(chave, pedido);
                entrada += resposta.tokensEntrada();
                saida += resposta.tokensSaida();
                return new Lida(gemini.lerObjeto(resposta.texto(), prompt.obrigatorios(), prompt.listas()), entrada, saida);
            } catch (FalhaDoModelo falha) {
                if (falha.tipo() != FalhaDoModelo.Tipo.RESPOSTA_INVALIDA || vez >= 2) {
                    throw falha;
                }
                log.info("Resposta do Gemini fora do formato ({}): tentando de novo", falha.getMessage());
            }
        }
    }

    /**
     * O assunto passou de 150 caracteres com o prefixo: pede um mais curto. Se o pedido não der certo, fica o
     * original (quem chama corta na última palavra).
     */
    private Encurtado encurtar(String chave, String assunto, String contato) {
        int limite = ConferenciaDoTexto.limiteDoAssunto(contato);
        PromptsDaRedacao.Prompt prompt = PromptsDaRedacao.ASSUNTO_CURTO;
        String instrucao = prompt.instrucao(Map.of("TAMANHO", String.valueOf(assunto.length()),
                "LIMITE", String.valueOf(limite), "ASSUNTO", assunto));
        PedidoAoModelo pedido = new PedidoAoModelo(configuracao.modelo(), PromptsDaRedacao.SISTEMA, instrucao,
                prompt.esquema(), null, false, configuracao.temperatura(), configuracao.raciocinio(), 1024);
        try {
            RespostaDoModelo resposta = modelo.gerar(chave, pedido);
            JsonNode lido = gemini.lerObjeto(resposta.texto(), prompt.obrigatorios(), prompt.listas());
            String novo = mascaramento.mascararTrechos(lido.path("assunto").asText("").strip());
            return new Encurtado(novo.isEmpty() ? assunto : novo, resposta.tokensEntrada(), resposta.tokensSaida());
        } catch (FalhaDoModelo falha) {
            log.info("Não foi possível encurtar o assunto ({}): fica cortado na última palavra", falha.tipo());
            return new Encurtado(assunto, 0, 0);
        }
    }

    /** O JSON do Gemini nos registros da conferência (o que faltar vira vazio). */
    static ConferenciaDoTexto.Bruto bruto(JsonNode lido) {
        return bruto(lido, ModeloDoTexto.RESUMO);
    }

    /**
     * No resumo, os trechos vêm em "blocos"; nos chamados para o desenvolvimento, em "secoes" (uma lista de trechos
     * por parte), e entram na ordem do modelo.
     */
    static ConferenciaDoTexto.Bruto bruto(JsonNode lido, ModeloDoTexto modelo) {
        List<ConferenciaDoTexto.BlocoBruto> blocos;
        if (modelo.comSecoes()) {
            JsonNode secoes = lido.path("secoes");
            if (!secoes.isObject()) {
                throw new FalhaDoModelo(FalhaDoModelo.Tipo.RESPOSTA_INVALIDA, "faltou o campo secoes");
            }
            Map<String, List<ConferenciaDoTexto.BlocoBruto>> porSecao = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> secao : secoes.properties()) {
                porSecao.put(secao.getKey(), blocos(secao.getValue()));
            }
            blocos = modelo.ordenar(porSecao);
        } else {
            blocos = blocos(lido.path("blocos"));
        }
        Map<String, String> base = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> campo : lido.path("base").properties()) {
            base.put(campo.getKey(), campo.getValue().isNull() ? "" : campo.getValue().asText(""));
        }
        List<String> variantes = new ArrayList<>();
        lido.path("variantes").forEach(v -> variantes.add(v.asText()));
        return new ConferenciaDoTexto.Bruto(lido.path("assunto").asText(""), blocos, base,
                lido.path("telaFuncionalidade").asText(""), variantes);
    }

    private static List<ConferenciaDoTexto.BlocoBruto> blocos(JsonNode lista) {
        List<ConferenciaDoTexto.BlocoBruto> blocos = new ArrayList<>();
        for (JsonNode b : lista) {
            List<String> fontes = new ArrayList<>();
            b.path("fontes").forEach(f -> fontes.add(f.asText()));
            List<ConferenciaDoTexto.ImagemBruta> imagens = new ArrayList<>();
            for (JsonNode i : b.path("imagens")) {
                imagens.add(new ConferenciaDoTexto.ImagemBruta(i.path("id").asText(null), i.path("legenda").asText(null)));
            }
            blocos.add(new ConferenciaDoTexto.BlocoBruto(b.path("tipo").asText(null), b.path("texto").asText(""), fontes, imagens));
        }
        return blocos;
    }

    private static boolean semValor(String texto) {
        return texto == null || texto.isBlank() || texto.strip().equalsIgnoreCase("não identificado");
    }
}
