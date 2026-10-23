package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.view.BancoDeHorasMesView;
import br.com.conferenciaponto.application.view.BancoDeHorasMesView.Bloco;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;

import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A carga semanal no banco de horas: horário de 40h com carga de 42h. Cada dia útil fechado leva 24 min de débito,
 * o sábado compensa e o saldo de cada dia (contra a grade, como o RH) não muda.
 */
class CargaSemanalNoBancoTest {

    private static final int POR_DIA = 24 * 60;
    private static final YearMonth OUTUBRO = YearMonth.of(2026, 10);

    private final CenarioDaCarga c = new CenarioDaCarga();

    @BeforeEach
    void outubro() {
        // 01, 02 e 05 a 08 (09 sem registro), 13 a 16, 19 e 20; 21 (hoje) em andamento; sábado 10 de manhã
        for (int d : new int[]{1, 2, 5, 6, 7, 8, 13, 14, 15, 16, 19, 20}) {
            c.diaCheio(LocalDate.of(2026, 10, d));
        }
        c.dia(LocalDate.of(2026, 10, 21), "08:00", "12:00", "13:00");
        c.dia(LocalDate.of(2026, 10, 10), "08:00", "12:00");
        // setembro, ainda com o horário padrão (sem carga informada): nada muda
        c.dia(LocalDate.of(2026, 9, 30), "08:00", "12:00", "13:00", "17:48");
    }

    @Test
    @DisplayName("O saldo do mês soma a diferença da carga (24 min por dia útil fechado) e o sábado; o dia continua zero")
    void saldoDoMes() {
        SaldoMensal outubro = c.jornada.resumo(USUARIO, OUTUBRO);

        assertThat(outubro.segundosDaCarga()).isEqualTo(-12 * POR_DIA);
        assertThat(outubro.saldoJornadasSegundos()).as("os dias, contra a grade").isEqualTo(4 * 3600);
        assertThat(outubro.saldoMensalSegundos()).isEqualTo(4 * 3600 - 12 * POR_DIA);
        assertThat(c.registros.buscarPorData(USUARIO, LocalDate.of(2026, 10, 13)).orElseThrow().getSaldoDiarioSegundos())
                .isZero();
        assertThat(c.jornada.resumo(USUARIO, YearMonth.of(2026, 9)).segundosDaCarga()).as("setembro: sem carga").isZero();
        assertThat(c.carga.temDiferenca(USUARIO)).isTrue();
    }

    @Test
    @DisplayName("O banco do ciclo também leva a diferença (o mesmo número no painel, na auditoria e na planilha)")
    void bancoDoCiclo() {
        c.ciclo.garantirCicloAberto(USUARIO);
        assertThat(c.ciclo.atual(USUARIO).saldoSegundos()).isEqualTo(4 * 3600 - 12 * POR_DIA);
    }

