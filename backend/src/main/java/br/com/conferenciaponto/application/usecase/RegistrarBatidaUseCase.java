package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Registra a próxima batida do dia. Sem data/horário informados, usa o relógio do servidor.
 */
@Service
public class RegistrarBatidaUseCase {

    private final RegistroJornadaRepository repository;
    private final RegrasJornada regras;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public RegistrarBatidaUseCase(RegistroJornadaRepository repository, RegrasJornada regras,
                                  ApplicationEventPublisher eventos, Clock clock) {
        this.repository = repository;
        this.regras = regras;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Transactional
    public RegistroJornadaView executar(UUID usuarioId, LocalDate dataInformada, LocalTime horarioInformado) {
        LocalDateTime agora = LocalDateTime.now(clock);
        LocalDate data = dataInformada != null ? dataInformada : agora.toLocalDate();
        LocalTime horario = horarioInformado != null ? horarioInformado : agora.toLocalTime();

        if (LocalDateTime.of(data, horario).isAfter(agora)) {
            throw new RegraNegocioException("BATIDA_NO_FUTURO",
                    "Não é permitido registrar batida em data/hora futura.");
        }

        RegistroJornada registro = repository.buscarPorData(usuarioId, data)
                .orElseGet(() -> RegistroJornada.novo(usuarioId, data, regras.classificar(usuarioId, data)));
        MotorCalculoJornadaService motor = regras.motor(usuarioId, data);
        registro.registrarBatida(horario, motor);
        repository.salvar(registro);
        RegistroJornadaView view = RegistroJornadaView.de(registro, motor);
        eventos.publishEvent(new JornadaAtualizadaEvento(usuarioId, data, OrigemAtualizacao.API, view, null));
        return view;
    }
}
