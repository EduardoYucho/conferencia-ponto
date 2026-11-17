package br.com.conferenciaponto.infrastructure.web.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * @param ultimoDia          último dia incluído no saldo final (opcional: sem ele vale a sugestão do sistema)
 * @param mesmoComPendencias quem fecha viu as pendências do período (dias sem saída, sem registro, diferenças do RH)
 *                           e quer fechar assim mesmo
 */
public record FecharCicloRequest(
        LocalDate ultimoDia,
        @Size(max = 200, message = "A observação pode ter no máximo 200 caracteres.") String observacao,
        Boolean mesmoComPendencias) {

    public FecharCicloRequest(LocalDate ultimoDia, String observacao) {
        this(ultimoDia, observacao, null);
    }
}
