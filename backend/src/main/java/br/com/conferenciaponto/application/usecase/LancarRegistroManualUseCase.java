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
import br.com.conferenciaponto.domain.port.ComprovanteArquivadoRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lançamento manual de intervalos em fim de semana/feriado (100% crédito).
 * Relançar a mesma data substitui o lançamento manual anterior; batidas de
 * relógio existentes não são sobrescritas. Um dia lançado à mão que depois recebeu batidas com PDF (a pessoa
 * lançou a manhã e bateu no relógio à tarde) só é relançado se as batidas comprovadas continuarem iguais: sem isso,
 * corrigir a manhã apagaria (ou arredondaria) as batidas que têm comprovante.
 *
 * <p>Cada lançamento (e cada correção dele) fica no histórico do dia, com o motivo, quem e quando: quem confere vê
 * que as horas foram lançadas à mão.
 */
@Service
public class LancarRegistroManualUseCase {

    /** Motivo de quem não informa (ex.: chamadas antigas da API). */
    public static final String MOTIVO_PADRAO = "Lançamento manual";

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final RegistroJornadaRepository repository;
    private final AjusteJornadaRepository historico;
    private final ComprovanteArquivadoRepository arquivos;
    private final RegrasJornada regras;
    private final TravaDosMesesConferidos trava;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public LancarRegistroManualUseCase(RegistroJornadaRepository repository, AjusteJornadaRepository historico,
                                       ComprovanteArquivadoRepository arquivos, RegrasJornada regras,
                                       TravaDosMesesConferidos trava, ApplicationEventPublisher eventos, Clock clock) {
        this.repository = repository;
        this.historico = historico;
        this.arquivos = arquivos;
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
        Set<LocalTime> comprovadas = antes.isEmpty() ? Set.of()
                : VinculoComprovantes.horariosComprovados(registro, arquivos.listarPorRegistro(registro.getId()));
        if (registro.isRegistroManual() && !batidas.horarios().containsAll(comprovadas)) {
            throw new RegraNegocioException("LANCAMENTO_MANUAL_COM_COMPROVANTE", ("%s tem batidas com comprovante em PDF "
                    + "(%s), e o lançamento mudaria ou tiraria essas batidas. Corrija o dia em \"Ajustar batidas\": "
                    + "lá as batidas comprovadas ficam travadas e o resto pode mudar.")
                    .formatted(DIA.format(data), comprovadas.stream().sorted().map(HORA::format)
                            .collect(Collectors.joining(", "))));
        }

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
