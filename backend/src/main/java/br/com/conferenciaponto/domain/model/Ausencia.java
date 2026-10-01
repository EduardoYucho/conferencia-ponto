package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Período de férias, atestado, licença ou folga: os dias úteis do período têm jornada base zero
 * (não geram débito) e somem das pendências do painel.
 */
public record Ausencia(UUID id, UUID usuarioId, LocalDate dataInicio, LocalDate dataFim, TipoAusencia tipo, String descricao,
                       Instant criadoEm, String criadoPor) {

    public static final int DURACAO_MAXIMA_DIAS = 366;

    public Ausencia {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(criadoEm, "criadoEm");
        if (dataInicio == null || dataFim == null) {
            throw new RegraNegocioException("AUSENCIA_SEM_DATAS", "Informe o início e o fim da ausência.");
        }
        if (dataFim.isBefore(dataInicio)) {
            throw new RegraNegocioException("AUSENCIA_DATAS_INVERTIDAS", "O fim da ausência deve ser igual ou posterior ao início.");
        }
        if (ChronoUnit.DAYS.between(dataInicio, dataFim) >= DURACAO_MAXIMA_DIAS) {
            throw new RegraNegocioException("AUSENCIA_LONGA_DEMAIS", "Uma ausência pode ter no máximo 1 ano.");
        }
        descricao = descricao == null || descricao.isBlank() ? null : descricao.strip();
        if (tipo == TipoAusencia.ABONO && descricao == null) {
            throw new RegraNegocioException("AUSENCIA_ABONO_SEM_JUSTIFICATIVA",
                    "Informe a justificativa do abono (ex.: doação de sangue).");
        }
    }

    public static Ausencia nova(UUID usuarioId, LocalDate inicio, LocalDate fim, TipoAusencia tipo, String descricao, String usuario,
                                Instant agora) {
        return new Ausencia(UUID.randomUUID(), usuarioId, inicio, fim, tipo, descricao, agora, usuario);
    }

    public boolean contem(LocalDate data) {
        return !data.isBefore(dataInicio) && !data.isAfter(dataFim);
    }

    public boolean sobrepoe(LocalDate inicio, LocalDate fim) {
        return !dataInicio.isAfter(fim) && !dataFim.isBefore(inicio);
    }

    /** Mesmo tipo e encostada no dia informado (para juntar dias vindos do RH num período só). */
    public boolean encostaEm(LocalDate data, TipoAusencia outroTipo) {
        return tipo == outroTipo && (dataFim.plusDays(1).equals(data) || dataInicio.minusDays(1).equals(data));
    }

    public Ausencia estendidaAte(LocalDate data) {
        return new Ausencia(id, usuarioId, data.isBefore(dataInicio) ? data : dataInicio,
                data.isAfter(dataFim) ? data : dataFim, tipo, descricao, criadoEm, criadoPor);
    }

    public String rotulo() {
        return descricao == null ? tipo.rotulo() : tipo.rotulo() + " · " + descricao;
    }
}
