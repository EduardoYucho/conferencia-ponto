package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.view.PresencaView;
import br.com.conferenciaponto.application.view.PresencaView.Pessoa;
import br.com.conferenciaponto.application.view.PresencaView.Situacao;
import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.TipoAusencia;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.AusenciaRepository;
import br.com.conferenciaponto.domain.port.CalendarioFeriados;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Painel da equipe: quem está trabalhando agora.
 *
 * <p>"Online" é quem bateu a entrada e ainda não bateu a saída — e não tem o dia marcado como férias, folga,
 * licença, atestado ou abono. Todos os usuários ativos aparecem; quem está offline vem com o motivo. A ordem em
 * que as regras são avaliadas:
 * <ol>
 *   <li>dia marcado como ausência → offline pelo motivo marcado (mesmo que haja batida);</li>
 *   <li>entrada sem saída → trabalhando;</li>
 *   <li>batidas completas → em intervalo (se o horário ainda tem período pela frente) ou encerrou;</li>
 *   <li>sem batida → feriado, sem expediente, antes do horário, ainda não bateu ou não registrou.</li>
 * </ol>
 *
 * <p>Todos veem a situação de todos. O detalhe é reservado: para colegas, atestado e abono aparecem só como
 * "Ausência justificada", e justificativas, batidas e login ficam com a administração, a coordenação e a
 * própria pessoa.
 */
@Service
@Transactional(readOnly = true)
public class ConsultarPresencaUseCase {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM");
    /** Tipos de ausência que os colegas veem só como "Ausência justificada". */
    private static final List<TipoAusencia> RESERVADAS = List.of(TipoAusencia.ATESTADO, TipoAusencia.ABONO);

    private final UsuarioRepository usuarios;
    private final RegistroJornadaRepository registros;
    private final AusenciaRepository ausencias;
    private final CalendarioFeriados feriados;
    private final RegrasJornada regras;
    private final Clock clock;

    public ConsultarPresencaUseCase(UsuarioRepository usuarios, RegistroJornadaRepository registros,
                                    AusenciaRepository ausencias, CalendarioFeriados feriados, RegrasJornada regras,
                                    Clock clock) {
        this.usuarios = usuarios;
        this.registros = registros;
        this.ausencias = ausencias;
        this.feriados = feriados;
        this.regras = regras;
        this.clock = clock;
    }

