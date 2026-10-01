package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.evento.CalendarioAlteradoEvento;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.TipoAusencia;
import br.com.conferenciaponto.domain.port.AusenciaRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Férias, atestados, licenças e folgas. Os dias úteis cobertos passam a ter jornada base zero:
 * não geram débito e somem das pendências. Dias já registrados no período são reclassificados.
 */
@Service
public class GerenciarAusenciasUseCase {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final AusenciaRepository ausencias;
    private final RegistroJornadaRepository registros;
    private final RegrasJornada regras;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public GerenciarAusenciasUseCase(AusenciaRepository ausencias, RegistroJornadaRepository registros,
                                     RegrasJornada regras, ApplicationEventPublisher eventos, Clock clock) {
        this.ausencias = ausencias;
        this.registros = registros;
        this.regras = regras;
        this.eventos = eventos;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Ausencia> listar(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        return ausencias.listarNoPeriodo(usuarioId, inicio, fim);
    }

    @Transactional
    public Ausencia cadastrar(UUID usuarioId, LocalDate inicio, LocalDate fim, TipoAusencia tipo, String descricao,
                              String usuario) {
        Ausencia nova = Ausencia.nova(usuarioId, inicio, fim, tipo, descricao, usuario, clock.instant());
        List<Ausencia> sobrepostas = ausencias.listarNoPeriodo(usuarioId, inicio, fim);
        if (!sobrepostas.isEmpty()) {
            Ausencia outra = sobrepostas.get(0);
            throw new ConflitoException("AUSENCIA_SOBREPOSTA", "Já existe %s de %s a %s nesse período."
                    .formatted(outra.tipo().rotulo().toLowerCase(), DATA.format(outra.dataInicio()),
                            DATA.format(outra.dataFim())));
        }
        ausencias.salvar(nova);
        reclassificar(usuarioId, inicio, fim, "%s cadastrada".formatted(nova.rotulo()));
        return nova;
    }

    @Transactional
    public void excluir(UUID usuarioId, UUID id) {
        Ausencia ausencia = ausencias.buscarPorId(id)
                .filter(a -> a.usuarioId().equals(usuarioId))
                .orElseThrow(() -> new RecursoNaoEncontradoException("AUSENCIA_NAO_ENCONTRADA",
                        "Ausência não encontrada."));
        ausencias.excluir(id);
        reclassificar(usuarioId, ausencia.dataInicio(), ausencia.dataFim(),
                "%s removida".formatted(ausencia.rotulo()));
    }

    /**
     * Marca um dia (usado ao aceitar dados do RH). Se houver um período do mesmo tipo encostado, ele é
     * estendido — 14 dias de férias viram um período só, e não 14 cadastros.
     */
    @Transactional
    public Ausencia registrarDia(UUID usuarioId, LocalDate data, TipoAusencia tipo, String descricao,
                                 String usuario) {
        List<Ausencia> vizinhas = ausencias.listarNoPeriodo(usuarioId, data.minusDays(1), data.plusDays(1));
        Optional<Ausencia> jaCobre = vizinhas.stream().filter(a -> a.contem(data)).findFirst();
        if (jaCobre.isPresent()) {
            if (jaCobre.get().tipo() != tipo) {
                throw new ConflitoException("AUSENCIA_SOBREPOSTA", "O dia %s já está marcado como %s."
                        .formatted(DATA.format(data), jaCobre.get().tipo().rotulo().toLowerCase()));
            }
            return jaCobre.get();
        }
        List<Ausencia> encostadas = vizinhas.stream().filter(a -> a.encostaEm(data, tipo)).toList();
        Ausencia resultado;
        if (encostadas.isEmpty()) {
            resultado = Ausencia.nova(usuarioId, data, data, tipo, descricao, usuario, clock.instant());
        } else {
            resultado = encostadas.get(0).estendidaAte(data);
            if (encostadas.size() > 1) { // o dia une dois períodos: vira um só
                Ausencia outra = encostadas.get(1);
                resultado = resultado.estendidaAte(outra.dataInicio()).estendidaAte(outra.dataFim());
                ausencias.excluir(outra.id());
            }
        }
        ausencias.salvar(resultado);
        reclassificar(usuarioId, data, data, "%s (conforme RH)".formatted(resultado.rotulo()));
        return resultado;
    }

    private void reclassificar(UUID usuarioId, LocalDate inicio, LocalDate fim, String motivo) {
        eventos.publishEvent(new CalendarioAlteradoEvento(usuarioId, inicio, fim));
        for (RegistroJornada registro : registros.listarPorPeriodo(usuarioId, inicio, fim)) {
            LocalDate data = registro.getDataReferencia();
            MotorCalculoJornadaService motor = regras.motor(usuarioId, data);
            if (registro.reclassificar(regras.classificar(usuarioId, data), motor)) {
                registros.salvar(registro);
                eventos.publishEvent(new JornadaAtualizadaEvento(usuarioId, data,
                        OrigemAtualizacao.AUSENCIA, RegistroJornadaView.de(registro, motor), motivo));
            }
        }
    }
}
