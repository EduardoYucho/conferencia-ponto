package br.com.conferenciaponto.domain.service;

import br.com.conferenciaponto.domain.model.ApuracaoDiaria;
import br.com.conferenciaponto.domain.model.Batidas;
import br.com.conferenciaponto.domain.model.StatusJornada;
import br.com.conferenciaponto.domain.model.TipoDia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalTime;
import java.util.Arrays;

import static br.com.conferenciaponto.domain.model.TipoBatida.ENTRADA_1;
import static br.com.conferenciaponto.domain.model.TipoBatida.ENTRADA_2;
import static br.com.conferenciaponto.domain.model.TipoBatida.ENTRADA_3;
import static br.com.conferenciaponto.domain.model.TipoBatida.SAIDA_1;
import static br.com.conferenciaponto.domain.model.TipoBatida.SAIDA_2;
import static br.com.conferenciaponto.domain.model.TipoBatida.SAIDA_3;
import static org.assertj.core.api.Assertions.assertThat;

class MotorCalculoJornadaServiceTest {

    private final MotorCalculoJornadaService motor = new MotorCalculoJornadaService();

    private static LocalTime t(String horario) {
        return LocalTime.parse(horario);
    }

    private static Batidas batidas(String horarios) {
        return Batidas.deHorarios(Arrays.stream(horarios.trim().split("\\s+")).map(LocalTime::parse).toList());
    }

    /** "-00:14:51" -> -891 */
    private static int segundos(String duracao) {
        int sinal = duracao.startsWith("-") ? -1 : 1;
        String[] p = duracao.replace("-", "").split(":");
        return sinal * (Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + Integer.parseInt(p[2]));
    }

    @Test
    @DisplayName("Jornada base padrão é 08:48 = 31.680 segundos; dias não úteis têm base zero")
    void jornadaBase() {
        assertThat(motor.jornadaBaseSegundos()).isEqualTo(528 * 60);
        assertThat(motor.jornadaPrevistaSegundos(TipoDia.UTIL)).isEqualTo(31_680);
        assertThat(motor.jornadaPrevistaSegundos(TipoDia.FIM_DE_SEMANA)).isZero();
        assertThat(motor.jornadaPrevistaSegundos(TipoDia.FERIADO)).isZero();
    }

    /**
     * Dias reais dos relatórios de banco de horas do RH, nov/2025 a ago/2026: o motor precisa
     * chegar exatamente ao mesmo "Hr. Trabalhadas" e "Hr. Extra/Falta", ao segundo. Inclui os casos de
     * fronteira da tolerância (5:05, 5:22...) e dias com 6 batidas.
     */
    @ParameterizedTest(name = "{0}: {2} → trabalhado {3}, saldo {4}")
    @CsvSource(delimiter = ';', value = {
            "02/03/2026;UTIL;08:04:09 12:00:13 13:00:40 17:48:15;08:43:39;00:00:00",
            "04/03/2026;UTIL;07:59:25 12:00:05 13:03:53 18:12:21;09:09:08;00:24:21",
            "05/03/2026;UTIL;08:01:06 12:00:23 13:02:04 15:02:06 15:16:57 17:51:00;08:33:22;-00:14:51",
            "09/03/2026;UTIL;08:09:16 12:01:04 13:00:58 17:55:02;08:45:52;-00:02:14",
            "10/03/2026;UTIL;08:25:51 12:00:38 12:53:25 17:56:37;08:37:59;-00:10:39",
            "16/03/2026;UTIL;08:02:48 12:01:30 12:55:51 18:43:56;09:46:47;00:55:56",
            "18/03/2026;UTIL;07:54:31 12:00:13 13:03:57 17:48:17;08:50:02;00:05:29",
            "05/12/2025;UTIL;07:54:44 12:00:11 13:00:00 17:53:48;08:59:15;00:11:04",
            "10/12/2025;UTIL;07:58:01 12:00:19 13:01:53 17:53:02;08:53:27;00:05:02",
            "18/12/2025;UTIL;07:56:59 12:00:33 12:54:38 13:26:26 13:44:55 18:04:10;08:54:37;00:03:03",
            "29/12/2025;UTIL;07:59:12 12:01:05 12:53:44 13:06:28 13:06:42 17:53:06;09:01:01;00:11:08",
            "24/12/2025;UTIL;07:55:09 12:00:14;04:05:05;-04:48:00",
            "25/02/2026;UTIL;08:00:25 12:00:16 12:57:21 17:48:13 18:28:06 20:35:35;10:58:12;02:07:29",
            "09/04/2026;UTIL;08:04:22 12:18:36;04:14:14;-04:29:24",
            "30/04/2026;UTIL;08:05:10 12:00:14 12:57:54 17:49:22;08:46:32;-00:05:10",
            "01/06/2026;UTIL;08:05:22 12:00:15 12:58:12 17:48:25;08:45:06;-00:05:22",
            "19/06/2026;UTIL;08:00:06 12:07:56 13:00:22 17:53:09;09:00:37;00:13:05",
            "24/06/2026;UTIL;08:05:52 12:00:25 12:58:32 18:03:21;08:59:22;00:09:29",
            "08/07/2026;UTIL;08:03:33 11:01:37 11:15:45 12:00:21 12:49:38 17:53:37;08:46:39;00:01:51",
            "13/08/2026;UTIL;08:00:11 12:05:53 13:00:28 17:48:03;08:53:17;00:05:53",
            "20/08/2026;UTIL;08:05:15 12:00:02 12:54:07 17:48:18;08:48:58;00:00:38",
            "22/08/2026;FIM_DE_SEMANA;08:00:06 12:00:10;04:00:04;04:00:04",
            "28/08/2026;UTIL;08:05:05 12:00:05 12:54:28 17:48:24;08:48:56;00:00:27",
            "29/05/2026;UTIL;07:53:38 12:00:04 13:02:09 17:55:58;09:00:15;00:14:20",
            "28/05/2026;UTIL;08:06:05 12:00:16 13:02:39 17:28:30;08:20:02;-00:25:35",
    })
    @DisplayName("Reproduz ao segundo o relatório do RH")
    void reproduzRelatorioDoRh(String data, TipoDia tipo, String horarios, String trabalhadoRh, String saldoRh) {
        ApuracaoDiaria a = motor.apurar(tipo, batidas(horarios));

        assertThat(a.status()).isEqualTo(StatusJornada.FECHADA);
        assertThat(a.segundosTrabalhados()).as("trabalhado").isEqualTo(segundos(trabalhadoRh));
        assertThat(a.saldoDiarioSegundos()).as("saldo").isEqualTo(segundos(saldoRh));
    }

