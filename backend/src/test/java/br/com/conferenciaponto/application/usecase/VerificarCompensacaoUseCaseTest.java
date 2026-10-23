package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.Notificacao;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;
import static org.assertj.core.api.Assertions.assertThat;

/** Os avisos do sino: faltam horas da carga perto do fim do mês, o mês terminou faltando e a semana sem folga. */
class VerificarCompensacaoUseCaseTest {

    private final CenarioDaCarga c = new CenarioDaCarga();

    private void diasUteisDeOutubroAte(int ultimo) {
        for (int d = 1; d <= ultimo; d++) {
            LocalDate data = LocalDate.of(2026, 10, d);
            if (data.getDayOfWeek().getValue() <= 5 && d != 12) {
                c.diaCheio(data);
            }
        }
    }

    @Test
    @DisplayName("Faltando horas e com 2 fins de semana no mês, um aviso por faixa (não repete)")
    void faltamHoras() {
        diasUteisDeOutubroAte(20);

        List<Notificacao> criadas = c.verificar.executar(USUARIO, LocalDate.of(2026, 10, 21));

        assertThat(criadas).singleElement().satisfies(n -> {
            assertThat(n.tipo()).isEqualTo(TipoNotificacao.COMPENSACAO);
            assertThat(n.titulo()).isEqualTo("Faltam 8h24 para fechar a carga de outubro");
            assertThat(n.mensagem()).startsWith("Restam 2 fins de semana no mês (24/10 a 25/10 e 31/10).");
            assertThat(n.link()).isEqualTo("/banco-de-horas");
        });
        assertThat(c.verificar.executar(USUARIO, LocalDate.of(2026, 10, 21))).isEmpty();

        c.em(LocalDate.of(2026, 10, 27), LocalTime.of(9, 0));
        assertThat(c.verificar.executar(USUARIO, LocalDate.of(2026, 10, 27))).extracting(Notificacao::mensagem)
                .singleElement().asString().startsWith("Resta 1 fim de semana no mês (31/10).");
    }

    @Test
    @DisplayName("Com 3 fins de semana pela frente ainda não avisa; com a carga fechada, nunca")
    void cedoOuFechado() {
        diasUteisDeOutubroAte(9);
        c.em(LocalDate.of(2026, 10, 9), LocalTime.of(18, 0));
        assertThat(c.verificar.executar(USUARIO, LocalDate.of(2026, 10, 9))).isEmpty();

        c.dia(LocalDate.of(2026, 10, 3), "07:00", "12:00", "13:00", "17:00");
        c.em(LocalDate.of(2026, 10, 21), LocalTime.of(18, 0));
        assertThat(c.verificar.executar(USUARIO, LocalDate.of(2026, 10, 21))).as("9h no sábado pagam 8h24").isEmpty();
    }

    @Test
    @DisplayName("Nos primeiros dias do mês seguinte: o mês terminou faltando (ficou como débito no banco)")
    void mesTerminouFaltando() {
        diasUteisDeOutubroAte(31);
        c.em(LocalDate.of(2026, 11, 3), LocalTime.of(9, 0));

        assertThat(c.verificar.executar(USUARIO, LocalDate.of(2026, 11, 3))).extracting(Notificacao::titulo)
                .contains("Outubro terminou faltando 8h24 da carga");
    }

    @Test
    @DisplayName("Semana inteira e o fim de semana trabalhados: 7 dias sem folga vão para o sino (uma vez)")
    void semFolga() {
        for (int d = 5; d <= 9; d++) {
            c.diaCheio(LocalDate.of(2026, 10, d));
        }
        c.dia(LocalDate.of(2026, 10, 10), "08:00", "12:00");
        c.dia(LocalDate.of(2026, 10, 11), "08:00", "10:00");

        List<Notificacao> criadas = c.verificar.folgasAgora(USUARIO, LocalDate.of(2026, 10, 11));
        assertThat(criadas).singleElement().satisfies(n -> {
            assertThat(n.tipo()).isEqualTo(TipoNotificacao.SEM_FOLGA);
            assertThat(n.mensagem()).contains("De 05/10 a 11/10").contains("CLT, art. 67");
            assertThat(n.link()).isEqualTo("/banco-de-horas?ano=2026&mes=10");
        });
        assertThat(c.verificar.folgasAgora(USUARIO, LocalDate.of(2026, 10, 12))).isEmpty();
    }
}
