package br.com.conferenciaponto.modulos.atendimento;

import br.com.conferenciaponto.modulos.atendimento.domain.ia.ArquivoRemoto;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.FalhaDoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.ModeloDeIA;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.PedidoAoModelo;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.RespostaDoModelo;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Um Gemini de mentira, na memória: cada geração responde o que estiver na fila (ou a resposta padrão da categoria);
 * os arquivos "enviados" nascem na situação pedida e passam pelas seguintes, uma a cada consulta.
 */
public final class ModeloDeMentira implements ModeloDeIA {

    /** Um pedido de geração e a chave usada. */
    public record Geracao(String chave, PedidoAoModelo pedido) {
    }

    private final Deque<Object> fila = new ArrayDeque<>();
    private volatile Function<PedidoAoModelo, String> padrao = p -> respostaPadrao(p);
    private final List<Geracao> geracoes = new CopyOnWriteArrayList<>();
    private final List<String> enviados = new CopyOnWriteArrayList<>();
    private final List<String> nomesParaExibir = new CopyOnWriteArrayList<>();
    private final List<String> consultados = new CopyOnWriteArrayList<>();
    private final List<String> apagados = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequencia = new AtomicInteger();
    private volatile List<ArquivoRemoto.Estado> estados = List.of(ArquivoRemoto.Estado.ATIVO);
    private final java.util.Map<String, Deque<ArquivoRemoto.Estado>> situacoes = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * O resumo padrão: cita o primeiro anexo (A1) com imagem, uma fonte e uma imagem que não existem e um dado de
     * acesso remoto, para a conferência do servidor tirar.
     */
    public static final String RESUMO = """
            {"assunto":"Erro ao emitir a nota fiscal na tela de vendas",
             "blocos":[
               {"tipo":"paragrafo","texto":"A cliente relatou que não conseguia emitir a nota fiscal e enviou o print do erro.",
                "fontes":["M1","A1"],"imagens":[{"id":"A1","legenda":"tela Pedidos com o erro"}]},
               {"tipo":"passo","texto":"Acessei o computador pelo AnyDesk 123 456 789.","fontes":["M1"],
                "imagens":[{"id":"A9","legenda":"tela inventada"}]},
               {"tipo":"passo","texto":"Emiti a nota de novo.","fontes":["M999"],"imagens":[]},
               {"tipo":"paragrafo","texto":"A nota foi emitida.","fontes":["M1"],"imagens":[]}],
             "base":{"cliente":"Loja Exemplo","modulo":"Vendas","tela":"Pedidos","codigoTela":"VEN010",
                     "sintoma":"erro ao emitir a nota","mensagemErro":"Erro ao gravar o pedido","causa":"não identificado",
                     "resolucao":"nota emitida de novo"},
             "telaFuncionalidade":"Pedidos (VEN010)"}""";

    /**
     * O chamado de erro padrão: as partes fora de ordem, uma parte que não existe, a causa vazia (o sistema escreve
     * "Causa não identificada.") e a Tela/Funcionalidade vazia (o sistema sugere pela tela lida no print).
     */
    public static final String DEV_ERRO = """
            {"assunto":"Erro ao gravar pedido de venda com desconto","variantes":["sem_causa_identificada"],
             "secoes":{
               "pedido":[{"tipo":"paragrafo","texto":"Verificar e corrigir a gravação do pedido.","fontes":["M1"],"imagens":[]}],
               "relato":[{"tipo":"paragrafo","texto":"Ao gravar o pedido, aparece uma mensagem de erro.","fontes":["M1","A1"],
                          "imagens":[{"id":"A1","legenda":"tela Pedidos com o erro"}]}],
               "caminhoTela":[{"tipo":"paragrafo","texto":"Vendas -> Pedidos (VEN010)","fontes":["A1"],"imagens":[]}],
               "passos":[{"tipo":"paragrafo","texto":"Abrir a tela Pedidos.","fontes":["A1"],"imagens":[]},
                         {"tipo":"paragrafo","texto":"Clicar em Gravar.","fontes":["A1"],"imagens":[]}],
               "resultadoObtido":[{"tipo":"paragrafo","texto":"Aparece \\"Erro ao gravar o pedido\\".","fontes":["A1"],"imagens":[]}],
               "causa":[],
               "comparacao":[],"resultadoEsperado":[],"dadosDoRegistro":[],"desdeQuando":[],"tentativas":[],
               "inventada":[{"tipo":"paragrafo","texto":"parte que não existe","fontes":["M1"],"imagens":[]}]},
             "base":{"cliente":"Loja Exemplo","modulo":"Vendas","tela":"Pedidos","codigoTela":"VEN010",
                     "sintoma":"erro ao gravar","mensagemErro":"Erro ao gravar o pedido","causa":"não identificado",
                     "resolucao":"enviado ao desenvolvimento"},
             "telaFuncionalidade":"não identificado"}""";

