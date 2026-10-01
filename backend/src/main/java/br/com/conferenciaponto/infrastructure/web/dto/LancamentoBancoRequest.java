package br.com.conferenciaponto.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Lançamento avulso no banco de horas.
 *
 * @param duracao quantidade de horas, sempre positiva: "04:00", "8:48", "40:00" ou "01:30:15"
 * @param sentido DEBITO abate do banco; CREDITO soma
 */
public record LancamentoBancoRequest(
        @NotNull(message = "Informe a data.") LocalDate data,
        @NotBlank(message = "Informe as horas (ex.: 04:00).")
        @Pattern(regexp = "^\\d{1,3}:[0-5]\\d(:[0-5]\\d)?$", message = "Use horas e minutos, ex.: 04:00 ou 8:48.")
        String duracao,
        @NotNull(message = "Informe se o lançamento abate (DEBITO) ou credita (CREDITO).") Sentido sentido,
        @NotBlank(message = "Informe o motivo (ex.: compensação de horas).")
        @Size(max = 200, message = "O motivo pode ter no máximo 200 caracteres.") String descricao) {

    public enum Sentido { DEBITO, CREDITO }

    /** Segundos com sinal: negativo abate do banco. */
    public int segundos() {
        String[] partes = duracao.split(":");
        int total = Integer.parseInt(partes[0]) * 3600 + Integer.parseInt(partes[1]) * 60
                + (partes.length > 2 ? Integer.parseInt(partes[2]) : 0);
        return sentido == Sentido.DEBITO ? -total : total;
    }
}
