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

    /** As respostas válidas de cada esquema (imagem, áudio, documento), com dados de acesso para a máscara tirar. */
    public static String respostaPadrao(PedidoAoModelo pedido) {
        String esquema = pedido.esquemaJson();
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
