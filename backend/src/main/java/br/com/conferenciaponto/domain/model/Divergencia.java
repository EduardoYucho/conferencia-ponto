package br.com.conferenciaponto.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Diferença entre a conferência e o relatório do RH num dia. Há no máximo uma por data: uma nova
 * conferência atualiza a existente. Guarda a "fotografia" dos dois lados no momento em que foi
 * detectada; uma decisão ("manter dados locais") vale enquanto a fotografia não mudar.
 */
public record Divergencia(UUID id, LocalDate data, UUID relatorioId, TipoDivergencia tipo, String descricao,
                          boolean aceitavel, String motivoNaoAceitavel, List<LocalTime> horariosRh,
                          List<LocalTime> horariosLocal, String ocorrenciaRh, TipoDia tipoDiaLocal,
                          Integer saldoRhSegundos, Integer saldoLocalSegundos, StatusDivergencia status,
                          Instant detectadaEm, Instant resolvidaEm, String resolvidaPor, String observacao) {

    public Divergencia {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(relatorioId, "relatorioId");
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(descricao, "descricao");
        horariosRh = List.copyOf(horariosRh);
        horariosLocal = List.copyOf(horariosLocal);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detectadaEm, "detectadaEm");
    }

    /** Resultado da comparação de um dia (sem identidade nem decisão). */
    public record Achado(TipoDivergencia tipo, String descricao, boolean aceitavel, String motivoNaoAceitavel,
                         List<LocalTime> horariosRh, List<LocalTime> horariosLocal, String ocorrenciaRh,
                         TipoDia tipoDiaLocal, Integer saldoRhSegundos, Integer saldoLocalSegundos) {

        public Achado {
            horariosRh = List.copyOf(horariosRh);
            horariosLocal = List.copyOf(horariosLocal);
        }
    }

    public static Divergencia nova(LocalDate data, UUID relatorioId, Achado a, Instant agora) {
        return new Divergencia(UUID.randomUUID(), data, relatorioId, a.tipo(), a.descricao(), a.aceitavel(),
                a.motivoNaoAceitavel(), a.horariosRh(), a.horariosLocal(), a.ocorrenciaRh(), a.tipoDiaLocal(),
                a.saldoRhSegundos(), a.saldoLocalSegundos(), StatusDivergencia.PENDENTE, agora, null, null, null);
    }

    public boolean isPendente() {
        return status == StatusDivergencia.PENDENTE;
    }

    /** Mesma situação (os dois lados iguais aos da fotografia): a decisão tomada continua valendo. */
    public boolean mesmaSituacao(Achado a) {
        return tipo == a.tipo() && horariosRh.equals(a.horariosRh()) && horariosLocal.equals(a.horariosLocal())
                && Objects.equals(ocorrenciaRh, a.ocorrenciaRh()) && tipoDiaLocal == a.tipoDiaLocal()
                && Objects.equals(saldoRhSegundos, a.saldoRhSegundos())
                && Objects.equals(saldoLocalSegundos, a.saldoLocalSegundos());
    }

    /**
     * A diferença continua (ou reapareceu). "Manter dados locais" vale enquanto a situação for a mesma;
     * qualquer outra decisão anterior (aceito, resolvida) volta a ficar pendente — o dia mudou depois dela.
     */
    public Divergencia atualizada(UUID relatorio, Achado a, Instant agora) {
        boolean manterDecisao = status == StatusDivergencia.PENDENTE
                || (status == StatusDivergencia.MANTIDO_LOCAL && mesmaSituacao(a));
        boolean mudou = !manterDecisao || !mesmaSituacao(a);
        StatusDivergencia novoStatus = manterDecisao ? status : StatusDivergencia.PENDENTE;
        return new Divergencia(id, data, relatorio, a.tipo(), a.descricao(), a.aceitavel(), a.motivoNaoAceitavel(),
                a.horariosRh(), a.horariosLocal(), a.ocorrenciaRh(), a.tipoDiaLocal(), a.saldoRhSegundos(),
                a.saldoLocalSegundos(), novoStatus, mudou ? agora : detectadaEm, mudou ? null : resolvidaEm,
                mudou ? null : resolvidaPor, mudou ? null : observacao);
    }

    public Divergencia resolvida(StatusDivergencia novoStatus, String usuario, String obs, Instant agora) {
        return new Divergencia(id, data, relatorioId, tipo, descricao, aceitavel, motivoNaoAceitavel, horariosRh,
                horariosLocal, ocorrenciaRh, tipoDiaLocal, saldoRhSegundos, saldoLocalSegundos, novoStatus, detectadaEm,
                agora, usuario, obs == null || obs.isBlank() ? null : obs.strip());
    }
}