    /** O chamado de alteração padrão. */
    public static final String DEV_ALTERACAO = """
            {"assunto":"Novo campo de desconto máximo no pedido",
             "secoes":{
               "modulo":[{"tipo":"paragrafo","texto":"Vendas -> Pedidos (VEN010)","fontes":["A1"],
                          "imagens":[{"id":"A1","legenda":"tela Pedidos"}]}],
               "tipoDeSolicitacao":[{"tipo":"paragrafo","texto":"Novo campo.","fontes":["M1"],"imagens":[]}],
               "descricao":[{"tipo":"paragrafo","texto":"Limitar o desconto por vendedor.","fontes":["M1"],"imagens":[]}],
               "contexto":[],
               "comportamentoAtual":[{"tipo":"paragrafo","texto":"Hoje qualquer desconto é aceito.","fontes":["M1"],"imagens":[]}],
               "comportamentoEsperado":[{"tipo":"paragrafo","texto":"Desconto acima do limite pede senha do gerente.","fontes":["M1"],"imagens":[]}],
               "detalhamento":[{"tipo":"paragrafo","texto":"Campo Desconto máximo (%) no cadastro do vendedor.","fontes":["M1"],"imagens":[]}],
               "regras":[],"observacaoComercial":[],
               "pontosAConfirmar":[{"tipo":"paragrafo","texto":"O limite vale também para o gerente?","fontes":["M1"],"imagens":[]}]},
             "base":{"cliente":"Loja Exemplo","modulo":"Vendas","tela":"Pedidos","codigoTela":"VEN010",
                     "sintoma":"precisa limitar o desconto","mensagemErro":"não identificado","causa":"não identificado",
                     "resolucao":"enviado ao desenvolvimento"},
             "telaFuncionalidade":"Pedidos de Venda (VEN010)"}""";

    /**
     * O vídeo padrão: três passos (o segundo com a mensagem de erro e o quadro num instante próprio; o terceiro sem
     * instante de quadro, que fica no começo do passo), a narração com um dado de acesso remoto para a máscara tirar.
     */
    public static final String VIDEO = """
            {"tipoDoVideo":"problema","descricao":"Gravação da tela emitindo a nota fiscal com desconto","duracao":"00:40",
             "pedido":{"resumo":"não identificado","itens":[],"exemplos":[]},
             "passos":[
               {"instante":"00:02","tela":{"nome":"Pedidos","codigo":"VEN010","caminhoMenu":"Vendas > Pedidos"},
                "acoes":["clique em Novo"],"mensagens":[],"conteudoNaTela":[],"descricao":"Abre um pedido novo.","instanteDoQuadro":"00:03"},
               {"instante":"00:10","tela":{"nome":"Pedidos","codigo":"VEN010","caminhoMenu":"Vendas > Pedidos"},
                "acoes":["digitação de 10 no campo Desconto","clique no botão Gravar"],
                "mensagens":[{"texto":"Erro ao gravar o pedido","tipo":"erro"}],"conteudoNaTela":["Desconto: 10"],
                "descricao":"Grava e aparece o erro.",
                "instanteDoQuadro":"00:12"},
               {"instante":"00:30","tela":{"nome":"Pedidos","codigo":"VEN010","caminhoMenu":""},
                "acoes":["clique em OK"],"mensagens":[],"conteudoNaTela":[],"descricao":"Fecha a mensagem.",
                "instanteDoQuadro":"não identificado"}],
             "narracao":[{"inicio":"00:01","falante":"Falante 1","texto":"vou mostrar o erro, o anydesk é 123 456 789"}],
             "textoLegivel":true}""";

