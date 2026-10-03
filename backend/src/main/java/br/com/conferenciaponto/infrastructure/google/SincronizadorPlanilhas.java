package br.com.conferenciaponto.infrastructure.google;

import br.com.conferenciaponto.infrastructure.log.DonoDoLog;
import br.com.conferenciaponto.infrastructure.log.ContextoDeLog;
import br.com.conferenciaponto.application.evento.CalendarioAlteradoEvento;
import br.com.conferenciaponto.application.evento.CicloAtualizadoEvento;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.LancamentoBancoAlteradoEvento;
import br.com.conferenciaponto.application.evento.UsuarioAlteradoEvento;
import br.com.conferenciaponto.application.usecase.GerenciarPlanilhaUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarPlanilhaUseCase.Resultado;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mantém as planilhas do Google em dia: cada mudança no ponto de alguém (batida importada, ajuste, lançamento
 * no banco, feriado, férias, horário, fechamento do ciclo) marca os meses afetados e, alguns segundos depois,
 * as abas deles e o resumo são regravados. Mudanças seguidas (vários PDFs de uma vez) viram uma gravação só.
 *
 * <p>Uma thread única faz as gravações, uma planilha por vez. Falha passageira (sem internet, limite do Google)
 * é tentada de novo com intervalos crescentes; uma vez por dia e na subida do sistema, tudo é regravado.
 */
@Component
public class SincronizadorPlanilhas {

    /** Acima disso, um intervalo de datas alterado regrava a planilha inteira. */
    private static final int MESES_POR_INTERVALO = 36;
    private static final int TENTATIVAS = 8;
    private static final Duration MAIOR_ESPERA_APOS_FALHA = Duration.ofMinutes(30);

    /**
     * @param meses      meses a regravar; {@code null} = todas as abas
     * @param naoAntesDe a gravação espera até este instante
     */
    private record Pendencia(Set<YearMonth> meses, int tentativas, Instant naoAntesDe) {
    }

    private static final Logger log = LoggerFactory.getLogger(SincronizadorPlanilhas.class);