    @Test
    @DisplayName("Compensação: 21 dias úteis (12/10 é feriado) = 8h24 no mês; o sábado pagou 4h; faltam 4h24")
    void compensacao() {
        BancoDeHorasMesView.Compensacao comp = c.banco.compensacao(USUARIO, OUTUBRO);

        assertThat(comp.cargaSemanal()).isEqualTo(42 * 3600);
        assertThat(comp.somaDoHorario()).isEqualTo(40 * 3600);
        assertThat(comp.diferencaSemanal()).isEqualTo(2 * 3600);
        assertThat(comp.diferencaPorDia()).isEqualTo(POR_DIA);
        assertThat(comp.cargaInformada()).isTrue();
        assertThat(comp.diasUteis()).isEqualTo(21);
        assertThat(comp.diasUteisContados()).isEqualTo(12);
        assertThat(comp.diferencaPrevista()).isEqualTo(-21 * POR_DIA);
        assertThat(comp.diferencaContada()).isEqualTo(-12 * POR_DIA);
        assertThat(comp.semExpedienteSegundos()).isEqualTo(4 * 3600);
        assertThat(comp.compensado()).isEqualTo(4 * 3600);
        assertThat(comp.paraOBanco()).isZero();
        assertThat(comp.falta()).isEqualTo(21 * POR_DIA - 4 * 3600);
        assertThat(comp.finsDeSemanaRestantes()).containsExactly(
                new Bloco(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25)),
                new Bloco(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31)));
    }

    @Test
    @DisplayName("O que o fim de semana passa da diferença vai para o banco (e quem não tem diferença leva tudo)")
    void excedenteVaiParaOBanco() {
        c.dia(LocalDate.of(2026, 10, 17), "08:00", "12:00", "13:00", "17:00");
        c.dia(LocalDate.of(2026, 10, 18), "08:00", "11:00");

        BancoDeHorasMesView.Compensacao comp = c.banco.compensacao(USUARIO, OUTUBRO);
        assertThat(comp.semExpedienteSegundos()).isEqualTo(15 * 3600);
        assertThat(comp.compensado()).isEqualTo(21 * POR_DIA);
        assertThat(comp.paraOBanco()).isEqualTo(15 * 3600 - 21 * POR_DIA);
        assertThat(comp.falta()).isZero();

        BancoDeHorasMesView.Compensacao setembro = c.banco.compensacao(USUARIO, YearMonth.of(2026, 9));
        assertThat(setembro.diferencaSemanal()).isZero();
        assertThat(setembro.compensado()).isZero();
    }

    @Test
    @DisplayName("Mês que terminou: o que faltou é o que de fato entrou no banco (dias úteis fechados)")
    void mesQueTerminou() {
        c.em(LocalDate.of(2026, 11, 3), LocalTime.of(9, 0));
        BancoDeHorasMesView.Compensacao comp = c.banco.compensacao(USUARIO, OUTUBRO);

        assertThat(comp.diferencaContada()).isEqualTo(-12 * POR_DIA);
        assertThat(comp.falta()).isEqualTo(12 * POR_DIA - 4 * 3600);
        assertThat(comp.finsDeSemanaRestantes()).isEmpty();
    }

    @Test
    @DisplayName("A tela: a diferença de cada dia, os dias sem expediente, o dia útil sem registro e o horário de referência")
    void tela() {
        c.lancamentos.salvar(LancamentoBanco.novo(USUARIO, LocalDate.of(2026, 10, 2), -1_800, "Saída antecipada",
                "eduardo", Instant.EPOCH));

        BancoDeHorasMesView v = c.banco.mes(USUARIO, OUTUBRO);

        assertThat(v.horario().cargaSemanalSegundos()).isEqualTo(42 * 3600);
        assertThat(v.diferencaDoDia()).hasSize(12).allSatisfy((data, valor) -> assertThat(valor).isEqualTo(-POR_DIA));
        assertThat(v.diferencaDoDia()).doesNotContainKeys(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 21));
        assertThat(v.semExpediente()).contains(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11))
                .doesNotContain(LocalDate.of(2026, 10, 12)).hasSize(9);
        assertThat(v.semRegistro()).containsExactly(LocalDate.of(2026, 10, 9));
        assertThat(v.auditoria().resumo().segundosLancados()).isEqualTo(-1_800);
        assertThat(v.conferencia().vigente()).isNull();
        assertThat(v.conferencia().terminou()).isFalse();
        assertThat(v.avisos()).isEmpty();
        assertThat(v.excluidos()).isEmpty();
        assertThat(v.avisosDoHorario()).extracting(a -> a.codigo()).contains("COMPENSACAO_NO_MES", "COMPENSACAO_COM_ACORDO");
    }

    @Test
    @DisplayName("Dia excluído continua no extrato (quem excluiu e o que tinha), até ter um registro de novo")
    void diaExcluido() {
        var excluir = new ExcluirRegistroUseCase(c.registros, c.arquivos, c.ajustes, c.trava, c.eventos::add, c.clock);
        excluir.executar(USUARIO, LocalDate.of(2026, 10, 10), "eduardo");

        assertThat(c.banco.mes(USUARIO, OUTUBRO).excluidos()).singleElement().satisfies(a -> {
            assertThat(a.data()).isEqualTo(LocalDate.of(2026, 10, 10));
            assertThat(a.usuario()).isEqualTo("eduardo");
            assertThat(a.antes()).containsExactly(LocalTime.of(8, 0), LocalTime.of(12, 0));
        });
        c.dia(LocalDate.of(2026, 10, 10), "09:00", "11:00");
        assertThat(c.banco.mes(USUARIO, OUTUBRO).excluidos()).isEmpty();
    }
}