    /** Um vídeo em que o cliente explica um pedido (um relatório novo), com o modelo numa planilha. */
    public static final String VIDEO_DE_PEDIDO = """
            {"tipoDoVideo":"pedido","descricao":"A cliente mostra a planilha que a diretoria usa e o relatório atual",
             "duracao":"01:10",
             "pedido":{"resumo":"Criar um relatório de vendas por vendedor igual à planilha da diretoria",
                       "itens":["coluna Vendedor","coluna Total vendido no mês","filtro por período"],
                       "exemplos":["planilha com as colunas Vendedor, Meta, Total vendido"]},
             "passos":[
               {"instante":"00:05","tela":{"nome":"Vendas por Período","codigo":"VEN050","caminhoMenu":"Relatórios > Vendas"},
                "acoes":["clique em Visualizar"],"mensagens":[],"conteudoNaTela":["colunas: Data, Cliente, Valor"],
                "descricao":"Mostra o relatório atual.","instanteDoQuadro":"00:08"}],
             "narracao":[{"inicio":"00:01","falante":"Falante 1","texto":"a diretoria quer ver por vendedor, igual a planilha"}],
             "textoLegivel":true}""";

    /** As respostas válidas de cada esquema (imagem, áudio, documento, vídeo, resumo, chamados, assunto mais curto). */
    public static String respostaPadrao(PedidoAoModelo pedido) {
        String esquema = pedido.esquemaJson();
        if (esquema.contains("\"instanteDoQuadro\"")) {
            return VIDEO;
        }
        if (esquema.contains("\"secoes\"")) {
            return esquema.contains("\"resultadoObtido\"") ? DEV_ERRO : DEV_ALTERACAO;
        }
        if (esquema.contains("\"blocos\"")) {
            return RESUMO;
        }
        if (esquema.contains("\"assunto\"")) {
            return "{\"assunto\":\"Erro na nota fiscal\"}";
        }
        if (esquema.contains("\"trechos\"")) {
            return """
                    {"idioma":"pt-BR","duracao":"00:42","falantes":[{"rotulo":"Falante 1","papel":"cliente"}],
                     "trechos":[{"inicio":"00:00","falante":"Falante 1","texto":"O sistema travou na emissão da nota"},
                                {"inicio":"00:10","falante":"Falante 1","texto":"meu anydesk é 123 456 789"}],
                     "temTrechoInaudivel":false}""";
        }
        if (esquema.contains("\"tipoDocumento\"")) {
            return """
                    {"tipoDocumento":"relatório","descricao":"Relatório de vendas","conteudo":"Total: 1.234,56",
                     "dados":[{"rotulo":"Senha","valor":"abc123"}],"mensagens":[]}""";
        }
        return """
                {"descricao":"Tela de pedidos com erro","tela":{"nome":"Pedidos","codigo":"VEN010","caminhoMenu":"Vendas > Pedidos"},
                 "campos":[{"rotulo":"Nota","valor":"000123456"},{"rotulo":"ID","valor":"987 654 321"}],
                 "mensagens":[{"texto":"Erro ao gravar o pedido","tipo":"erro"}],"textoLegivel":true,
                 "regioes":[{"descricao":"mensagem","caixa":[100,200,300,400]}]}""";
    }

    // ------------------------------------------------------------------------------------------- roteiro

    /** A próxima geração devolve este texto (o JSON). */
    public synchronized ModeloDeMentira responder(String texto) {
        fila.addLast(texto);
        return this;
    }

    /** A próxima geração falha assim. */
    public synchronized ModeloDeMentira falhar(FalhaDoModelo falha) {
        fila.addLast(falha);
        return this;
    }

    public ModeloDeMentira padrao(Function<PedidoAoModelo, String> resposta) {
        this.padrao = resposta;
        return this;
    }

    public ModeloDeMentira estados(ArquivoRemoto.Estado... estados) {
        this.estados = List.of(estados);
        return this;
    }

    public List<Geracao> geracoes() {
        return List.copyOf(geracoes);
    }

    public List<String> enviados() {
        return List.copyOf(enviados);
    }

