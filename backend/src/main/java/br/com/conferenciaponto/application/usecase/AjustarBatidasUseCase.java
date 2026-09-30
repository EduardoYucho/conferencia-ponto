package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.port.AjusteJornadaRepository;
import br.com.conferenciaponto.domain.port.ComprovanteArquivadoRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ajuste manual das batidas de um dia: inclui a batida que o relógio não registrou, corrige ou
 * remove batidas sem comprovante, para a conferência ficar igual à correção feita pelo RH.
 *
 * <p>Cada ajuste exige justificativa e fica no histórico (antes, depois, quem, quando). Batidas com
 * PDF arquivado são a prova da marcação real e não podem ser alteradas nem removidas.
 */
@Service
public class AjustarBatidasUseCase {

    static final int JUSTIFICATIVA_MINIMA = 5;
    static final int JUSTIFICATIVA_MAXIMA = 500;
    private static final Duration TOLERANCIA_RELOGIO = Duration.ofMinutes(5);
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HORA_SEGUNDOS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final RegistroJornadaRepository registros;
    private final ComprovanteArquivadoRepository arquivos;
    private final AjusteJornadaRepository ajustes;
    private final ClassificadorDiaService classificador;
    private final MotorCalculoJornadaService motor;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public AjustarBatidasUseCase(RegistroJornadaRepository registros, ComprovanteArquivadoRepository arquivos,
                                 AjusteJornadaRepository ajustes, ClassificadorDiaService classificador,
                                 MotorCalculoJornadaService motor, ApplicationEventPublisher eventos, Clock clock) {
        this.registros = registros;
        this.arquivos = arquivos;
        this.ajustes = ajustes;
        this.classificador = classificador;
        this.motor = motor;
        this.eventos = eventos;
        this.clock = clock;
    }

    /**
     * @param horarios      lista FINAL de batidas do dia (1 a 6, em qualquer ordem)
     * @param justificativa motivo (ex.: "Corrigido pelo RH: falha no relógio")
     * @param usuario       login de quem está ajustando
     */
    @Transactional
    public RegistroJornadaView executar(LocalDate data, List<LocalTime> horarios, String justificativa,
                                        String usuario) {
        if (data == null) {
            throw new RegraNegocioException("DATA_OBRIGATORIA", "Informe a data do ajuste.");
        }
        String motivo = justificativa == null ? "" : justificativa.strip();
        if (motivo.length() < JUSTIFICATIVA_MINIMA) {
            throw new RegraNegocioException("AJUSTE_SEM_JUSTIFICATIVA",
                    "Informe o motivo do ajuste (ex.: \"Corrigido pelo RH: falha no relógio\").");
        }
        if (motivo.length() > JUSTIFICATIVA_MAXIMA) {
            throw new RegraNegocioException("AJUSTE_JUSTIFICATIVA_LONGA",
                    "A justificativa pode ter no máximo %d caracteres.".formatted(JUSTIFICATIVA_MAXIMA));
        }
        validarFuturo(data, horarios);
        return aplicar(data, horarios, motivo, usuario, OrigemAtualizacao.AJUSTE);
    }

    /**
     * "Aceitar dados do RH" na conciliação: o dia fica com as batidas do relatório do RH. Batidas com PDF
     * podem ter os segundos alinhados ao RH (até 1 minuto), mas não somem. Um dia que não existia aqui
     * é criado sem a marca de ajuste (é o registro oficial do RH).
     */
    @Transactional
    public RegistroJornadaView conformeRh(LocalDate data, List<LocalTime> horarios, String justificativa,
                                          String usuario) {
        return aplicar(data, horarios, justificativa, usuario, OrigemAtualizacao.CONCILIACAO);
    }

    private RegistroJornadaView aplicar(LocalDate data, List<LocalTime> horarios, String motivo, String usuario,
                                        OrigemAtualizacao origem) {
        Optional<RegistroJornada> existente = registros.buscarPorData(data);
        RegistroJornada registro = existente
                .orElseGet(() -> RegistroJornada.novo(data, classificador.classificar(data)));
        List<ComprovanteArquivado> pdfs = existente.isPresent()
                ? arquivos.listarPorRegistro(registro.getId())
                : List.of();
        Set<LocalTime> comprovadas = VinculoComprovantes.horariosComprovados(registro, pdfs);

        RegistroJornada.RegrasAjuste regras = origem == OrigemAtualizacao.CONCILIACAO
                ? RegistroJornada.RegrasAjuste.conformeRh(existente.isPresent())
                : RegistroJornada.RegrasAjuste.MANUAL;
        List<LocalTime> antes = registro.ajustarBatidas(horarios, comprovadas, regras, motor);
        registros.salvar(registro);
        if (!pdfs.isEmpty()) {
            VinculoComprovantes.reorganizar(registro, arquivos); // as batidas podem ter mudado de coluna
        }
        AjusteJornada ajuste = AjusteJornada.novo(registro, antes, motivo, usuario, clock.instant());
        ajustes.salvar(ajuste);

        RegistroJornadaView view = RegistroJornadaView.de(registro, motor);
        String mensagem = (origem == OrigemAtualizacao.CONCILIACAO
                ? "Batidas de %s alinhadas ao RH por %s: %s → %s."
                : "Batidas de %s ajustadas por %s: %s → %s.")
                .formatted(DIA.format(data), usuario, texto(ajuste.antes()), texto(ajuste.depois()));
        eventos.publishEvent(new JornadaAtualizadaEvento(data, origem, view, mensagem));
        return view;
    }

    /**
     * O que a tela de ajuste precisa saber do dia.
     *
     * @param comprovadas horários das batidas que têm PDF (ficam travadas)
     * @param historico   ajustes anteriores, do mais recente para o mais antigo
     */
    public record Contexto(List<LocalTime> comprovadas, List<AjusteJornada> historico) {
    }

    @Transactional(readOnly = true)
    public Contexto contexto(LocalDate data) {
        List<LocalTime> comprovadas = registros.buscarPorData(data)
                .map(r -> List.copyOf(VinculoComprovantes.horariosComprovados(r, arquivos.listarPorRegistro(r.getId()))))
                .orElse(List.of());
        return new Contexto(comprovadas, historico(data));
    }

    /** Histórico de ajustes do dia, do mais recente para o mais antigo. */
    @Transactional(readOnly = true)
    public List<AjusteJornada> historico(LocalDate data) {
        return ajustes.listarPorData(data);
    }

    private void validarFuturo(LocalDate data, List<LocalTime> horarios) {
        LocalDateTime agora = LocalDateTime.now(clock);
        if (data.isAfter(agora.toLocalDate())) {
            throw new RegraNegocioException("AJUSTE_DATA_FUTURA", "Não é possível ajustar uma data futura.");
        }
        if (data.equals(agora.toLocalDate()) && horarios != null) {
            LocalTime limite = agora.toLocalTime().plus(TOLERANCIA_RELOGIO);
            horarios.stream().filter(Objects::nonNull).filter(h -> h.isAfter(limite)).findFirst()
                    .ifPresent(h -> {
                        throw new RegraNegocioException("AJUSTE_HORARIO_FUTURO",
                                "A batida das %s ainda não aconteceu.".formatted(HORA.format(h)));
                    });
        }
    }

    private static String texto(List<LocalTime> horarios) {
        return horarios.isEmpty()
                ? "(sem batidas)"
                : horarios.stream().map(HORA_SEGUNDOS::format).collect(Collectors.joining(" "));
    }
}
