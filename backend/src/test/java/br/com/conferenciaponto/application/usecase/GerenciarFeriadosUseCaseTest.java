package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.CalendarioAlteradoEvento;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.AbrangenciaFeriado;
import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GerenciarFeriadosUseCaseTest {

    private static final LocalDate CORPUS_CHRISTI = LocalDate.of(2026, 6, 4);

    private final CalendarioFeriadosEmMemoria calendario = new CalendarioFeriadosEmMemoria();
    private final RegistroJornadaRepositoryEmMemoria registros = new RegistroJornadaRepositoryEmMemoria();
    private final MotorCalculoJornadaService motor = new MotorCalculoJornadaService();
    private final List<Object> eventos = new ArrayList<>();
    private final GerenciarFeriadosUseCase useCase = new GerenciarFeriadosUseCase(calendario, registros,
            new ClassificadorDiaService(calendario), motor, eventos::add);

    @Test
    @DisplayName("Feriado num dia sem registro: entra no calendário e avisa a tela")
    void feriadoSemRegistro() {
        Feriado f = useCase.cadastrar(CORPUS_CHRISTI, " Corpus Christi ", AbrangenciaFeriado.MUNICIPAL);

        assertThat(f.descricao()).isEqualTo("Corpus Christi");
        assertThat(calendario.isFeriado(CORPUS_CHRISTI)).isTrue();
        assertThat(useCase.listar(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .singleElement().extracting(Feriado::abrangencia).isEqualTo(AbrangenciaFeriado.MUNICIPAL);
        assertThat(eventos).singleElement().isInstanceOf(CalendarioAlteradoEvento.class);
    }

    @Test
    @DisplayName("Feriado num dia trabalhado: o débito vira crédito; removendo, volta a ser dia útil")
    void feriadoReclassificaODia() {
        RegistroJornada r = RegistroJornada.novo(CORPUS_CHRISTI, TipoDia.UTIL);
        r.incluirBatida(LocalTime.of(8, 0), motor);
        r.incluirBatida(LocalTime.of(12, 0), motor);
        registros.salvar(r);
        assertThat(r.getSaldoDiarioSegundos()).isEqualTo(4 * 3600 - 31_680);

        useCase.cadastrar(CORPUS_CHRISTI, "Corpus Christi", null);

        RegistroJornada feriado = registros.buscarPorData(CORPUS_CHRISTI).orElseThrow();
        assertThat(feriado.getTipoDia()).isEqualTo(TipoDia.FERIADO);
        assertThat(feriado.getSaldoDiarioSegundos()).isEqualTo(4 * 3600);
        assertThat(eventos).hasSize(2).last().isInstanceOf(JornadaAtualizadaEvento.class);

        useCase.excluir(CORPUS_CHRISTI);

        assertThat(registros.buscarPorData(CORPUS_CHRISTI).orElseThrow().getTipoDia()).isEqualTo(TipoDia.UTIL);
        assertThat(calendario.isFeriado(CORPUS_CHRISTI)).isFalse();
    }

    @Test
    @DisplayName("Data que já é feriado: conflito; remover o que não existe: 404")
    void validacoes() {
        useCase.cadastrar(CORPUS_CHRISTI, "Corpus Christi", AbrangenciaFeriado.EMPRESA);

        assertThatThrownBy(() -> useCase.cadastrar(CORPUS_CHRISTI, "Outro", null))
                .isInstanceOf(ConflitoException.class).hasMessageContaining("04/06/2026 já é feriado: Corpus Christi");
        assertThatThrownBy(() -> useCase.excluir(LocalDate.of(2026, 6, 5)))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }
}
