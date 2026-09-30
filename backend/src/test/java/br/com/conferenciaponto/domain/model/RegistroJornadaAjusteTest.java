package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ajuste manual das batidas (relógio falhou e o RH corrigiu). */
class RegistroJornadaAjusteTest {

    private final MotorCalculoJornadaService motor = new MotorCalculoJornadaService();

    private static LocalTime t(String horario) {
        return LocalTime.parse(horario);
    }

    /** 24/06: faltou a saída do almoço; a importação deixou 12:58 como Saída 1 e o dia "em andamento". */
    private RegistroJornada diaComBatidaFaltando() {
        RegistroJornada registro = RegistroJornada.novo(LocalDate.of(2026, 6, 24), TipoDia.UTIL);
        registro.incluirBatida(t("08:05:53"), motor);
        registro.incluirBatida(t("12:58:33"), motor);
        registro.incluirBatida(t("18:03:22"), motor);
        return registro;
    }

    private static final Set<LocalTime> PDFS_24_06 = Set.of(t("08:05:53"), t("12:58:33"), t("18:03:22"));

    @Test
    @DisplayName("Incluir a batida esquecida reorganiza as colunas, fecha o dia e marca só a batida nova")
    void incluiBatidaEsquecida() {
        RegistroJornada registro = diaComBatidaFaltando();
        assertThat(registro.getStatus()).isEqualTo(StatusJornada.EM_ANDAMENTO);

        List<LocalTime> antes = registro.ajustarBatidas(
                List.of(t("08:05:53"), t("12:58:33"), t("18:03:22"), t("12:00")), PDFS_24_06, motor);

        assertThat(antes).containsExactly(t("08:05:53"), t("12:58:33"), t("18:03:22"));
        assertThat(registro.getBatidas()).isEqualTo(
                new Batidas(t("08:05:53"), t("12:00"), t("12:58:33"), t("18:03:22")));
        assertThat(registro.getStatus()).isEqualTo(StatusJornada.FECHADA);
        // igual ao relatório do RH de 24/06: 08:05:53 é 5:53 de atraso (fora da tolerância),
        // 12:58:33 volta tolerada, 18:03:22 é 15:22 de extra → saldo +09:29
        assertThat(registro.getSegundosTrabalhados()).isEqualTo(32_336);
        assertThat(registro.getSaldoDiarioSegundos()).isEqualTo(569);
        assertThat(registro.getHorariosAjustados()).containsExactly(t("12:00"));
    }

    @Test
    @DisplayName("Corrigir uma batida ajustada troca a marcação; as demais continuam como estavam")
    void corrigeBatidaAjustada() {
        RegistroJornada registro = diaComBatidaFaltando();
        registro.ajustarBatidas(List.of(t("08:05:53"), t("12:00"), t("12:58:33"), t("18:03:22")), PDFS_24_06, motor);

        registro.ajustarBatidas(List.of(t("08:05:53"), t("12:02"), t("12:58:33"), t("18:03:22")), PDFS_24_06, motor);

        assertThat(registro.getBatidas().saida1()).isEqualTo(t("12:02"));
        assertThat(registro.getHorariosAjustados()).containsExactly(t("12:02"));
    }

    @Test
    @DisplayName("Batida com comprovante em PDF não pode ser removida nem alterada")
    void protegeBatidaComprovada() {
        RegistroJornada registro = diaComBatidaFaltando();

        assertThatThrownBy(() -> registro.ajustarBatidas(
                List.of(t("08:00"), t("12:00"), t("12:58:33"), t("18:03:22")), PDFS_24_06, motor))
                .isInstanceOf(RegraNegocioException.class)
                .extracting("codigo").isEqualTo("AJUSTE_ALTERA_BATIDA_COMPROVADA");
        assertThat(registro.getBatidas().quantidade()).isEqualTo(3); // nada mudou
    }

    @Test
    @DisplayName("Batida sem PDF (ex.: botão bater ponto) pode ser corrigida")
    void corrigeBatidaSemPdf() {
        RegistroJornada registro = diaComBatidaFaltando();

        registro.ajustarBatidas(List.of(t("08:00"), t("12:00"), t("12:58:33"), t("18:03:22")),
                Set.of(t("12:58:33"), t("18:03:22")), motor);

        assertThat(registro.getHorariosAjustados()).containsExactly(t("08:00"), t("12:00"));
    }

    @Test
    @DisplayName("Rejeita: mais de 6 batidas, batidas a menos de 1 minuto, nada alterado e lista vazia")
    void validacoes() {
        RegistroJornada registro = diaComBatidaFaltando();

        assertThatThrownBy(() -> registro.ajustarBatidas(List.of(t("08:05:53"), t("10:00"), t("10:30"),
                t("12:00"), t("12:58:33"), t("17:00"), t("18:03:22")), PDFS_24_06, motor))
                .extracting("codigo").isEqualTo("AJUSTE_MAIS_DE_6_BATIDAS");
        assertThatThrownBy(() -> registro.ajustarBatidas(List.of(t("08:05:53"), t("12:58"),
                t("12:58:33"), t("18:03:22")), PDFS_24_06, motor))
                .extracting("codigo").isEqualTo("AJUSTE_BATIDAS_PROXIMAS");
        assertThatThrownBy(() -> registro.ajustarBatidas(List.of(t("18:03:22"), t("08:05:53"), t("12:58:33")),
                PDFS_24_06, motor))
                .extracting("codigo").isEqualTo("AJUSTE_SEM_MUDANCA");
        assertThatThrownBy(() -> registro.ajustarBatidas(List.of(), Set.of(), motor))
                .extracting("codigo").isEqualTo("AJUSTE_SEM_BATIDAS");
    }
}
