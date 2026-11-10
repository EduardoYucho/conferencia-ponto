package br.com.conferenciaponto.modulos.fiscal.infrastructure.coleta;

import br.com.conferenciaponto.modulos.fiscal.application.coleta.ColetarSituacao;
import br.com.conferenciaponto.modulos.fiscal.application.coleta.ConfiguracaoDoMonitor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Roda a coleta numa thread própria ("fiscal-coleta"), a cada {@code fiscal.coleta.intervalo} depois do fim da
 * rodada anterior (uma rodada lenta nunca encavala na próxima), e limpa o histórico antigo uma vez por dia. Um erro
 * numa rodada fica no log e a próxima roda normalmente.
 */
@Component
public class AgendaDaColeta {

    private static final Logger log = LoggerFactory.getLogger(AgendaDaColeta.class);

    private final ColetarSituacao coleta;
    private final ConfiguracaoDoMonitor configuracao;
    private ScheduledExecutorService agenda;

    public AgendaDaColeta(ColetarSituacao coleta, ConfiguracaoDoMonitor configuracao) {
        this.coleta = coleta;
        this.configuracao = configuracao;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void iniciar() {
        ConfiguracaoDoMonitor.Coleta c = configuracao.coleta();
        if (!c.ativa()) {
            log.info("Monitor fiscal: coleta automática desligada (fiscal.coleta.ativa=false)");
            return;
        }
        if (agenda != null) {
            return;
        }
        agenda = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "fiscal-coleta");
            t.setDaemon(true);
            return t;
        });
        agenda.scheduleWithFixedDelay(this::rodar, c.primeiraVez().toMillis(), c.intervalo().toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Monitor fiscal: coleta a cada {} (a primeira em {})", c.intervalo(), c.primeiraVez());
    }

    void rodar() {
        try {
            coleta.executar();
        } catch (RuntimeException e) {
            log.error("Monitor fiscal: a rodada falhou; a próxima roda no horário", e);
        }
    }

    @Scheduled(cron = "${fiscal.retencao.cron:0 45 3 * * *}")
    public void limparHistorico() {
        try {
            coleta.limparHistorico();
        } catch (RuntimeException e) {
            log.error("Monitor fiscal: a limpeza do histórico falhou; tenta de novo amanhã", e);
        }
    }

    @PreDestroy
    public synchronized void parar() {
        if (agenda != null) {
            agenda.shutdownNow();
            agenda = null;
        }
    }
}
