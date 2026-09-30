package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.StatusJornada;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AjustarBatidasUseCaseTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate HOJE = LocalDate.of(2026, 9, 29);
    private static final LocalDate DIA_26_06 = LocalDate.of(2026, 6, 26);
    private static final String MOTIVO = "Corrigido pelo RH: falha no relógio";

    private final RegistroJornadaRepositoryEmMemoria registros = new RegistroJornadaRepositoryEmMemoria();
    private final ComprovanteArquivadoRepositoryEmMemoria arquivos = new ComprovanteArquivadoRepositoryEmMemoria();
    private final AjusteJornadaRepositoryEmMemoria ajustes = new AjusteJornadaRepositoryEmMemoria();
    private final MotorCalculoJornadaService motor = new MotorCalculoJornadaService();
    private final List<Object> eventos = new ArrayList<>();
    private final Clock clock = Clock.fixed(ZonedDateTime.of(HOJE, LocalTime.of(11, 0), SP).toInstant(), SP);

    private final AjustarBatidasUseCase useCase = new AjustarBatidasUseCase(registros, arquivos, ajustes,
            new ClassificadorDiaService(data -> false), motor, eventos::add, clock);

    private static LocalTime t(String horario) {
        return LocalTime.parse(horario);
    }

    /** 26/06 como ficou após importar os PDFs: faltou a volta do almoço. */
    private RegistroJornada diaImportadoComFalha() {
        RegistroJornada registro = RegistroJornada.novo(DIA_26_06, TipoDia.UTIL);
        List<String> horarios = List.of("08:01:11", "12:00:03", "17:42:57");
        for (String h : horarios) {
            registro.incluirBatida(t(h), motor);
        }
        registros.salvar(registro);
        TipoBatida[] tipos = {TipoBatida.ENTRADA_1, TipoBatida.SAIDA_1, TipoBatida.ENTRADA_2};
        for (int i = 0; i < horarios.size(); i++) {
            arquivos.salvar(new ComprovanteArquivado(UUID.randomUUID(), registro.getId(), "2026/06/c" + i + ".pdf",
                    tipos[i], Instant.EPOCH, LocalDateTime.of(DIA_26_06, t(horarios.get(i))),
                    "comprovanteponto (" + i + ").pdf", "hash" + i, 10));
        }
        return registro;
    }

    @Test
    @DisplayName("Inclui a volta do almoço: fecha o dia, move o PDF das 17:42 para Saída 2 e grava o histórico")
    void ajustaDiaComPdfs() {
        RegistroJornada registro = diaImportadoComFalha();

        RegistroJornadaView v = useCase.executar(DIA_26_06,
                List.of(t("08:01:11"), t("12:00:03"), t("13:00"), t("17:42:57")), MOTIVO, "eduardo");

        assertThat(v.status()).isEqualTo(StatusJornada.FECHADA);
        assertThat(v.horariosAjustados()).containsExactly(t("13:00"));
        // tolerância devolve 1:11 da entrada e tira 0:03 da saída do almoço; a saída às 17:42:57 foi
        // 5:03 antes das 17:48 (fora da tolerância): falta de exatos 5:03
        assertThat(v.saldoDiarioSegundos()).isEqualTo(-303);
        assertThat(arquivos.listarPorRegistro(registro.getId()))
                .extracting(ComprovanteArquivado::tipoBatida)
                .containsExactly(TipoBatida.ENTRADA_1, TipoBatida.SAIDA_1, TipoBatida.SAIDA_2);

        AjusteJornada ajuste = ajustes.salvos.get(0);
        assertThat(ajuste.antes()).containsExactly(t("08:01:11"), t("12:00:03"), t("17:42:57"));
        assertThat(ajuste.depois()).containsExactly(t("08:01:11"), t("12:00:03"), t("13:00"), t("17:42:57"));
        assertThat(ajuste.usuario()).isEqualTo("eduardo");
        assertThat(ajuste.justificativa()).isEqualTo(MOTIVO);

        JornadaAtualizadaEvento evento = (JornadaAtualizadaEvento) eventos.get(0);
        assertThat(evento.origem()).isEqualTo(OrigemAtualizacao.AJUSTE);
        assertThat(evento.mensagem()).contains("26/06", "eduardo", "08:01:11 12:00:03 17:42:57 → 08:01:11 12:00:03 13:00:00 17:42:57");
        assertThat(useCase.historico(DIA_26_06)).hasSize(1);
    }

    @Test
    @DisplayName("Dia sem nenhum registro (relógio fora do ar o dia todo): cria o dia com as 4 batidas ajustadas")
    void diaInteiroSemRegistro() {
        LocalDate dia = LocalDate.of(2026, 7, 1);
        RegistroJornadaView v = useCase.executar(dia,
                List.of(t("08:00"), t("12:00"), t("13:00"), t("17:48")), MOTIVO, "eduardo");

        assertThat(v.saldoDiarioSegundos()).isZero();
        assertThat(v.horariosAjustados()).hasSize(4);
        assertThat(ajustes.salvos.get(0).antes()).isEmpty();
    }

    @Test
    @DisplayName("Não altera batida com PDF")
    void naoAlteraComprovada() {
        diaImportadoComFalha();

        assertThatThrownBy(() -> useCase.executar(DIA_26_06,
                List.of(t("08:00"), t("12:00:03"), t("13:00"), t("17:42:57")), MOTIVO, "eduardo"))
                .isInstanceOf(RegraNegocioException.class)
                .extracting("codigo").isEqualTo("AJUSTE_ALTERA_BATIDA_COMPROVADA");
        assertThat(ajustes.salvos).isEmpty();
        assertThat(eventos).isEmpty();
    }

    @Test
    @DisplayName("Exige justificativa e não aceita data ou horário no futuro")
    void validacoes() {
        List<LocalTime> dia = List.of(t("08:00"), t("12:00"));

        assertThatThrownBy(() -> useCase.executar(LocalDate.of(2026, 7, 1), dia, "  ok ", "eduardo"))
                .extracting("codigo").isEqualTo("AJUSTE_SEM_JUSTIFICATIVA");
        assertThatThrownBy(() -> useCase.executar(HOJE.plusDays(1), dia, MOTIVO, "eduardo"))
                .extracting("codigo").isEqualTo("AJUSTE_DATA_FUTURA");
        assertThatThrownBy(() -> useCase.executar(HOJE, List.of(t("08:00"), t("12:00")), MOTIVO, "eduardo"))
                .extracting("codigo").isEqualTo("AJUSTE_HORARIO_FUTURO"); // agora são 11:00
        assertThat(ajustes.salvos).isEmpty();
    }

    @Test
    @DisplayName("Conforme RH: alinha os segundos das batidas com PDF, inclui a que faltou e só ela fica marcada")
    void conformeRhAlinhaSegundos() {
        diaImportadoComFalha(); // PDFs 08:01:11, 12:00:03, 17:42:57

        RegistroJornadaView v = useCase.conformeRh(DIA_26_06,
                List.of(t("08:01:10"), t("12:00:02"), t("12:58:04"), t("17:42:56")), "Conforme relatório do RH", "eduardo");

        assertThat(v.saldoDiarioSegundos()).isEqualTo(-304); // igual ao RH (-00:05:04)
        assertThat(v.horariosAjustados()).containsExactly(t("12:58:04"));
        assertThat(arquivos.listarPorRegistro(registros.buscarPorData(DIA_26_06).orElseThrow().getId()))
                .extracting(ComprovanteArquivado::tipoBatida)
                .containsExactlyInAnyOrder(TipoBatida.ENTRADA_1, TipoBatida.SAIDA_1, TipoBatida.SAIDA_2);
        assertThat(eventos).last().isInstanceOfSatisfying(JornadaAtualizadaEvento.class,
                e -> assertThat(e.origem()).isEqualTo(OrigemAtualizacao.CONCILIACAO));
    }

    @Test
    @DisplayName("Conforme RH: batida com PDF não some nem se move mais de 1 minuto")
    void conformeRhNaoRemoveComprovada() {
        diaImportadoComFalha();

        assertThatThrownBy(() -> useCase.conformeRh(DIA_26_06,
                List.of(t("08:01:11"), t("12:02:00"), t("13:00"), t("17:42:57")), "Conforme relatório do RH", "eduardo"))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("12:00:03");
    }

    @Test
    @DisplayName("Conforme RH num dia sem registro: cria o dia com as batidas do RH, sem marca de ajuste, mesmo com batidas a segundos de distância")
    void conformeRhDiaNovo() {
        LocalDate dia2912 = LocalDate.of(2025, 12, 29);

        RegistroJornadaView v = useCase.conformeRh(dia2912, List.of(t("07:59:12"), t("12:01:05"), t("12:53:44"),
                t("13:06:28"), t("13:06:42"), t("17:53:06")), "Conforme relatório do RH", "eduardo");

        assertThat(v.saldoDiarioSegundos()).isEqualTo(668); // +00:11:08, como no RH
        assertThat(v.horariosAjustados()).isEmpty();
        assertThat(ajustes.listarPorData(dia2912)).singleElement()
                .satisfies(a -> assertThat(a.antes()).isEmpty());
    }
}
