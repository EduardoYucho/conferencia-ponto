package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Ciclo do banco de horas (semestral). Enquanto ABERTO, o saldo é a soma dos dias a partir do início
 * (a {@code dataFim} é só a previsão); ao ser FECHADO, o saldo exato fica congelado e o próximo ciclo
 * começa do zero no dia seguinte.
 */
public record CicloBanco(UUID id, UUID usuarioId, LocalDate dataInicio, LocalDate dataFim, LocalDate dataFimPrevista,
                         StatusCiclo status, Integer saldoFinalSegundos, Instant fechadoEm, String fechadoPor,
                         String observacao, Instant criadoEm) {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public CicloBanco {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(criadoEm, "criadoEm");
        if (dataInicio == null || dataFim == null || dataFimPrevista == null) {
            throw new RegraNegocioException("CICLO_SEM_DATAS", "Informe o início e a previsão de término do ciclo.");
        }
        if (dataFimPrevista.isBefore(dataInicio)) {
            throw new RegraNegocioException("CICLO_DATAS_INVERTIDAS",
                    "A previsão de término do ciclo deve ser posterior ao início.");
        }
        if (dataFim.isBefore(dataInicio)) {
            throw new RegraNegocioException("CICLO_FECHAMENTO_ANTES_DO_INICIO",
                    "O ciclo começou em %s: o fechamento não pode ser antes disso.".formatted(DATA.format(dataInicio)));
        }
        if ((status == StatusCiclo.FECHADO) != (saldoFinalSegundos != null && fechadoEm != null)) {
            throw new IllegalArgumentException("Ciclo fechado exige saldo final e data do fechamento (e só ele)");
        }
        observacao = observacao == null || observacao.isBlank() ? null : observacao.strip();
    }

    /** Abre um ciclo com a duração informada (ex.: 25/05/2026 + 6 meses = até 24/11/2026). */
    public static CicloBanco abrir(UUID usuarioId, LocalDate inicio, int duracaoMeses, Instant agora) {
        if (duracaoMeses < 1 || duracaoMeses > 24) {
            throw new IllegalArgumentException("Duração do ciclo deve estar entre 1 e 24 meses");
        }
        LocalDate previsao = inicio.plusMonths(duracaoMeses).minusDays(1);
        return new CicloBanco(UUID.randomUUID(), usuarioId, inicio, previsao, previsao, StatusCiclo.ABERTO, null, null, null,
                null, agora);
    }

    public boolean isAberto() {
        return status == StatusCiclo.ABERTO;
    }

    /**
     * Fecha o ciclo com o último dia incluído e o saldo exato apurado até ele.
     *
     * @param ultimoDia último dia que entra no saldo (entre o início do ciclo e hoje)
     */
    public CicloBanco fechar(LocalDate ultimoDia, int saldoSegundos, String usuario, String observacao,
                             LocalDate hoje, Instant agora) {
        if (!isAberto()) {
            throw new ConflitoException("CICLO_JA_FECHADO", "Este ciclo já foi fechado.");
        }
        Objects.requireNonNull(ultimoDia, "ultimoDia");
        if (ultimoDia.isAfter(hoje)) {
            throw new RegraNegocioException("CICLO_FECHAMENTO_FUTURO",
                    "O fechamento não pode ser depois de hoje (%s).".formatted(DATA.format(hoje)));
        }
        return new CicloBanco(id, usuarioId, dataInicio, ultimoDia, dataFimPrevista, StatusCiclo.FECHADO, saldoSegundos, agora,
                usuario, observacao, criadoEm);
    }

    /** Desfaz o fechamento: volta a ser o ciclo aberto, com a previsão original. */
    public CicloBanco reaberto() {
        if (isAberto()) {
            throw new ConflitoException("CICLO_JA_ABERTO", "Este ciclo já está aberto.");
        }
        return new CicloBanco(id, usuarioId, dataInicio, dataFimPrevista, dataFimPrevista, StatusCiclo.ABERTO, null, null, null,
                observacao, criadoEm);
    }

    /** Corrige o início e/ou a previsão de término do ciclo aberto. */
    public CicloBanco comPeriodo(LocalDate inicio, LocalDate fimPrevisto) {
        if (!isAberto()) {
            throw new ConflitoException("CICLO_JA_FECHADO", "Só o ciclo aberto pode ter o período alterado.");
        }
        return new CicloBanco(id, usuarioId, inicio, fimPrevisto, fimPrevisto, status, null, null, null, observacao, criadoEm);
    }

    /** Dias até a previsão de término (0 = termina hoje; negativo = já passou). */
    public long diasAtePrevisao(LocalDate hoje) {
        return ChronoUnit.DAYS.between(hoje, dataFimPrevista);
    }

    /** Último dia considerado no saldo: a data de fechamento ou, no ciclo aberto, sem limite. */
    public LocalDate ultimoDiaDoSaldo() {
        return isAberto() ? LocalDate.of(9999, 12, 31) : dataFim;
    }
}
