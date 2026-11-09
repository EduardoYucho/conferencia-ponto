package br.com.conferenciaponto.modulos.fiscal.application.coleta;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * O "Ler agora" da tela: uma rodada fora de hora, para quem pode configurar o monitor. Para não encher os portais de
 * consultas, recusa se a última rodada começou há menos de {@link #INTERVALO_MINIMO}.
 */
@Component
public class LerAgora {

    static final Duration INTERVALO_MINIMO = Duration.ofMinutes(1);

    private final ColetarSituacao coleta;
    private final RegistroDoMonitor registro;
    private final Clock clock;

    public LerAgora(ColetarSituacao coleta, RegistroDoMonitor registro, Clock clock) {
        this.coleta = coleta;
        this.registro = registro;
        this.clock = clock;
    }

    public RegistroDoMonitor.Rodada executar() {
        registro.ultimaRodada()
                .filter(r -> r.inicio().isAfter(clock.instant().minus(INTERVALO_MINIMO)))
                .ifPresent(r -> {
                    throw new RegraNegocioException("LEITURA_RECENTE",
                            "A última leitura foi há menos de 1 minuto. Espere um pouco para ler de novo.");
                });
        return coleta.executar();
    }
}
