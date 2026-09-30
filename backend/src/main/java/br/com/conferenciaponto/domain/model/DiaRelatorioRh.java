package br.com.conferenciaponto.domain.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Uma linha do relatório de banco de horas do RH.
 *
 * @param horarios                 batidas na ordem do relatório (podem ser ímpares ou mais de 6)
 * @param ocorrencia               texto no lugar das batidas ("Feriado", "Férias"...), sem o dia da semana
 * @param jornadaPrevistaSegundos  "Hr. Trabalho"
 * @param segundosTrabalhados      "Hr. Trabalhadas"
 * @param saldoSegundos            "Hr. Extra/Falta"
 */
public record DiaRelatorioRh(LocalDate data, List<LocalTime> horarios, String ocorrencia, int jornadaPrevistaSegundos,
                             int segundosTrabalhados, int saldoSegundos) {

    public DiaRelatorioRh {
        Objects.requireNonNull(data, "data");
        horarios = List.copyOf(horarios);
        ocorrencia = ocorrencia == null || ocorrencia.isBlank() ? null : ocorrencia.strip();
    }

    public Optional<OcorrenciaRh> tipoOcorrencia() {
        return OcorrenciaRh.de(ocorrencia);
    }
}
