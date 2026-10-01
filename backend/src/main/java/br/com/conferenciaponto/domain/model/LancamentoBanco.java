package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Lançamento avulso no banco de horas: débito (segundos negativos) ou crédito numa data, com justificativa.
 * Ex.: compensar as horas a mais com uma folga ou uma saída antecipada, horas pagas pela empresa,
 * correção feita pelo RH. Não altera a jornada do dia: soma no saldo do mês e do ciclo.
 */
public record LancamentoBanco(UUID id, LocalDate data, int segundos, String descricao, Instant criadoEm,
                              String criadoPor) {

    /** 300 horas para mais ou para menos. */
    public static final int LIMITE_SEGUNDOS = 300 * 3600;
    public static final int TAMANHO_DESCRICAO = 200;

    public LancamentoBanco {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(criadoEm, "criadoEm");
        if (data == null) {
            throw new RegraNegocioException("LANCAMENTO_SEM_DATA", "Informe a data do lançamento.");
        }
        if (segundos == 0) {
            throw new RegraNegocioException("LANCAMENTO_ZERADO", "Informe a quantidade de horas do lançamento.");
        }
        if (Math.abs(segundos) > LIMITE_SEGUNDOS) {
            throw new RegraNegocioException("LANCAMENTO_GRANDE_DEMAIS", "Um lançamento pode ter no máximo 300 horas.");
        }
        descricao = descricao == null ? "" : descricao.strip();
        if (descricao.isEmpty()) {
            throw new RegraNegocioException("LANCAMENTO_SEM_JUSTIFICATIVA",
                    "Informe o motivo do lançamento (ex.: compensação de horas).");
        }
        if (descricao.length() > TAMANHO_DESCRICAO) {
            descricao = descricao.substring(0, TAMANHO_DESCRICAO);
        }
    }

    public static LancamentoBanco novo(LocalDate data, int segundos, String descricao, String usuario, Instant agora) {
        return new LancamentoBanco(UUID.randomUUID(), data, segundos, descricao, agora, usuario);
    }

    public boolean isDebito() {
        return segundos < 0;
    }
}
