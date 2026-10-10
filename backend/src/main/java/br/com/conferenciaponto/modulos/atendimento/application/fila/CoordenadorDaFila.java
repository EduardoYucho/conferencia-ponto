package br.com.conferenciaponto.modulos.atendimento.application.fila;

import br.com.conferenciaponto.modulos.atendimento.domain.fila.AcompanhamentoDaFila;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ExecutorDeTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.ResultadoDaTarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefa;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.TipoDeTarefa;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * O motor da fila do gerador. Uma thread varre a tabela a cada {@code atendimento.fila.varredura}, pega as tarefas
 * prontas ({@code FOR UPDATE SKIP LOCKED}) e as entrega a um conjunto limitado de trabalhadores: no máximo
 * {@code trabalhadores} ao mesmo tempo, {@code por-usuario} de cada pessoa e o limite de cada tipo (ex.: 4 downloads).
 *
 * <ul>
 *   <li>Falha temporária: volta para a fila com espera crescente (10 s, 20 s, 40 s… até 10 min), respeitando o tempo
 *       pedido pelo outro lado; na última tentativa vira falha definitiva.</li>
 *   <li>Falha de um item não para os outros.</li>
 *   <li>Na subida, o que estava "executando" volta para a fila (o serviço parou no meio). Durante a execução o prazo
 *       é renovado; uma tarefa presa além do prazo volta sozinha.</li>
 * </ul>
 *
 * O log registra ids, tipos e códigos; nunca conteúdo.
 */
@Component
public class CoordenadorDaFila {

    private static final Logger log = LoggerFactory.getLogger(CoordenadorDaFila.class);

    private final Tarefas tarefas;
    private final Map<TipoDeTarefa, ExecutorDeTarefa> executores = new EnumMap<>(TipoDeTarefa.class);
    private final Map<TipoDeTarefa, Integer> limitesPorTipo = new EnumMap<>(TipoDeTarefa.class);
    private final AcompanhamentoDaFila acompanhamento;
    private final Clock clock;
    private final int trabalhadores;
    private final int porUsuario;
    private final Duration prazo;
    private final Duration varredura;
    private final Duration esperaInicial;
    private final Duration esperaMaxima;
    private final boolean ativa;

    /** Ids das tarefas que este processo está executando agora. */
    private final Set<Long> emExecucao = ConcurrentHashMap.newKeySet();
    /** Ligado por {@link #parar()}: nenhuma varredura pega tarefa depois disso. */
    private volatile boolean parando;
    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService relogio;

