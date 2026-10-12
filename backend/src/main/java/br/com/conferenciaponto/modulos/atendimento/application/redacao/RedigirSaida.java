package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import br.com.conferenciaponto.modulos.atendimento.application.ia.ChamadasAoGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.AtendimentoGuardado;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoArquivo;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Mascaramento;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
 * passa pela conferência do servidor (fontes, imagens, assunto, dados de acesso) e vira HTML montado aqui.
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
    private final Mascaramento mascaramento = new Mascaramento();

    public RedigirSaida(Saidas saidas, Atendimentos atendimentos, ChamadasAoGemini gemini, ModeloDeIA modelo,
                        PastaDosAtendimentos pasta, ConfiguracaoDaRedacao configuracao, ObjectMapper json, Clock clock) {
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
        List<ConferenciaDoTexto.ImagemDisponivel> imagens = imagensDisponiveis(atendimento.get(), linha.get());
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
            ConferenciaDoTexto conferencia = new ConferenciaDoTexto(
                    linha.get().todos().map(ItemDaLinha::id).collect(Collectors.toSet()), imagens,
                    mascaramento::mascararTrechos, UUID::randomUUID);
            ModeloDoTexto modeloDoTexto = ModeloDoTexto.de(saida.tipo());
            ConferenciaDoTexto.Conferido conferido = conferencia.conferir(bruto(lida.resultado(), modeloDoTexto), contato);
            TextoRedigido texto = conferido.texto().comBlocos(modeloDoTexto.completar(conferido.texto().blocos()));
            if (semValor(texto.telaFuncionalidade())) {
                // a sugestão pelos códigos lidos nas imagens
                texto = texto.comTela(SugestaoDaTela.de(linha.get()).orElse(texto.telaFuncionalidade()));
            }
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
     * As imagens que o texto pode usar: os anexos e prints de imagem que foram analisados (o modelo sabe o que
     * mostram) e cujo arquivo ainda está no servidor.
     */
    List<ConferenciaDoTexto.ImagemDisponivel> imagensDisponiveis(AtendimentoGuardado atendimento, LinhaDoTempo linha) {
        Map<UUID, ArquivoDoAtendimento> arquivos = atendimento.arquivos().stream()
                .collect(Collectors.toMap(ArquivoDoAtendimento::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<ConferenciaDoTexto.ImagemDisponivel> imagens = new ArrayList<>();
        linha.todos()
                .filter(ItemDaLinha::arquivo)
                .filter(i -> "imagem".equals(i.categoria()) && "analisado".equals(i.situacao()) && i.arquivoId() != null)
                .forEach(i -> {
                    ArquivoDoAtendimento arquivo = arquivos.get(i.arquivoId());
                    if (arquivo != null && arquivo.caminho() != null
                            && SituacaoDoArquivo.PRONTO.codigo().equals(arquivo.situacao())
                            && Files.isRegularFile(pasta.localDoArquivo(arquivo.caminho()))) {
                        imagens.add(new ConferenciaDoTexto.ImagemDisponivel(i.id(), arquivo.id(), arquivo.caminho(),
                                i.resumo() != null ? i.resumo() : arquivo.nome()));
                    }
                });
        return imagens;
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