    public List<String> nomesParaExibir() {
        return List.copyOf(nomesParaExibir);
    }

    public List<String> consultados() {
        return List.copyOf(consultados);
    }

    public List<String> apagados() {
        return List.copyOf(apagados);
    }

    // ------------------------------------------------------------------------------------------- modelo

    @Override
    public ArquivoRemoto enviar(String chave, Path arquivo, String tipoDeMidia, long tamanho, String nomeParaExibir) {
        String nome = "files/m" + sequencia.incrementAndGet();
        enviados.add(nome);
        nomesParaExibir.add(nomeParaExibir);
        Deque<ArquivoRemoto.Estado> roteiro = new ArrayDeque<>(estados);
        situacoes.put(nome, roteiro);
        return new ArquivoRemoto(nome, "https://gemini/" + nome, tipoDeMidia, roteiro.peekFirst(),
                Instant.parse("2099-01-01T00:00:00Z"));
    }

    @Override
    public ArquivoRemoto consultar(String chave, String nome) {
        consultados.add(nome);
        Deque<ArquivoRemoto.Estado> roteiro = situacoes.get(nome);
        if (roteiro == null || apagados.contains(nome)) {
            throw new FalhaDoModelo(FalhaDoModelo.Tipo.ARQUIVO_FALHOU, "O arquivo não está mais no Gemini.");
        }
        if (roteiro.size() > 1) {
            roteiro.removeFirst();
        }
        return new ArquivoRemoto(nome, "https://gemini/" + nome, null, roteiro.peekFirst(), Instant.parse("2099-01-01T00:00:00Z"));
    }

    @Override
    public void apagar(String chave, String nome) {
        apagados.add(nome);
    }

    /** Os pedidos de vetor: a chave e o texto. */
    public record PedidoDeVetor(String chave, String modelo, int dimensao, String texto) {
    }

    private final List<PedidoDeVetor> vetoresPedidos = new CopyOnWriteArrayList<>();
    private final Deque<FalhaDoModelo> falhasDeVetor = new ArrayDeque<>();

    public List<PedidoDeVetor> vetoresPedidos() {
        return vetoresPedidos;
    }

    /** A próxima chamada de vetor falha assim. */
    public synchronized ModeloDeMentira falharVetor(FalhaDoModelo falha) {
        falhasDeVetor.add(falha);
        return this;
    }

    /**
     * Um vetor de "palavras": cada palavra do texto soma 1 numa posição (sempre a mesma para a mesma palavra), e o
     * vetor sai normalizado. Textos com as mesmas palavras ficam parecidos.
     */
    @Override
    public float[] vetor(String chave, String modelo, int dimensao, String texto) {
        vetoresPedidos.add(new PedidoDeVetor(chave, modelo, dimensao, texto));
        synchronized (this) {
            if (!falhasDeVetor.isEmpty()) {
                throw falhasDeVetor.removeFirst();
            }
        }
        return vetorDePalavras(texto, dimensao);
    }

    public static float[] vetorDePalavras(String texto, int dimensao) {
        float[] v = new float[dimensao];
        for (String palavra : texto.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (palavra.length() > 2 && !palavra.equals("title") && !palavra.equals("text") && !palavra.equals("task")
                    && !palavra.equals("query") && !palavra.equals("search") && !palavra.equals("result")) {
                v[Math.floorMod(palavra.hashCode(), dimensao)] += 1;
            }
        }
        double soma = 0;
        for (float x : v) {
            soma += x * x;
        }
        if (soma == 0) {
            v[0] = 1;
            return v;
        }
        for (int k = 0; k < dimensao; k++) {
            v[k] = (float) (v[k] / Math.sqrt(soma));
        }
        return v;
    }

    @Override
    public RespostaDoModelo gerar(String chave, PedidoAoModelo pedido) {
        geracoes.add(new Geracao(chave, pedido));
        Object proximo;
        synchronized (this) {
            proximo = fila.isEmpty() ? null : fila.removeFirst();
        }
        if (proximo instanceof FalhaDoModelo falha) {
            throw falha;
        }
        String texto = proximo instanceof String s ? s : padrao.apply(pedido);
        return new RespostaDoModelo(texto, 1000, 200);
    }
}
