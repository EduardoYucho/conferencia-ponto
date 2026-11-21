package br.com.expedio.application;

import br.com.expedio.application.PendenciasDoPeriodo.Pendencias;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** O texto das pendências: uma frase por tipo, no plural certo, com até 5 datas. */
class PendenciasDoPeriodoTest {

    private static LocalDate dia(int d) {
        return LocalDate.of(2026, 9, d);
    }

    @Test
    void semNadaNaoHaPendencia() {
        assertThat(Pendencias.NENHUMA.vazia()).isTrue();
        assertThat(Pendencias.NENHUMA.itens()).isEmpty();
        assertThat(Pendencias.NENHUMA.resumo()).isEmpty();
        assertThat(PendenciasDoPeriodo.NENHUMA.de(UUID.randomUUID(), dia(1), dia(30)).vazia()).isTrue();
    }

    @Test
    void pluralEDatas() {
        Pendencias p = new Pendencias(List.of(dia(3), dia(14)), List.of(dia(9)), List.of(dia(1), dia(2), dia(4)));

        assertThat(p.vazia()).isFalse();
        assertThat(p.itens()).containsExactly(
                "2 dias com entrada sem saída (03/09 e 14/09): fora do saldo",
                "1 dia útil sem nenhum registro (09/09): fora do saldo",
                "3 diferenças com o relatório do RH sem decisão (01/09, 02/09 e 04/09)");
        assertThat(p.resumo()).isEqualTo("2 dias com entrada sem saída (03/09 e 14/09): fora do saldo; "
                + "1 dia útil sem nenhum registro (09/09): fora do saldo e "
                + "3 diferenças com o relatório do RH sem decisão (01/09, 02/09 e 04/09)");
    }

    @Test
    void muitasDatasViramEMais() {
        List<LocalDate> doze = IntStream.rangeClosed(1, 12).mapToObj(PendenciasDoPeriodoTest::dia).toList();

        assertThat(new Pendencias(List.of(), doze, List.of()).resumo())
                .isEqualTo("12 dias úteis sem nenhum registro (01/09, 02/09, 03/09, 04/09, 05/09 e mais 7): fora do saldo");
    }
}