    @Nested
    @DisplayName("Carga real de 28/09/2026 (jornada em andamento)")
    class CargaReal28Setembro2026 {

        private final ApuracaoDiaria apuracao = motor.apurar(TipoDia.UTIL,
                new Batidas(t("08:02:31"), t("12:00:15"), t("12:59:59"), null));

        @Test
        @DisplayName("Entrada 1 real 08:02:31 -> considerada 08:00:00 (2:31 de atraso, tolerado)")
        void entrada1() {
            assertThat(apuracao.considerado(ENTRADA_1)).isEqualTo(t("08:00"));
            assertThat(apuracao.marcacao(ENTRADA_1).toleranciaAplicada()).isTrue();
            assertThat(apuracao.marcacao(ENTRADA_1).desvioSegundos()).isEqualTo(151);
        }

        @Test
        @DisplayName("Saída 1 real 12:00:15 -> 12:00:00; Entrada 2 real 12:59:59 -> 13:00:00")
        void almoco() {
            assertThat(apuracao.considerado(SAIDA_1)).isEqualTo(t("12:00"));
            assertThat(apuracao.considerado(ENTRADA_2)).isEqualTo(t("13:00"));
            assertThat(apuracao.marcacao(ENTRADA_2).desvioSegundos()).isEqualTo(-1);
        }

        @Test
        @DisplayName("Saída 2 NULL -> jornada em andamento, saldo não apurado, trabalhado bruto do 1º intervalo")
        void emAndamento() {
            assertThat(apuracao.marcacao(SAIDA_2).real()).isNull();
            assertThat(apuracao.status()).isEqualTo(StatusJornada.EM_ANDAMENTO);
            assertThat(apuracao.segundosTrabalhados()).isEqualTo(segundos("03:57:44")); // 08:02:31 → 12:00:15
            assertThat(apuracao.saldoDiarioSegundos()).isNull();
            assertThat(apuracao.jornadaPrevistaSegundos()).isEqualTo(31_680);
        }

        @Test
        @DisplayName("Fechando às 17:50:10 (2:10 depois, tolerado): saldo zero")
        void fechamento() {
            ApuracaoDiaria fechada = motor.apurar(TipoDia.UTIL,
                    new Batidas(t("08:02:31"), t("12:00:15"), t("12:59:59"), t("17:50:10")));
            assertThat(fechada.considerado(SAIDA_2)).isEqualTo(t("17:48"));
            assertThat(fechada.status()).isEqualTo(StatusJornada.FECHADA);
            assertThat(fechada.saldoDiarioSegundos()).isZero();
        }
    }

    @ParameterizedTest(name = "{0} vs 08:00 -> {1}")
    @CsvSource({
            "08:00:00, 08:00:00",
            "08:05:00, 08:00:00",   // limite inclusivo: exatamente 5:00
            "08:05:01, 08:05:01",   // 1 segundo além: vale o horário real
            "08:05:22, 08:05:22",
            "07:55:00, 08:00:00",
            "07:54:59, 07:54:59",
    })
    @DisplayName("Tolerância de 5:00 por batida, com segundos (limite inclusivo)")
    void tolerancia(String real, String esperado) {
        assertThat(motor.aplicarTolerancia(t(real), t("08:00"))).isEqualTo(t(esperado));
    }

