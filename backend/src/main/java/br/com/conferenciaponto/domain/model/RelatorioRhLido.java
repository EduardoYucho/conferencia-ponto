package br.com.conferenciaponto.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Conteúdo extraído do PDF do relatório de banco de horas do RH (sem CPF). */
public record RelatorioRhLido(String funcionario, LocalDateTime emitidoEm, LocalDate periodoInicio, LocalDate periodoFim,
                              Integer totalPrevistoSegundos, Integer totalTrabalhadoSegundos, Integer totalSaldoSegundos,
                              List<DiaRelatorioRh> dias) {

    public RelatorioRhLido {
        dias = List.copyOf(dias);
    }
}
