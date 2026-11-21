package br.com.expedio.application.usecase;

import br.com.expedio.application.RegrasJornada;
import br.com.expedio.application.TravaDosMesesConferidos;
import br.com.expedio.application.evento.JornadaAtualizadaEvento;
import br.com.expedio.application.evento.OrigemAtualizacao;
import br.com.expedio.application.view.RegistroJornadaView;
import br.com.expedio.domain.exception.RegraNegocioException;
import br.com.expedio.domain.model.RegistroJornada;
import br.com.expedio.domain.port.RegistroJornadaRepository;
import br.com.expedio.domain.service.MotorCalculoJornadaService;
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
    private final TravaDosMesesConferidos trava;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public RegistrarBatidaUseCase(RegistroJornadaRepository repository, RegrasJornada regras,
                                  TravaDosMesesConferidos trava, ApplicationEventPublisher eventos, Clock clock) {
        this.repository = repository;
        this.regras = regras;
        this.trava = trava;
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

        trava.exigirAberto(usuarioId, data);
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