    /** @param quem quem está olhando o painel (decide o nível de detalhe de cada linha) */
    public PresencaView agora(Usuario quem) {
        LocalDate hoje = LocalDate.now(clock);
        LocalTime agora = LocalTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Optional<Feriado> feriado = feriados.buscar(hoje);
        var doDia = registros.listarPorData(hoje).stream()
                .collect(Collectors.toMap(RegistroJornada::getUsuarioId, Function.identity(), (a, b) -> a));

        List<Pessoa> pessoas = usuarios.listar().stream()
                .filter(Usuario::ativo)
                .map(usuario -> pessoa(usuario, quem, hoje, agora, feriado.orElse(null), doDia.get(usuario.id())))
                .sorted(Comparator.comparingInt((Pessoa p) -> ordem(p.situacao()))
                        .thenComparing(Pessoa::nome, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new PresencaView(hoje, agora, pessoas);
    }

    private Pessoa pessoa(Usuario usuario, Usuario quem, LocalDate hoje, LocalTime agora, Feriado feriado,
                          RegistroJornada registro) {
        boolean euMesmo = usuario.id().equals(quem.id());
        boolean completo = euMesmo || quem.podeVerTodos();
        if (!usuario.isTitular()) {
            return new Pessoa(usuario.id(), completo ? usuario.login() : null, usuario.nome(), euMesmo,
                    Situacao.NAO_REGISTRA_PONTO, "Coordenação: consulta os dados, não registra ponto", null, null, null,
                    List.of(), false, false);
        }

        GradeHoraria grade = regras.horario(usuario.id(), hoje).gradeDo(hoje).orElse(null);
        List<LocalTime> batidas = registro == null ? List.of() : registro.getBatidas().horarios();
        Optional<Ausencia> ausencia = ausencias.listarNoPeriodo(usuario.id(), hoje, hoje).stream().findFirst();

        Situacao situacao;
        String motivo;
        String detalhe = null;
        LocalTime desde = null;
        boolean atencao = false;
        boolean alemDoHorario = false;

        if (ausencia.isPresent()) {
            Ausencia a = ausencia.get();
            situacao = Situacao.AUSENCIA;
            boolean reservada = RESERVADAS.contains(a.tipo()) && !completo;
            motivo = (reservada ? "Ausência justificada" : a.tipo().rotulo()) + ate(a, hoje);
            detalhe = completo ? a.descricao() : null;
        } else if (batidas.size() % 2 == 1) {
            situacao = Situacao.TRABALHANDO;
            desde = batidas.get(batidas.size() - 1);
            motivo = "Trabalhando desde " + HORA.format(desde);
            alemDoHorario = grade == null || agora.isAfter(fim(grade)) || agora.isBefore(inicio(grade));
            if (feriado != null) {
                detalhe = "Hoje é feriado: " + feriado.descricao();
            } else if (grade == null) {
                detalhe = "Dia sem expediente no horário cadastrado";
            }
        } else if (!batidas.isEmpty()) {
            desde = batidas.get(batidas.size() - 1);
            boolean haPeriodoPelaFrente = grade != null && batidas.size() / 2 < grade.periodos().size()
                    && agora.isBefore(fim(grade));
            situacao = haPeriodoPelaFrente ? Situacao.INTERVALO : Situacao.ENCERROU;
            motivo = haPeriodoPelaFrente
                    ? "Em intervalo desde " + HORA.format(desde)
                    : "Encerrou o expediente às " + HORA.format(desde);
        } else if (feriado != null) {
            situacao = Situacao.FERIADO;
            motivo = "Feriado: " + feriado.descricao();
        } else if (grade == null) {
            situacao = Situacao.SEM_EXPEDIENTE;
            motivo = "Sem expediente hoje";
        } else if (agora.isBefore(inicio(grade))) {
            situacao = Situacao.ANTES_DO_EXPEDIENTE;
            motivo = "O expediente começa às " + HORA.format(inicio(grade));
        } else if (!agora.isAfter(fim(grade))) {
            situacao = Situacao.SEM_BATIDA;
            motivo = "Ainda não bateu o ponto (o expediente começou às " + HORA.format(inicio(grade)) + ")";
            atencao = true;
        } else {
            situacao = Situacao.NAO_REGISTROU;
            motivo = "Não registrou ponto hoje";
            atencao = true;
        }

        return new Pessoa(usuario.id(), completo ? usuario.login() : null, usuario.nome(), euMesmo, situacao, motivo,
                detalhe, desde, grade == null ? null : horario(grade), completo ? batidas : List.of(), atencao,
                alemDoHorario);
    }

    /** " até dd/MM" quando a ausência continua depois de hoje. */
    private static String ate(Ausencia ausencia, LocalDate hoje) {
        return ausencia.dataFim().isAfter(hoje) ? " até " + DIA.format(ausencia.dataFim()) : "";
    }

    private static LocalTime inicio(GradeHoraria grade) {
        return grade.periodos().get(0).entrada();
    }

    private static LocalTime fim(GradeHoraria grade) {
        return grade.periodos().get(grade.periodos().size() - 1).saida();
    }

    /** "08:00–12:00 · 13:00–17:48" */
    private static String horario(GradeHoraria grade) {
        return grade.periodos().stream()
                .map(p -> HORA.format(p.entrada()) + "–" + HORA.format(p.saida()))
                .collect(Collectors.joining(" · "));
    }

    /** Quem está trabalhando primeiro; depois quem volta logo; quem não tem ponto, por último. */
    private static int ordem(Situacao situacao) {
        return switch (situacao) {
            case TRABALHANDO -> 0;
            case INTERVALO -> 1;
            case SEM_BATIDA -> 2;
            case NAO_REGISTRA_PONTO -> 9;
            default -> 3;
        };
    }
}
