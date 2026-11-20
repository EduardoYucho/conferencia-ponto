package br.com.receptorbackup.coleta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import br.com.receptorbackup.config.Propriedades;

/** Limpeza ao subir e todo dia às 3h30: coletas antigas (se ligada) e links nunca usados vencidos. */
@Component
public class LimpezaAgendada {

    private static final Logger LOG = LoggerFactory.getLogger(LimpezaAgendada.class);

    private final ServicoDeColetas coletas;
    private final Propriedades propriedades;

    public LimpezaAgendada(ServicoDeColetas coletas, Propriedades propriedades) {
        this.coletas = coletas;
        this.propriedades = propriedades;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void aoSubir() {
        limpar();
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void limpar() {
        int apagadas = coletas.limpar(propriedades.limpezaDias(), propriedades.manterUltimaPorCliente());
        if (apagadas > 0) {
            LOG.info("Limpeza: {} coleta(s) apagada(s)", apagadas);
        }
    }
}
