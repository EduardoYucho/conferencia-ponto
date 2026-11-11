package br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta;

import br.com.conferenciaponto.modulos.fiscal.application.coleta.FonteDeLeituras;
import br.com.conferenciaponto.modulos.fiscal.domain.Alvo;
import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import br.com.conferenciaponto.modulos.fiscal.domain.Leitura;
import br.com.conferenciaponto.modulos.fiscal.domain.Origem;
import br.com.conferenciaponto.modulos.fiscal.domain.ProblemaLocal;
import br.com.conferenciaponto.modulos.fiscal.domain.Situacao;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Os testes de conexão dos autorizadores que não têm painel público (fiscal.conexoes). Um endereço usado por mais
 * de um documento (ex.: a SEFAZ-SP na NF-e e no CT-e) é testado uma vez só por rodada. Muitos endereços sem resposta
 * na mesma leitura (a internet deste computador oscilando) ficam sem informação, não fora.
 */
public class TestesDeConexao implements FonteDeLeituras {

    /** O serviço com que o teste aparece na tela. */
    public static final String SERVICO = "Conexão";
    static final String NINGUEM_RESPONDEU = "Nenhum servidor respondeu ao teste de conexão.";
    /** A partir de quantos servidores (e de que parte deles) sem resposta na mesma leitura a culpa é da rede local. */
    static final int MINIMO_PARA_REDE = 3;
    static final double FRACAO_PARA_REDE = 0.3;

    private record Destino(Documento documento, String autorizador, String endereco) {
    }

    private final List<Destino> destinos = new ArrayList<>();
    private final SondaDeConexao sonda;
    private final int paralelos;
    private final Duration tempoLimite;

    /** @param conexoes documento → autorizador → endereço ("host" ou "host:porta") */
    public TestesDeConexao(Map<Documento, Map<String, String>> conexoes, SondaDeConexao sonda, int paralelos,
                           Duration tempoLimite) {
        conexoes.forEach((documento, porAutorizador) -> porAutorizador.forEach((autorizador, endereco) -> {
            if (endereco != null && !endereco.isBlank()) {
                destinos.add(new Destino(documento, autorizador.strip().toUpperCase(Locale.ROOT), endereco.strip()));
            }
        }));
        this.sonda = sonda;
        this.paralelos = Math.max(1, paralelos);
        this.tempoLimite = tempoLimite;
    }

    public boolean vazio() {
        return destinos.isEmpty();
    }

    @Override
    public String nome() {
        return "testes-de-conexao";
    }

    @Override
    public Resultado ler(Instant agora) {
        Map<String, Future<SondaDeConexao.Resposta>> porEndereco = new LinkedHashMap<>();
        AtomicInteger numero = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(paralelos, Math.max(1, destinos.size())), r -> {
            Thread t = new Thread(r, "fiscal-conexao-" + numero.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        try {
            for (Destino d : destinos) {
                String chave = d.endereco().toLowerCase(Locale.ROOT);
                porEndereco.computeIfAbsent(chave, e -> executor.submit(() -> sonda.testar(d.endereco())));
            }
            // cada teste tem o próprio limite; este é o da fila inteira (endereços esperando thread livre)
            long ate = System.nanoTime() + tempoLimite.multipliedBy(2).toNanos();
            Map<String, SondaDeConexao.Resposta> respostas = new LinkedHashMap<>();
            porEndereco.forEach((endereco, pedido) -> respostas.put(endereco, esperar(pedido, ate)));

            // muitos servidores sem resposta ao mesmo tempo: é a internet deste computador, não a SEFAZ (autorizadores
            // independentes não caem todos juntos); os que falharam ficam sem informação nesta leitura
            long falharam = respostas.values().stream().filter(TestesDeConexao::falhou).count();
            boolean redeOscilando = falharam >= Math.max(MINIMO_PARA_REDE, Math.ceil(respostas.size() * FRACAO_PARA_REDE));
            if (redeOscilando) {
                respostas.replaceAll((endereco, r) -> falhou(r)
                        ? new SondaDeConexao.Resposta(Situacao.SEM_INFORMACAO, ProblemaLocal.REDE_OSCILANDO, null, false) : r);
            }

            List<Leitura> leituras = new ArrayList<>();
            boolean alguemRespondeu = false;
            for (Destino d : destinos) {
                SondaDeConexao.Resposta r = respostas.get(d.endereco().toLowerCase(Locale.ROOT));
                alguemRespondeu |= r.respondeu();
                leituras.add(new Leitura(new Alvo(d.documento(), d.autorizador(), SERVICO), Origem.TESTE_DE_CONEXAO,
                        r.situacao(), r.detalhe(), null, r.tempoMs(), agora));
            }
            String erro = destinos.isEmpty() ? null
                    : !alguemRespondeu ? NINGUEM_RESPONDEU
                    : redeOscilando ? "Muitos servidores sem resposta ao mesmo tempo (%d de %d): provavelmente a internet deste computador."
                            .formatted(falharam, respostas.size())
                    : null;
            return new Resultado(nome(), alguemRespondeu, erro, leituras, null, null);
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean falhou(SondaDeConexao.Resposta r) {
        return r.situacao() == Situacao.FORA || r.situacao() == Situacao.SEM_INFORMACAO;
    }

    private static SondaDeConexao.Resposta esperar(Future<SondaDeConexao.Resposta> pedido, long ate) {
        try {
            long resta = Math.max(1, TimeUnit.NANOSECONDS.toMillis(ate - System.nanoTime()));
            return pedido.get(resta, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pedido.cancel(true);
            return new SondaDeConexao.Resposta(Situacao.SEM_INFORMACAO, "O teste não terminou a tempo.", null, false);
        } catch (ExecutionException e) {
            return new SondaDeConexao.Resposta(Situacao.SEM_INFORMACAO, "Erro inesperado no teste de conexão.", null,
                    false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SondaDeConexao.Resposta(Situacao.SEM_INFORMACAO, "Teste interrompido.", null, false);
        }
    }
}
