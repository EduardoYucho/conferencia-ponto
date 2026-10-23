package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Intervalo;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LancarRegistroManualUseCaseTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate SABADO = LocalDate.of(2026, 9, 26);
    private static final LocalDate SEGUNDA = LocalDate.of(2026, 9, 28);

    private final RegistroJornadaRepositoryEmMemoria repository = new RegistroJornadaRepositoryEmMemoria();
    private final AjusteJornadaRepositoryEmMemoria historico = new AjusteJornadaRepositoryEmMemoria();
    private final ComprovanteArquivadoRepositoryEmMemoria arquivos = new ComprovanteArquivadoRepositoryEmMemoria();
    private final MotorCalculoJornadaService motor = new MotorCalculoJornadaService();
    private final ClassificadorDiaService classificador = new ClassificadorDiaService(data -> false);
    private final Clock clock = Clock.fixed(ZonedDateTime.of(SEGUNDA, LocalTime.of(18, 0), SP).toInstant(), SP);

    private final LancarRegistroManualUseCase useCase =
            new LancarRegistroManualUseCase(repository, historico, arquivos, Fixtures.regras(classificador), br.com.conferenciaponto.application.TravaDosMesesConferidos.NENHUMA,
                    evento -> { }, clock);

    private static Intervalo intervalo(String entrada, String saida) {
        return new Intervalo(LocalTime.parse(entrada), LocalTime.parse(saida));
    }

    @Test
    @DisplayName("Sábado: 100% das horas viram crédito e o registro é marcado como manual")
    void sabadoTudoCredito() {
        RegistroJornadaView v = useCase.executar(USUARIO, SABADO, List.of(intervalo("09:00", "12:30")));

        assertThat(v.tipoDia()).isEqualTo(TipoDia.FIM_DE_SEMANA);
        assertThat(v.registroManual()).isTrue();
        assertThat(v.jornadaPrevistaSegundos()).isZero();
        assertThat(v.saldoDiarioSegundos()).isEqualTo(210 * 60);
    }

    @Test
    @DisplayName("Relançar a mesma data substitui o lançamento manual anterior")
    void relancamentoSubstitui() {
        useCase.executar(USUARIO, SABADO, List.of(intervalo("09:00", "12:30")));
        RegistroJornadaView v = useCase.executar(USUARIO, SABADO,
                List.of(intervalo("08:00", "12:00"), intervalo("13:00", "14:00")));

        assertThat(v.saldoDiarioSegundos()).isEqualTo(300 * 60);
        assertThat(repository.quantidade()).isEqualTo(1);
    }

    @Test
    @DisplayName("Cada lançamento à mão (e cada correção dele) fica no histórico do dia, com motivo, quem e quando")
    void historico() {
        useCase.executar(USUARIO, SABADO, List.of(intervalo("09:00", "12:30")), "Compensação da carga semanal", "eduardo");
        useCase.executar(USUARIO, SABADO, List.of(intervalo("09:00", "12:00")), null, "eduardo");
        useCase.executar(USUARIO, SABADO, List.of(intervalo("09:00", "12:00")), "Sem mudança", "eduardo");

        assertThat(historico.listarPorData(USUARIO, SABADO)).hasSize(2).allSatisfy(a -> {
            assertThat(a.origem()).isEqualTo(br.com.conferenciaponto.domain.model.AjusteJornada.Origem.MANUAL);
            assertThat(a.usuario()).isEqualTo("eduardo");
        });
        assertThat(historico.salvos.get(0).antes()).isEmpty();
        assertThat(historico.salvos.get(0).justificativa()).isEqualTo("Compensação da carga semanal");
        assertThat(historico.salvos.get(1).justificativa()).isEqualTo(LancarRegistroManualUseCase.MOTIVO_PADRAO);
        assertThat(historico.salvos.get(1).antes()).containsExactly(LocalTime.of(9, 0), LocalTime.of(12, 30));
        assertThatThrownBy(() -> useCase.executar(USUARIO, SABADO, List.of(intervalo("09:00", "12:00")), "abc", "eduardo"))
                .isInstanceOf(RegraNegocioException.class);
    }

    @Test
    @DisplayName("Dia útil não aceita lançamento manual")
    void rejeitaDiaUtil() {
        assertThatThrownBy(() -> useCase.executar(USUARIO, SEGUNDA, List.of(intervalo("08:00", "12:00"))))
                .isInstanceOf(RegraNegocioException.class)
                .extracting("codigo").isEqualTo("LANCAMENTO_MANUAL_DIA_UTIL");
    }

    @Test
    @DisplayName("Não sobrescreve batidas de relógio já existentes")
    void naoSobrescreveRelogio() {
        new RegistrarBatidaUseCase(repository, Fixtures.regras(classificador), br.com.conferenciaponto.application.TravaDosMesesConferidos.NENHUMA, evento -> { }, clock)
                .executar(USUARIO, SABADO, LocalTime.of(9, 0));

        assertThatThrownBy(() -> useCase.executar(USUARIO, SABADO, List.of(intervalo("09:00", "12:00"))))
                .isInstanceOf(ConflitoException.class);
    }

    @Test
    @DisplayName("Dia útil já batido: a regra de dia útil prevalece sobre o conflito")
    void diaUtilComBatidasRetornaErroDeDiaUtil() {
        new RegistrarBatidaUseCase(repository, Fixtures.regras(classificador), br.com.conferenciaponto.application.TravaDosMesesConferidos.NENHUMA, evento -> { }, clock)
                .executar(USUARIO, SEGUNDA, LocalTime.of(8, 0));

        assertThatThrownBy(() -> useCase.executar(USUARIO, SEGUNDA, List.of(intervalo("09:00", "11:00"))))
                .isInstanceOf(RegraNegocioException.class)
                .extracting("codigo").isEqualTo("LANCAMENTO_MANUAL_DIA_UTIL");
    }

    @Test
    @DisplayName("Rejeita data futura")
    void rejeitaFuturo() {
        assertThatThrownBy(() -> useCase.executar(USUARIO, LocalDate.of(2026, 10, 3), List.of(intervalo("09:00", "12:00"))))
                .extracting("codigo").isEqualTo("LANCAMENTO_NO_FUTURO");
    }

    @Test
    @DisplayName("Dia lançado à mão que recebeu batidas com PDF: relançar não pode mudar nem tirar as batidas comprovadas")
    void naoApagaBatidaComComprovante() {
        useCase.executar(USUARIO, SABADO, List.of(intervalo("08:00", "12:00")));
        var registro = repository.buscarPorData(USUARIO, SABADO).orElseThrow();
        for (LocalTime pdf : List.of(LocalTime.of(13, 0, 10), LocalTime.of(15, 0, 30))) {
            registro.incluirBatida(pdf, motor);
            arquivos.salvar(new br.com.conferenciaponto.domain.model.ComprovanteArquivado(java.util.UUID.randomUUID(),
                    registro.getId(), "x.pdf", br.com.conferenciaponto.domain.model.TipoBatida.ENTRADA_2,
                    java.time.Instant.EPOCH, SABADO.atTime(pdf), "x.pdf", "h" + pdf, 10));
        }
        repository.salvar(registro);

        // a tela preenche sem os segundos: 13:00 e 15:00 não são as batidas comprovadas
        assertThatThrownBy(() -> useCase.executar(USUARIO, SABADO,
                List.of(intervalo("08:30", "12:00"), intervalo("13:00", "15:00"))))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("13:00:10, 15:00:30").hasMessageContaining("Ajustar batidas");
        assertThatThrownBy(() -> useCase.executar(USUARIO, SABADO, List.of(intervalo("08:30", "12:00"))))
                .isInstanceOf(RegraNegocioException.class);
        assertThat(repository.buscarPorData(USUARIO, SABADO).orElseThrow().getBatidas().horarios())
                .containsExactly(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(13, 0, 10), LocalTime.of(15, 0, 30));

        // mantendo as comprovadas, a manhã pode ser corrigida
        RegistroJornadaView v = useCase.executar(USUARIO, SABADO,
                List.of(intervalo("08:30", "12:00"), new Intervalo(LocalTime.of(13, 0, 10), LocalTime.of(15, 0, 30))));
        assertThat(v.saldoDiarioSegundos()).isEqualTo(210 * 60 + 2 * 3600 + 20);
    }
}
