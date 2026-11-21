package br.com.conferenciaponto.modulos.backup.infrastructure.coleta;

import br.com.conferenciaponto.modulos.backup.infrastructure.config.ConfiguracaoDoBackup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/** Limpeza ao subir e todo dia às 3h30: coletas antigas (se ligada) e links nunca usados vencidos. */
@Component
public class LimpezaAgendada {

    private static final Logger LOG = LoggerFactory.getLogger(LimpezaAgendada.class);

    private final ServicoDeColetas coletas;
    private final ConfiguracaoDoBackup configuracao;

    public LimpezaAgendada(ServicoDeColetas coletas, ConfiguracaoDoBackup configuracao) {
        this.coletas = coletas;
        this.configuracao = configuracao;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void aoSubir() {
        limpar();
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void limpar() {
        int apagadas = coletas.limpar(configuracao.limpezaDias());
        if (apagadas > 0) {
            LOG.info("Limpeza: {} coleta(s) tirada(s) do histórico", apagadas);
        }
    }
}
