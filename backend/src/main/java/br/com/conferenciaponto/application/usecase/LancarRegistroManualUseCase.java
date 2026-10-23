package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.TravaDosMesesConferidos;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.Batidas;
import br.com.conferenciaponto.domain.model.Intervalo;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.port.AjusteJornadaRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Lançamento manual de intervalos em fim de semana/feriado (100% crédito).
 * Relançar a mesma data substitui o lançamento manual anterior; batidas de
 * relógio existentes não são sobrescritas.
 *
 * <p>Cada lançamento (e cada correção dele) fica no histórico do dia, com o motivo, quem e quando: quem confere vê
 * que as horas foram lançadas à mão.
 */
@Service
public class LancarRegistroManualUseCase {

    /** Motivo de quem não informa (ex.: chamadas antigas da API). */
    public static final String MOTIVO_PADRAO = "Lançamento manual";

    private final RegistroJornadaRepository repository;
    private final AjusteJornadaRepository historico;
    private final RegrasJornada regras;
    private final TravaDosMesesConferidos trava;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public LancarRegistroManualUseCase(RegistroJornadaRepository repository, AjusteJornadaRepository historico,
                                       RegrasJornada regras, TravaDosMesesConferidos trava,
                                       ApplicationEventPublisher eventos, Clock clock) {
        this.repository = repository;
        this.historico = historico;
        this.regras = regras;
        this.trava = trava;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Transactional
    public RegistroJornadaView executar(UUID usuarioId, LocalDate data, List<Intervalo> intervalos) {
        return executar(usuarioId, data, intervalos, null, "sistema");
    }

    /**
     * @param justificativa motivo (ex.: "Compensação da carga semanal"); vazio = "Lançamento manual"
     * @param quem          login de quem lançou
     */
    @Transactional
    public RegistroJornadaView executar(UUID usuarioId, LocalDate data, List<Intervalo> intervalos,
                                        String justificativa, String quem) {
        if (data == null) {
            throw new RegraNegocioException("DATA_OBRIGATORIA", "Informe a data do lançamento.");
        }
        if (data.isAfter(LocalDate.now(clock))) {
            throw new RegraNegocioException("LANCAMENTO_NO_FUTURO",
                    "Não é permitido lançar horas em data futura.");
        }
        String motivo = justificativa == null || justificativa.isBlank() ? MOTIVO_PADRAO : justificativa.strip();
        if (motivo.length() > AjustarBatidasUseCase.JUSTIFICATIVA_MAXIMA) {
            throw new RegraNegocioException("AJUSTE_JUSTIFICATIVA_LONGA",
                    "O motivo pode ter no máximo %d caracteres.".formatted(AjustarBatidasUseCase.JUSTIFICATIVA_MAXIMA));
        }
        if (motivo.length() < AjustarBatidasUseCase.JUSTIFICATIVA_MINIMA) {
            throw new RegraNegocioException("AJUSTE_SEM_JUSTIFICATIVA",
                    "Escreva o motivo com pelo menos %d letras.".formatted(AjustarBatidasUseCase.JUSTIFICATIVA_MINIMA));
        }
        trava.exigirAberto(usuarioId, data);

        Batidas batidas = Batidas.deIntervalos(intervalos);

        RegistroJornada registro = repository.buscarPorData(usuarioId, data)
                .orElseGet(() -> RegistroJornada.novo(usuarioId, data, regras.classificar(usuarioId, data)));
        MotorCalculoJornadaService motor = regras.motor(usuarioId, data);
        List<LocalTime> antes = registro.getBatidas().horarios();

        // Regras (dia útil, não sobrescrever relógio) ficam no agregado
        registro.lancarManualmente(batidas, motor);
        repository.salvar(registro);
        if (!antes.equals(registro.getBatidas().horarios())) {
            historico.salvar(AjusteJornada.novo(registro, antes, motivo, quem, clock.instant(), AjusteJornada.Origem.MANUAL));
        }
        RegistroJornadaView view = RegistroJornadaView.de(registro, motor);
        eventos.publishEvent(new JornadaAtualizadaEvento(usuarioId, data, OrigemAtualizacao.MANUAL, view, null));
        return view;
    }
}
