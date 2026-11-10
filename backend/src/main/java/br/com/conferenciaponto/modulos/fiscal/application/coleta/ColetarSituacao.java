package br.com.conferenciaponto.modulos.fiscal.application.coleta;

import br.com.conferenciaponto.modulos.fiscal.domain.DetectorDeIncidentes;
import br.com.conferenciaponto.modulos.fiscal.domain.EstadoAtual;
import br.com.conferenciaponto.modulos.fiscal.domain.Incidente;
import br.com.conferenciaponto.modulos.fiscal.domain.Leitura;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor.Aviso;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor.Rodada;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor.RodadaConcluida;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor.SituacaoDaFonte;
import br.com.conferenciaponto.modulos.fiscal.domain.ResumoDoAutorizador;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uma rodada de coleta: lê todas as fontes ao mesmo tempo (cada uma com tempo limite; uma que falha não para as
 * outras), resume cada autorizador, compara com o estado anterior e grava tudo de uma vez. Sem nenhuma fonte que
 * tenha alcançado alguém lá fora, a rodada é "sem internet neste computador": as leituras ficam sem informação e
 * nenhum incidente abre ou fecha.
 *
 * <p>Criado na configuração do módulo, que monta a lista das fontes a partir do fiscal.yml.
 */
public class ColetarSituacao {

    private static final Logger log = LoggerFactory.getLogger(ColetarSituacao.class);
    static final String SEM_INTERNET = "Sem internet neste computador: nenhuma fonte respondeu nesta rodada.";

    private final List<FonteDeLeituras> fontes;
    private final RegistroDoMonitor registro;
    private final ConfiguracaoDoMonitor configuracao;
    private final Clock clock;
    private final DetectorDeIncidentes detector = new DetectorDeIncidentes();
    private final ExecutorService executor;

    public ColetarSituacao(List<FonteDeLeituras> fontes, RegistroDoMonitor registro, ConfiguracaoDoMonitor configuracao,
                           Clock clock) {
        this.fontes = List.copyOf(fontes);
        this.registro = registro;
        this.configuracao = configuracao;
        this.clock = clock;
        AtomicInteger numero = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(Math.max(1, Math.min(6, this.fontes.size())), r -> {
            Thread t = new Thread(r, "fiscal-fonte-" + numero.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Uma rodada agora (a automática e o "Ler agora" da tela não rodam ao mesmo tempo). */
    public synchronized Rodada executar() {
        Instant inicio = clock.instant();
        List<FonteDeLeituras.Resultado> resultados = lerTodas();
        boolean semInternet = !resultados.isEmpty() && resultados.stream().noneMatch(FonteDeLeituras.Resultado::alcancou);

        List<Leitura> servicos = new ArrayList<>();
        for (FonteDeLeituras.Resultado r : resultados) {
            for (Leitura l : r.leituras()) {
                if (!l.alvo().ehGeral()) {
                    servicos.add(semInternet ? l.semInformacao(SEM_INTERNET) : l);
                }
            }
        }
        List<Leitura> leituras = new ArrayList<>(servicos);
        leituras.addAll(ResumoDoAutorizador.resumir(servicos));

        // o que já era acompanhado e não veio nesta rodada (a fonte falhou): sem informação, sem abrir nem fechar nada
        Map<String, EstadoAtual> anteriores = registro.estados();
        Set<String> lidas = new HashSet<>();
        leituras.forEach(l -> lidas.add(l.alvo().chave()));
        String motivo = semInternet ? SEM_INTERNET : "A fonte não trouxe este serviço nesta rodada.";
        anteriores.forEach((chave, estado) -> {
            if (!lidas.contains(chave)) {
                leituras.add(estado.leitura().semInformacao(motivo));
            }
        });

        Map<String, Incidente> abertos = registro.incidentesAbertos();
        DetectorDeIncidentes.Resultado resultado = detector.aplicar(anteriores, abertos, leituras, inicio);
        Instant fim = clock.instant();

        List<SituacaoDaFonte> situacaoDasFontes = new ArrayList<>();
        List<Aviso> avisos = new ArrayList<>();
        for (FonteDeLeituras.Resultado r : resultados) {
            situacaoDasFontes.add(new SituacaoDaFonte(r.fonte(), inicio, r.erro() == null ? inicio : null, r.erro()));
            if (r.documentoDoAviso() != null && r.alcancou() && r.erro() == null) {
                avisos.add(new Aviso(r.documentoDoAviso(), r.aviso(), inicio, inicio)); // sem texto = o aviso saiu do painel
            }
        }
        int comProblema = (int) situacaoDasFontes.stream().filter(SituacaoDaFonte::comProblema).count();
        Rodada rodada = new Rodada(inicio, fim, leituras.size(), comProblema, semInternet);
        List<Leitura> gerais = leituras.stream().filter(l -> l.alvo().ehGeral()).toList();
        registro.gravar(new RodadaConcluida(rodada, resultado.estados(), resultado.mudancas(), resultado.incidentes(),
                gerais, situacaoDasFontes, avisos));

        long ruins = gerais.stream().filter(l -> l.situacao().ruim()).count();
        if (semInternet) {
            log.warn("Monitor fiscal: rodada sem internet ({} fontes sem resposta)", resultados.size());
        } else {
            log.info("Monitor fiscal: {} leituras, {} autorizador(es) com problema, {} mudança(s), {} fonte(s) com problema",
                    leituras.size(), ruins, resultado.mudancas().size(), comProblema);
        }
        return rodada;
    }

    /** Apaga o histórico que passou do prazo (os incidentes ficam). */
    public void limparHistorico() {
        Instant agora = clock.instant();
        ConfiguracaoDoMonitor.Retencao r = configuracao.retencao();
        registro.limpar(agora.minus(Duration.ofDays(r.diasResumo())), agora.minus(Duration.ofDays(r.diasMudancas())),
                agora.minus(Duration.ofDays(r.diasRodadas())));
    }

    private List<FonteDeLeituras.Resultado> lerTodas() {
        Instant agora = clock.instant();
        Map<FonteDeLeituras, Future<FonteDeLeituras.Resultado>> pedidos = new LinkedHashMap<>();
        for (FonteDeLeituras fonte : fontes) {
            pedidos.put(fonte, executor.submit(() -> fonte.ler(agora)));
        }
        // a fonte tem o tempo limite dela; isto é só a rede de segurança para uma fonte que não volta
        long limite = configuracao.coleta().tempoLimite().multipliedBy(3).toMillis();
        long ate = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limite);
        List<FonteDeLeituras.Resultado> resultados = new ArrayList<>();
        pedidos.forEach((fonte, pedido) -> {
            try {
                long resta = Math.max(1, TimeUnit.NANOSECONDS.toMillis(ate - System.nanoTime()));
                resultados.add(pedido.get(resta, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                pedido.cancel(true);
                resultados.add(FonteDeLeituras.Resultado.falhou(fonte.nome(), "Não terminou a tempo."));
            } catch (ExecutionException e) {
                log.warn("Monitor fiscal: a fonte {} falhou: {}", fonte.nome(), String.valueOf(e.getCause()));
                resultados.add(FonteDeLeituras.Resultado.falhou(fonte.nome(), "Erro inesperado ao ler a fonte."));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                resultados.add(FonteDeLeituras.Resultado.falhou(fonte.nome(), "Interrompida."));
            }
        });
        return resultados;
    }

    @PreDestroy
    void parar() {
        executor.shutdownNow();
    }
}
