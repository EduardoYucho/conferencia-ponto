package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.Intervalo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public record RegistroManualRequest(
        @NotNull(message = "Informe a data do lançamento.")
        LocalDate data,

        @NotEmpty(message = "Informe ao menos um intervalo.")
        @Size(max = 3, message = "Informe no máximo 3 intervalos.")
        List<@Valid @NotNull(message = "Há um intervalo sem horários.") IntervaloRequest> intervalos) {

    public record IntervaloRequest(
            @NotNull(message = "Informe o horário de entrada.") LocalTime entrada,
            @NotNull(message = "Informe o horário de saída.") LocalTime saida) {

        public Intervalo paraDominio() {
            return new Intervalo(entrada, saida);
        }
    }

    public List<Intervalo> intervalosDominio() {
        return intervalos.stream().map(IntervaloRequest::paraDominio).toList();
    }
}
