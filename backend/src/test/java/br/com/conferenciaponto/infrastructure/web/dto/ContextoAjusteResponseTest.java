package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.usecase.AjustarBatidasUseCase;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.TipoBatida;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextoAjusteResponseTest {

    private static final AjustarBatidasUseCase.Contexto CONTEXTO =
            new AjustarBatidasUseCase.Contexto(List.of(LocalTime.of(8, 1, 12)), List.of());

    @Test
    @DisplayName("Os nomes das batidas seguem o horário do dia: um nome para cada batida que o dia comporta")
    void nomesPeloHorarioDoDia() {
        ContextoAjusteResponse resposta = ContextoAjusteResponse.de(CONTEXTO, GradeHoraria.PADRAO);

        assertThat(resposta.comprovadas()).containsExactly(LocalTime.of(8, 1, 12));
        assertThat(resposta.historico()).isEmpty();
        assertThat(resposta.nomes()).hasSize(TipoBatida.MAXIMO)
                .startsWith("Entrada", "Saída p/ almoço", "Volta do almoço", "Saída");
    }

    @Test
    @DisplayName("Intervalo fora do meio-dia é \"intervalo\"; horário de um período só não tem saída para o almoço")
    void outrosHorarios() {
        assertThat(ContextoAjusteResponse.de(CONTEXTO, GradeHoraria.ler("06:00-09:00 09:30-14:00")).nomes())
                .startsWith("Entrada", "Saída p/ intervalo", "Volta do intervalo", "Saída");
        assertThat(ContextoAjusteResponse.de(CONTEXTO, GradeHoraria.ler("07:00-13:00")).nomes())
                .startsWith("Entrada", "Saída", "Volta", "Saída");
    }

    @Test
    @DisplayName("Dia sem horário previsto (sem expediente, feriado, folga): nomes simples")
    void semHorarioPrevisto() {
        assertThat(ContextoAjusteResponse.de(CONTEXTO, null).nomes())
                .hasSize(TipoBatida.MAXIMO)
                .startsWith("Entrada", "Saída", "Volta", "Saída");
    }
}