    public CoordenadorDaFila(Tarefas tarefas, List<ExecutorDeTarefa> executores, AcompanhamentoDaFila acompanhamento,
                             Clock clock,
                             @Value("${atendimento.fila.trabalhadores:8}") int trabalhadores,
                             @Value("${atendimento.fila.por-usuario:4}") int porUsuario,
                             @Value("${atendimento.fila.prazo-execucao:15m}") Duration prazo,
                             @Value("${atendimento.fila.varredura:2s}") Duration varredura,
                             @Value("${atendimento.fila.espera-inicial:10s}") Duration esperaInicial,
                             @Value("${atendimento.fila.espera-maxima:10m}") Duration esperaMaxima,
                             @Value("${atendimento.fila.ativa:true}") boolean ativa) {
        this.tarefas = tarefas;
        for (ExecutorDeTarefa executor : executores) {
            this.executores.put(executor.tipo(), executor);
            if (executor.limiteSimultaneo() > 0) {
                limitesPorTipo.put(executor.tipo(), executor.limiteSimultaneo());
            }
        }
        this.acompanhamento = acompanhamento;
        this.clock = clock;
        this.trabalhadores = Math.max(1, trabalhadores);
        this.porUsuario = Math.max(1, porUsuario);
        this.prazo = prazo;
        this.varredura = varredura;
        this.esperaInicial = esperaInicial;
        this.esperaMaxima = esperaMaxima;
        this.ativa = ativa;
        AtomicInteger numero = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(this.trabalhadores, this.trabalhadores, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "atendimentos-fila-" + numero.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        this.pool.allowCoreThreadTimeOut(true);
        this.relogio = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "atendimentos-fila-coordenador");
            t.setDaemon(true);
            return t;
        });
    }

    /** Na subida: devolve o que ficou "executando" e começa a varrer. */
    @EventListener(ApplicationReadyEvent.class)
    public void iniciar() {
        if (!ativa) {
            log.info("Fila do gerador desligada (atendimento.fila.ativa = false)");
            return;
        }
        int devolvidas = tarefas.devolverTodasEmExecucao();
        if (devolvidas > 0) {
            log.info("Fila do gerador: {} tarefa(s) que estavam em execução voltaram para a fila", devolvidas);
        }
        relogio.scheduleWithFixedDelay(this::varrerSemFalhar, 1000, varredura.toMillis(), TimeUnit.MILLISECONDS);
        long manutencao = Math.max(10_000, Math.min(60_000, prazo.toMillis() / 3));
        relogio.scheduleWithFixedDelay(this::manutencaoSemFalhar, manutencao, manutencao, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void parar() {
        synchronized (this) {
            parando = true; // espera a varredura em andamento (ela entrega o que pegou antes de o pool fechar)
        }
        relogio.shutdownNow();
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                pool.shutdownNow(); // o que ficar "executando" volta para a fila na próxima subida
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Uma varredura: entrega o que couber nos trabalhadores livres.
     *
     * @return quantas tarefas foram entregues
     */
    public synchronized int varrer() {
        if (parando) {
            return 0;
        }
        int livres = trabalhadores - emExecucao.size();
        if (livres <= 0) {
            return 0;
        }
        List<Tarefa> pegas = tarefas.pegar(livres, porUsuario, limitesPorTipo, clock.instant(), prazo);
        for (Tarefa tarefa : pegas) {
            emExecucao.add(tarefa.id());
            try {
                pool.execute(() -> executar(tarefa));
            } catch (RuntimeException e) {
                emExecucao.remove(tarefa.id());
                devolverSemFalhar(tarefa, e); // não fica "executando" até a próxima subida
            }
        }
        return pegas.size();
    }

    private void devolverSemFalhar(Tarefa tarefa, RuntimeException motivo) {
        try {
            tarefas.devolver(tarefa.id(), clock.instant());
            log.warn("Tarefa {} não pôde ser entregue e voltou para a fila: {}", tarefa.id(), motivo.toString());
        } catch (RuntimeException e) {
            log.warn("Tarefa {} não pôde ser entregue nem devolvida (volta na próxima subida): {}", tarefa.id(), e.toString());
        }
    }

    /** Quantas tarefas este processo está executando agora. */
    public int emExecucao() {
        return emExecucao.size();
    }

    /** Executa na thread atual (usado pelos trabalhadores e pelos testes). */
    void executar(Tarefa tarefa) {
        ResultadoDaTarefa resultado;
        try {
            acompanhamento.aoComecar(tarefa);
            ExecutorDeTarefa executor = executores.get(tarefa.tipo());
            resultado = executor == null
                    ? ResultadoDaTarefa.definitiva("TAREFA_SEM_EXECUTOR", "Esta etapa ainda não existe nesta versão do sistema.")
                    : executor.executar(tarefa);
        } catch (RuntimeException e) {
            StackTraceElement onde = e.getStackTrace().length == 0 ? null : e.getStackTrace()[0];
            log.error("Tarefa {} ({}) falhou sem tratamento: {} em {}", tarefa.id(), tarefa.tipo().codigo(),
                    e.getClass().getSimpleName(), onde);
            resultado = ResultadoDaTarefa.temporaria("ERRO_INTERNO",
                    "Aconteceu um erro inesperado ao processar este item; uma nova tentativa será feita.");
        }
        try {
            ResultadoDaTarefa efetivo = registrar(tarefa, resultado);
            acompanhamento.aoTerminar(tarefa, efetivo);
        } catch (RuntimeException e) {
            log.error("Não foi possível registrar o fim da tarefa {} ({}): {}", tarefa.id(), tarefa.tipo().codigo(), e.toString());
        } finally {
            emExecucao.remove(tarefa.id());
        }
        if (ativa && !relogio.isShutdown()) {
            relogio.execute(this::varrerSemFalhar); // um trabalhador ficou livre: não espera a próxima varredura
        }
    }

    private ResultadoDaTarefa registrar(Tarefa tarefa, ResultadoDaTarefa resultado) {
        Instant agora = clock.instant();
        if (resultado instanceof ResultadoDaTarefa.FalhaDefinitiva f) {
            tarefas.falhar(tarefa.id(), f.codigo(), f.mensagem(), agora);
            log.info("Tarefa {} ({}) falhou: {}", tarefa.id(), tarefa.tipo().codigo(), f.codigo());
            return f;
        }
        if (resultado instanceof ResultadoDaTarefa.FalhaTemporaria t) {
            if (tarefa.ultimaTentativa()) {
                tarefas.falhar(tarefa.id(), t.codigo(), t.mensagem(), agora);
                log.info("Tarefa {} ({}) falhou na última tentativa ({}): {}", tarefa.id(), tarefa.tipo().codigo(),
                        tarefa.tentativa(), t.codigo());
                return ResultadoDaTarefa.definitiva(t.codigo(), t.mensagem());
            }
            Duration espera = espera(tarefa.tentativa(), t.esperar());
            tarefas.adiar(tarefa.id(), agora.plus(espera), t.codigo(), t.mensagem(), agora);
            log.info("Tarefa {} ({}) vai tentar de novo em {} s ({}, tentativa {} de {})", tarefa.id(),
                    tarefa.tipo().codigo(), espera.toSeconds(), t.codigo(), tarefa.tentativa(), tarefa.maxTentativas());
            return t;
        }
        if (resultado instanceof ResultadoDaTarefa.Pausar p) {
            tarefas.pausar(tarefa.id(), p.codigo(), p.mensagem(), agora);
            log.warn("Tarefa {} ({}) pausou o atendimento {}: {}", tarefa.id(), tarefa.tipo().codigo(),
                    tarefa.atendimentoId(), p.codigo());
            return p;
        }
        tarefas.concluir(tarefa.id(), agora);
        return resultado;
    }

    /** Espera crescente (dobra a cada tentativa) com um pouco de sorteio, sem passar do máximo. */
    Duration espera(int tentativa, Duration pedida) {
        long base = esperaInicial.toMillis() << Math.min(20, Math.max(0, tentativa - 1));
        long limitada = Math.min(base, esperaMaxima.toMillis());
        long comSorteio = limitada + ThreadLocalRandom.current().nextLong(Math.max(1, limitada / 5));
        long resultado = Math.min(comSorteio, esperaMaxima.toMillis());
        if (pedida != null && pedida.toMillis() > resultado) {
            resultado = Math.min(pedida.toMillis(), Duration.ofHours(1).toMillis());
        }
        return Duration.ofMillis(resultado);
    }

    private void varrerSemFalhar() {
        try {
            varrer();
        } catch (RuntimeException e) {
            log.warn("A varredura da fila do gerador falhou (tenta de novo na próxima): {}", e.toString());
        }
    }

    /** Renova o prazo do que está rodando e devolve o que ficou preso além do prazo. */
    private void manutencaoSemFalhar() {
        try {
            Instant agora = clock.instant();
            tarefas.renovar(Set.copyOf(emExecucao), agora.plus(prazo));
            int devolvidas = tarefas.devolverVencidas(agora);
            if (devolvidas > 0) {
                log.warn("Fila do gerador: {} tarefa(s) presas além do prazo voltaram para a fila", devolvidas);
            }
        } catch (RuntimeException e) {
            log.warn("A manutenção da fila do gerador falhou: {}", e.toString());
        }
    }
}