    @Test
    @DisplayName("Dia útil exatamente na grade: 08:48 trabalhadas, saldo zero")
    void naGrade() {
        ApuracaoDiaria a = motor.apurar(TipoDia.UTIL, batidas("08:00 12:00 13:00 17:48"));
        assertThat(a.segundosTrabalhados()).isEqualTo(31_680);
        assertThat(a.saldoDiarioSegundos()).isZero();
    }

    @Test
    @DisplayName("Atraso fora da tolerância gera débito exato, com segundos")
    void atraso() {
        ApuracaoDiaria a = motor.apurar(TipoDia.UTIL, batidas("08:20:45 12:00 13:00 17:48"));
        assertThat(a.considerado(ENTRADA_1)).isEqualTo(t("08:20:45"));
        assertThat(a.saldoDiarioSegundos()).isEqualTo(-segundos("00:20:45"));
    }

    @Test
    @DisplayName("Tolerância por batida isolada: 4 desvios de até 5:00 não geram saldo")
    void quatroDesviosToleradoss() {
        ApuracaoDiaria a = motor.apurar(TipoDia.UTIL, batidas("08:05:00 11:55:00 13:05:00 17:43:00"));
        assertThat(a.saldoDiarioSegundos()).isZero();
        assertThat(a.segundosTrabalhados()).isEqualTo(segundos("08:28:00")); // bruto, sem a tolerância
    }

    @Test
    @DisplayName("Dia útil só com o 1º intervalo: fechado, com o débito da tarde")
    void meioPeriodo() {
        ApuracaoDiaria a = motor.apurar(TipoDia.UTIL, batidas("08:00 12:00"));
        assertThat(a.status()).isEqualTo(StatusJornada.FECHADA);
        assertThat(a.saldoDiarioSegundos()).isEqualTo(-segundos("04:48:00"));
    }

    @Test
    @DisplayName("Seis batidas: a saída no meio do expediente conta como falta; batidas extras não têm horário de grade")
    void seisBatidas() {
        ApuracaoDiaria a = motor.apurar(TipoDia.UTIL, batidas("08:00 11:00 11:15 12:00 13:00 17:48"));
        assertThat(a.saldoDiarioSegundos()).isEqualTo(-segundos("00:15:00"));
        assertThat(a.marcacao(SAIDA_1).oficial()).isNull();     // 11:00 é saída extra
        assertThat(a.marcacao(ENTRADA_2).oficial()).isNull();   // 11:15 é volta extra
        assertThat(a.marcacao(SAIDA_2).oficial()).isEqualTo(t("12:00"));
        assertThat(a.marcacao(ENTRADA_3).oficial()).isEqualTo(t("13:00"));
        assertThat(a.marcacao(SAIDA_3).oficial()).isEqualTo(t("17:48"));
    }

    @Test
    @DisplayName("Fim de semana: base zero, sem tolerância, 100% crédito (com segundos)")
    void fimDeSemana() {
        ApuracaoDiaria a = motor.apurar(TipoDia.FIM_DE_SEMANA, batidas("09:02:10 12:30:00"));
        assertThat(a.considerado(ENTRADA_1)).isEqualTo(t("09:02:10"));
        assertThat(a.marcacao(ENTRADA_1).oficial()).isNull();
        assertThat(a.marcacao(ENTRADA_1).toleranciaAplicada()).isFalse();
        assertThat(a.jornadaPrevistaSegundos()).isZero();
        assertThat(a.saldoDiarioSegundos()).isEqualTo(segundos("03:27:50"));
    }

    @Test
    @DisplayName("Feriado com dois intervalos: soma integral como crédito")
    void feriado() {
        ApuracaoDiaria a = motor.apurar(TipoDia.FERIADO, batidas("08:00 12:00 13:00 15:00"));
        assertThat(a.saldoDiarioSegundos()).isEqualTo(segundos("06:00:00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"08:00", "08:00 12:00 13:00", "08:00 12:00 13:00 17:48 18:30"})
    @DisplayName("Número ímpar de batidas: em andamento, sem saldo")
    void impar(String horarios) {
        ApuracaoDiaria a = motor.apurar(TipoDia.UTIL, batidas(horarios));
        assertThat(a.status()).isEqualTo(StatusJornada.EM_ANDAMENTO);
        assertThat(a.saldoDiarioSegundos()).isNull();
    }

    @Test
    @DisplayName("Sempre 6 marcações; posições vazias do dia útil mostram o horário da grade")
    void seisMarcacoes() {
        ApuracaoDiaria a = motor.apurar(TipoDia.UTIL, batidas("08:01"));
        assertThat(a.marcacoes()).hasSize(6);
        assertThat(a.marcacao(SAIDA_1).oficial()).isEqualTo(t("12:00"));
        assertThat(a.marcacao(SAIDA_3).oficial()).isNull();
    }
}
