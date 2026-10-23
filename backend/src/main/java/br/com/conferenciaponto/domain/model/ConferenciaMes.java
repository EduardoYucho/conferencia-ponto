package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.exception.ConflitoException;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * O mês de uma pessoa conferido por alguém, com os números da hora (para saber se algo mudou depois). Enquanto
 * vale (não foi reaberto), o mês fica travado para mudanças à mão: batidas, lançamentos, folgas e horário.
 *
 * @param saldoMesSegundos   o saldo do mês na hora da conferência (com os lançamentos e a diferença da carga)
 * @param trabalhadoSegundos as horas trabalhadas nos dias fechados
 */
public record ConferenciaMes(UUID id, UUID usuarioId, YearMonth mes, Instant conferidoEm, String conferidoPor,
                             String observacao, int saldoMesSegundos, int trabalhadoSegundos, int diasRegistrados,
                             Instant reabertoEm, String reabertoPor, String motivoReabertura) {

    public static final int TAMANHO_TEXTO = 300;

    public ConferenciaMes {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(mes, "mes");
        Objects.requireNonNull(conferidoEm, "conferidoEm");
        Objects.requireNonNull(conferidoPor, "conferidoPor");
        observacao = texto(observacao);
        motivoReabertura = texto(motivoReabertura);
        if ((reabertoEm == null) != (reabertoPor == null)) {
            throw new IllegalArgumentException("Reabertura exige quem e quando");
        }
    }

    public static ConferenciaMes nova(UUID usuarioId, YearMonth mes, String quem, String observacao, SaldoMensal numeros,
                                      Instant agora) {
        return new ConferenciaMes(UUID.randomUUID(), usuarioId, mes, agora, quem, observacao,
                numeros.saldoMensalSegundos(), numeros.segundosTrabalhados(), numeros.diasRegistrados(), null, null, null);
    }

    /** Ainda trava o mês (não foi reaberta). */
    public boolean vale() {
        return reabertoEm == null;
    }

    public ConferenciaMes reaberta(String quem, String motivo, Instant agora) {
        if (!vale()) {
            throw new ConflitoException("CONFERENCIA_JA_REABERTA", "Esta conferência já foi reaberta.");
        }
        return new ConferenciaMes(id, usuarioId, mes, conferidoEm, conferidoPor, observacao, saldoMesSegundos,
                trabalhadoSegundos, diasRegistrados, agora, Objects.requireNonNull(quem, "quem"), motivo);
    }

    /** Os números de agora são diferentes dos da conferência (um PDF chegou, um feriado foi cadastrado...). */
    public boolean mudouDesde(SaldoMensal agora) {
        return agora.saldoMensalSegundos() != saldoMesSegundos || agora.segundosTrabalhados() != trabalhadoSegundos
                || agora.diasRegistrados() != diasRegistrados;
    }

    private static String texto(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        String limpo = valor.strip();
        return limpo.length() > TAMANHO_TEXTO ? limpo.substring(0, TAMANHO_TEXTO) : limpo;
    }
}
