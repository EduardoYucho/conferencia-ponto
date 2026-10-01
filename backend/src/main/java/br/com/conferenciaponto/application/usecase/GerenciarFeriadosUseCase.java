package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.CalendarioAlteradoEvento;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.AbrangenciaFeriado;
import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.port.CalendarioFeriados;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Feriados cadastrados à mão (municipais, estaduais, pontos facultativos da empresa — ex.: Corpus Christi).
 * O dia passa a ter jornada base zero: um registro que já existia é recalculado (o trabalho vira crédito).
 */
@Service
public class GerenciarFeriadosUseCase {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CalendarioFeriados feriados;
    private final RegistroJornadaRepository registros;
    private final ClassificadorDiaService classificador;
    private final MotorCalculoJornadaService motor;
    private final ApplicationEventPublisher eventos;

    public GerenciarFeriadosUseCase(CalendarioFeriados feriados, RegistroJornadaRepository registros,
                                    ClassificadorDiaService classificador, MotorCalculoJornadaService motor,
                                    ApplicationEventPublisher eventos) {
        this.feriados = feriados;
        this.registros = registros;
        this.classificador = classificador;
        this.motor = motor;
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
        eventos.publishEvent(new CalendarioAlteradoEvento(data, data));
        registros.buscarPorData(data).ifPresent(registro -> {
            if (registro.reclassificar(classificador.classificar(data), motor)) {
                registros.salvar(registro);
                eventos.publishEvent(new JornadaAtualizadaEvento(data, OrigemAtualizacao.AUSENCIA,
                        RegistroJornadaView.de(registro, motor), motivo));
            }
        });
    }
}