    private final GerenciarPlanilhaUseCase planilhas;
    private final DonoDoLog dono;
    private final Duration espera;
    private final Duration primeiraEsperaAposFalha;
    private final Map<UUID, Pendencia> pendentes = new HashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(tarefa -> {
        Thread thread = new Thread(tarefa, "planilha-google");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public SincronizadorPlanilhas(GerenciarPlanilhaUseCase planilhas, GoogleProperties properties, DonoDoLog dono) {
        this(planilhas, properties.espera(), Duration.ofSeconds(30), dono);
    }

    SincronizadorPlanilhas(GerenciarPlanilhaUseCase planilhas, Duration espera, Duration primeiraEsperaAposFalha) {
        this(planilhas, espera, primeiraEsperaAposFalha, null);
    }

    private SincronizadorPlanilhas(GerenciarPlanilhaUseCase planilhas, Duration espera,
                                   Duration primeiraEsperaAposFalha, DonoDoLog dono) {
        this.planilhas = planilhas;
        this.dono = dono;
        this.espera = espera;
        this.primeiraEsperaAposFalha = primeiraEsperaAposFalha;
    }

    // ------------------------------------------------------------------ o que dispara

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAtualizarJornada(JornadaAtualizadaEvento evento) {
        agendar(evento.usuarioId(), Set.of(YearMonth.from(evento.data())));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAlterarBanco(LancamentoBancoAlteradoEvento evento) {
        agendar(evento.usuarioId(), Set.of(YearMonth.from(evento.data())));
    }

    /** Feriado ({@code usuarioId} nulo: vale para todos), férias, folga, abono ou horário de trabalho. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAlterarCalendario(CalendarioAlteradoEvento evento) {
        Set<YearMonth> meses = meses(evento.inicio(), evento.fim());
        if (evento.usuarioId() != null) {
            agendar(evento.usuarioId(), meses);
        } else {
            vinculados().forEach(usuario -> agendar(usuario, meses));
        }
    }

    /** Fechamento do ciclo, fechamento desfeito ou período corrigido: muda o banco de horas do resumo. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAtualizarCiclo(CicloAtualizadoEvento evento) {
        agendar(evento.usuarioId(), Set.of());
    }

    /** O nome da pessoa aparece em todas as abas. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAlterarUsuario(UsuarioAlteradoEvento evento) {
        agendar(evento.usuarioId(), null);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void aoSubir() {
        agendarTodos();
    }

    /** Virada do dia: o dia de ontem em andamento vira "incompleto", o mês pode ter mudado. */
    @Scheduled(cron = "0 10 0 * * *")
    public void todoDia() {
        agendarTodos();
    }

    /** Regrava tudo de todos (subida do sistema, virada do dia, conta de serviço trocada). */
    public void agendarTodos() {
        vinculados().forEach(usuario -> agendar(usuario, null));
    }

    /**
     * @param meses meses que mudaram (vazio = só o resumo); {@code null} = todas as abas
     */
    public void agendar(UUID usuarioId, Set<YearMonth> meses) {
        if (usuarioId == null) {
            return;
        }
        synchronized (pendentes) {
            Pendencia anterior = pendentes.get(usuarioId);
            pendentes.put(usuarioId, new Pendencia(juntar(anterior == null ? Set.of() : anterior.meses(), meses), 0,
                    Instant.now().plus(espera)));
        }
        executar(espera);
    }

    // ------------------------------------------------------------------ a gravação

    private void executar(Duration daqui) {
        try {
            executor.schedule(this::gravarPendentes, daqui.toMillis() + 50, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.debug("Sincronização de planilhas encerrada: {}", e.getMessage());
        }
    }

    private void gravarPendentes() {
        Map<UUID, Pendencia> vencidas = new HashMap<>();
        Instant agora = Instant.now();
        synchronized (pendentes) {
            pendentes.entrySet().removeIf(e -> {
                if (e.getValue().naoAntesDe().isAfter(agora)) {
                    return false;
                }
                vencidas.put(e.getKey(), e.getValue());
                return true;
            });
        }
        vencidas.forEach((usuario, pendencia) -> ContextoDeLog.comUsuario(
                dono == null ? null : dono.loginDe(usuario), () -> gravar(usuario, pendencia)));
    }

    private void gravar(UUID usuario, Pendencia pendencia) {
        Resultado resultado;
        try {
            resultado = planilhas.sincronizar(usuario, pendencia.meses());
        } catch (RuntimeException e) {
            log.error("Falha ao gravar a planilha do Google do usuário {}", usuario, e);
            return;
        }
        if (resultado == Resultado.TENTAR_DE_NOVO && pendencia.tentativas() < TENTATIVAS) {
            tentarDeNovo(usuario, pendencia.tentativas() + 1);
        }
    }

    private void tentarDeNovo(UUID usuario, int tentativa) {
        Duration aguardar = primeiraEsperaAposFalha.multipliedBy(1L << Math.min(tentativa - 1, 10));
        if (aguardar.compareTo(MAIOR_ESPERA_APOS_FALHA) > 0) {
            aguardar = MAIOR_ESPERA_APOS_FALHA;
        }
        synchronized (pendentes) {
            // uma mudança nova, chegada durante a gravação, já agendou a própria tentativa
            pendentes.putIfAbsent(usuario, new Pendencia(null, tentativa, Instant.now().plus(aguardar)));
        }
        executar(aguardar);
    }

    private List<UUID> vinculados() {
        try {
            return planilhas.vinculados();
        } catch (RuntimeException e) {
            log.warn("Não foi possível listar as planilhas vinculadas: {}", e.getMessage());
            return List.of();
        }
    }

    private static Set<YearMonth> juntar(Set<YearMonth> a, Set<YearMonth> b) {
        if (a == null || b == null) {
            return null;
        }
        Set<YearMonth> soma = new HashSet<>(a);
        soma.addAll(b);
        return soma;
    }

    private static Set<YearMonth> meses(LocalDate inicio, LocalDate fim) {
        if (inicio == null || fim == null) {
            return null;
        }
        List<YearMonth> meses = new ArrayList<>();
        for (YearMonth m = YearMonth.from(inicio); !m.isAfter(YearMonth.from(fim)); m = m.plusMonths(1)) {
            if (meses.size() >= MESES_POR_INTERVALO) {
                return null;
            }
            meses.add(m);
        }
        return new HashSet<>(meses);
    }

    @PreDestroy
    public void encerrar() {
        executor.shutdownNow();
    }
}
