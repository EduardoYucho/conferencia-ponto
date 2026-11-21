package br.com.expedio.application.usecase;

import br.com.expedio.application.RegrasJornada;
import br.com.expedio.application.evento.CalendarioAlteradoEvento;
import br.com.expedio.application.evento.JornadaAtualizadaEvento;
import br.com.expedio.application.evento.OrigemAtualizacao;
import br.com.expedio.application.view.RegistroJornadaView;
import br.com.expedio.domain.exception.ConflitoException;
import br.com.expedio.domain.exception.RecursoNaoEncontradoException;
import br.com.expedio.domain.model.AbrangenciaFeriado;
import br.com.expedio.domain.model.Feriado;
import br.com.expedio.domain.model.RegistroJornada;
import br.com.expedio.domain.port.CalendarioFeriados;
import br.com.expedio.domain.port.RegistroJornadaRepository;
import br.com.expedio.domain.service.MotorCalculoJornadaService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Feriados cadastrados à mão (municipais, estaduais, pontos facultativos da empresa — ex.: Corpus Christi).
 * Valem para todos os usuários. O dia passa a ter jornada base zero: os registros que já existiam são
 * recalculados (o trabalho vira crédito).
 */
@Service
public class GerenciarFeriadosUseCase {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CalendarioFeriados feriados;
    private final RegistroJornadaRepository registros;
    private final RegrasJornada regras;
    private final ApplicationEventPublisher eventos;

    public GerenciarFeriadosUseCase(CalendarioFeriados feriados, RegistroJornadaRepository registros,
                                    RegrasJornada regras, ApplicationEventPublisher eventos) {
        this.feriados = feriados;
        this.registros = registros;
        this.regras = regras;
        this.eventos = eventos;
    }

    @Transactional(readOnly = true)
    public List<Feriado> listar(LocalDate inicio, LocalDate fim) {
        return feriados.listar(inicio, fim);
    }

    @Transactional
    public Feriado cadastrar(LocalDate data, String descricao, AbrangenciaFeriado abrangencia) {
        Feriado novo = new Feriado(data, descricao, abrangencia);
        feriados.buscar(data).ifPresent(existente -> {
            throw new ConflitoException("FERIADO_JA_CADASTRADO", "%s já é feriado: %s."
                    .formatted(DATA.format(data), existente.descricao()));
        });
        feriados.salvar(novo);
        reclassificar(data, "Feriado cadastrado: %s".formatted(novo.descricao()));
        return novo;
    }

    @Transactional
    public void excluir(LocalDate data) {
        Feriado feriado = feriados.buscar(data).orElseThrow(() -> new RecursoNaoEncontradoException(
                "FERIADO_NAO_ENCONTRADO", "%s não está cadastrado como feriado.".formatted(DATA.format(data))));
        feriados.excluir(data);
        reclassificar(data, "Feriado removido: %s".formatted(feriado.descricao()));
    }

    private void reclassificar(LocalDate data, String motivo) {
        eventos.publishEvent(new CalendarioAlteradoEvento(null, data, data));
        for (RegistroJornada registro : registros.listarPorData(data)) {
            UUID usuarioId = registro.getUsuarioId();
            MotorCalculoJornadaService motor = regras.motor(usuarioId, data);
            if (registro.reclassificar(regras.classificar(usuarioId, data), motor)) {
                registros.salvar(registro);
                eventos.publishEvent(new JornadaAtualizadaEvento(usuarioId, data, OrigemAtualizacao.AUSENCIA,
                        RegistroJornadaView.de(registro, motor), motivo));
            }
        }
    }
}
