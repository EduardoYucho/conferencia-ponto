package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.LancamentoBanco;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** @param segundos negativo = abatido do banco; positivo = creditado */
public record LancamentoBancoResponse(UUID id, LocalDate data, int segundos, String descricao, Instant criadoEm,
                                      String criadoPor) {

    public static LancamentoBancoResponse de(LancamentoBanco l) {
        return new LancamentoBancoResponse(l.id(), l.data(), l.segundos(), l.descricao(), l.criadoEm(), l.criadoPor());
    }
}
