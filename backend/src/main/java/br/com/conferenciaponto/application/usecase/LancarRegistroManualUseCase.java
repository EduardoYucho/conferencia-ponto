package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Batidas;
import br.com.conferenciaponto.domain.model.Intervalo;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Lançamento manual de intervalos em fim de semana/feriado (100% crédito).
 * Relançar a mesma data substitui o lançamento manual anterior; batidas de
 * relógio existentes não são sobrescritas.
 */
@Service
public class LancarRegistroManualUseCase {

    private final RegistroJornadaRepository repository;
    private final RegrasJornada regras;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public LancarRegistroManualUseCase(RegistroJornadaRepository repository, RegrasJornada regras,
                                       ApplicationEventPublisher eventos, Clock clock) {
        this.repository = repository;
        this.regras = regras;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Transactional
    public RegistroJornadaView executar(UUID usuarioId, LocalDate data, List<Intervalo> intervalos) {
        if (data == null) {
            throw new RegraNegocioException("DATA_OBRIGATORIA", "Informe a data do lançamento.");
        }
        if (data.isAfter(LocalDate.now(clock))) {
            throw new RegraNegocioException("LANCAMENTO_NO_FUTURO",
                    "Não é permitido lançar horas em data futura.");
        }

        Batidas batidas = Batidas.deIntervalos(intervalos);

        RegistroJornada registro = repository.buscarPorData(usuarioId, data)
                .orElseGet(() -> RegistroJornada.novo(usuarioId, data, regras.classificar(usuarioId, data)));
        MotorCalculoJornadaService motor = regras.motor(usuarioId, data);

        // Regras (dia útil, não sobrescrever relógio) ficam no agregado
        registro.lancarManualmente(batidas, motor);
        repository.salvar(registro);
        RegistroJornadaView view = RegistroJornadaView.de(registro, motor);
        eventos.publishEvent(new JornadaAtualizadaEvento(usuarioId, data, OrigemAtualizacao.MANUAL, view, null));
        return view;
    }
}
