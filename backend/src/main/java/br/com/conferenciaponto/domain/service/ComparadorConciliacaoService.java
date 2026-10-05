package br.com.conferenciaponto.domain.service;

import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.Divergencia.Achado;
import br.com.conferenciaponto.domain.model.OcorrenciaRh;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Compara um dia da conferência com o mesmo dia no relatório do RH (serviço de domínio puro).
 *
 * <p>Regras:
 * <ol>
 *   <li><b>Tipo do dia</b> primeiro: RH com feriado/férias/folga num dia que aqui é útil, ou RH esperando
 *       jornada num dia que aqui é feriado/ausência.</li>
 *   <li><b>Existência</b>: batidas só no RH (dias anteriores ao uso da conferência, batida esquecida
 *       corrigida pelo RH) ou só na conferência.</li>
 *   <li><b>Batidas</b>: quantidade diferente (faltando/sobrando) ou horário diferente em mais de 1 minuto.</li>
 *   <li><b>Segundos</b>: o comprovante em PDF costuma marcar 1 s depois do RH. Batidas a até 1 minuto são
 *       consideradas a mesma batida; só viram divergência se mudarem o saldo.</li>
 *   <li><b>Saldo</b>: mesmas batidas e saldo diferente (diferença de regra de cálculo).</li>
 * </ol>
 */
public final class ComparadorConciliacaoService {

    /** Diferença máxima para duas batidas serem a mesma (PDF x relatório do RH). */
    public static final long MESMA_BATIDA_SEGUNDOS = 60;

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * @param local           registro da conferência (vazio se não houver)
     * @param tipoDiaLocal    classificação atual da data na conferência (feriados/ausências cadastrados)
     * @return a divergência encontrada, ou vazio se o dia bate com o RH
     */
    public Optional<Achado> comparar(DiaRelatorioRh rh, Optional<RegistroJornada> local, TipoDia tipoDiaLocal) {
        Objects.requireNonNull(rh, "rh");
        TipoDia tipoLocal = local.map(RegistroJornada::getTipoDia).orElse(tipoDiaLocal);
        List<LocalTime> r = rh.horarios();
        List<LocalTime> l = local.map(reg -> reg.getBatidas().horarios()).orElse(List.of());
        Integer saldoLocal = local.map(RegistroJornada::getSaldoDiarioSegundos).orElse(null);
        Fabrica f = new Fabrica(rh, l, tipoLocal, saldoLocal);

        Optional<Achado> tipoDia = compararTipoDia(rh, tipoLocal, f);
        if (tipoDia.isPresent()) {
            return tipoDia;
        }

        if (l.isEmpty()) {
            if (!r.isEmpty()) {
                return Optional.of(f.achado(TipoDivergencia.SOMENTE_RH, "O RH tem %d batida(s) (%s) e a conferência não tem registro. Saldo no RH: %s."
                        .formatted(r.size(), texto(r), saldo(rh.saldoSegundos())), motivoRhInvalido(r)));
            }
            if (rh.saldoSegundos() != 0) {
                return Optional.of(f.achado(TipoDivergencia.SOMENTE_RH, "O RH lançou %s sem batidas neste dia e a conferência não tem registro."
                                .formatted(saldo(rh.saldoSegundos())),
                        "Não há batidas para trazer do RH: confira o motivo com o RH."));
            }
            return Optional.empty();
        }
        if (r.isEmpty()) {
            return Optional.of(f.achado(TipoDivergencia.SOMENTE_LOCAL, "A conferência tem %d batida(s) (%s) e o RH não tem nenhuma (saldo no RH: %s)."
                            .formatted(l.size(), texto(l), saldo(rh.saldoSegundos())),
                    "Aceitar o RH apagaria o dia: se for o caso, exclua o dia na conferência."));
        }

        if (r.size() != l.size() && semPar(r, l).isEmpty() && semPar(l, r).isEmpty()) {
            // mesmas batidas, uma delas repetida em menos de 1 minuto de um dos lados (clique duplo)
            if (Objects.equals(saldoLocal, rh.saldoSegundos())) {
                return Optional.empty();
            }
            return Optional.of(f.achado(TipoDivergencia.SALDO, "Mesmas batidas, com repetição a menos de 1 minuto (RH %s × conferência %s); saldo RH %s × conferência %s."
                            .formatted(texto(r), texto(l), saldo(rh.saldoSegundos()),
                                    saldoLocal == null ? "incompleto" : saldo(saldoLocal)),
                    "A diferença vem de uma batida repetida: use \"Manter dados locais\" ou o ajuste manual."));
        }
        if (r.size() > l.size()) {
            List<LocalTime> faltando = semPar(r, l);
            return Optional.of(f.achado(TipoDivergencia.BATIDA_FALTANDO, "Falta na conferência: %s (RH com %d batidas, conferência com %d)."
                    .formatted(texto(faltando), r.size(), l.size()), motivoRhInvalido(r)));
        }
        if (r.size() < l.size()) {
            List<LocalTime> sobrando = semPar(l, r);
            return Optional.of(f.achado(TipoDivergencia.BATIDA_SOBRANDO, "A conferência tem batida que o RH não tem: %s (RH com %d, conferência com %d)."
                    .formatted(texto(sobrando), r.size(), l.size()), motivoRhInvalido(r)));
        }

        long maiorDiferenca = 0;
        List<String> diferentes = new ArrayList<>();
        for (int i = 0; i < r.size(); i++) {
            long d = Math.abs(ChronoUnit.SECONDS.between(r.get(i), l.get(i)));
            maiorDiferenca = Math.max(maiorDiferenca, d);
            if (d > MESMA_BATIDA_SEGUNDOS) {
                diferentes.add("%s: RH %s × conferência %s".formatted(TipoBatida.daPosicao(i).rotulo(),
                        HORA.format(r.get(i)), HORA.format(l.get(i))));
            }
        }
        if (!diferentes.isEmpty()) {
            return Optional.of(f.achado(TipoDivergencia.HORARIO_DIFERENTE, String.join("; ", diferentes) + ".",
                    motivoRhInvalido(r)));
        }
        if (Objects.equals(saldoLocal, rh.saldoSegundos())) {
            return Optional.empty();
        }
        String saldos = "saldo RH %s × conferência %s".formatted(saldo(rh.saldoSegundos()),
                saldoLocal == null ? "incompleto" : saldo(saldoLocal));
        if (maiorDiferenca == 0) {
            return Optional.of(f.achado(TipoDivergencia.SALDO, "Mesmas batidas, mas %s (diferença na regra de cálculo)."
                    .formatted(saldos), "As batidas já são iguais às do RH: não há o que trazer."));
        }
        return Optional.of(f.achado(TipoDivergencia.DIFERENCA_SEGUNDOS, "Batidas iguais com até %d s de diferença, que mudam o saldo: %s."
                .formatted(maiorDiferenca, saldos), motivoRhInvalido(r)));
    }

    private Optional<Achado> compararTipoDia(DiaRelatorioRh rh, TipoDia tipoLocal, Fabrica f) {
        Optional<OcorrenciaRh> ocorrencia = rh.tipoOcorrencia();
        if (ocorrencia.isPresent() && ocorrencia.get() != OcorrenciaRh.OUTRA) {
            OcorrenciaRh oc = ocorrencia.get();
            boolean localDiferente = oc == OcorrenciaRh.FERIADO
                    ? tipoLocal == TipoDia.UTIL || tipoLocal == TipoDia.AUSENCIA
                    : tipoLocal == TipoDia.UTIL;
            if (localDiferente) {
                return Optional.of(f.achado(TipoDivergencia.TIPO_DIA, "O RH marcou \"%s\" e na conferência o dia é %s."
                        .formatted(rh.ocorrencia(), rotulo(tipoLocal)), null));
            }
            return Optional.empty();
        }
        if (rh.jornadaPrevistaSegundos() > 0 && (tipoLocal == TipoDia.FERIADO || tipoLocal == TipoDia.AUSENCIA)) {
            return Optional.of(f.achado(TipoDivergencia.TIPO_DIA, "O RH conta este dia como útil (jornada de %s) e na conferência ele é %s."
                            .formatted(duracao(rh.jornadaPrevistaSegundos()), rotulo(tipoLocal)),
                    "Remova o feriado ou a ausência deste dia na conferência (tela Ausências)."));
        }
        return Optional.empty();
    }

    /** Batidas de {@code origem} sem nenhuma em {@code outra} a até 1 minuto. */
    private static List<LocalTime> semPar(List<LocalTime> origem, List<LocalTime> outra) {
        return origem.stream()
                .filter(h -> outra.stream().noneMatch(o -> Math.abs(ChronoUnit.SECONDS.between(h, o)) <= MESMA_BATIDA_SEGUNDOS))
                .toList();
    }

    /** Motivo pelo qual as batidas do RH não podem ser copiadas como estão (ou null se podem). */
    static String motivoRhInvalido(List<LocalTime> r) {
        if (r.size() > TipoBatida.MAXIMO) {
            return "O RH tem %d batidas e a conferência comporta %d: use o ajuste manual.".formatted(r.size(), TipoBatida.MAXIMO);
        }
        if (r.size() % 2 == 1) {
            return "O RH tem número ímpar de batidas (%d): copiar deixaria o dia incompleto. Use o ajuste manual."
                    .formatted(r.size());
        }
        for (int i = 1; i < r.size(); i++) {
            if (!r.get(i).isAfter(r.get(i - 1))) {
                return "O RH tem batidas repetidas ou fora de ordem (%s): use o ajuste manual.".formatted(HORA.format(r.get(i)));
            }
        }
        return null;
    }

    private static String rotulo(TipoDia tipo) {
        return switch (tipo) {
            case UTIL -> "dia útil";
            case FIM_DE_SEMANA -> "fim de semana";
            case FERIADO -> "feriado";
            case AUSENCIA -> "ausência (férias/folga/atestado)";
        };
    }

    static String texto(List<LocalTime> horarios) {
        return horarios.stream().map(HORA::format).collect(Collectors.joining(" "));
    }

    static String saldo(int segundos) {
        return (segundos < 0 ? "-" : "+") + duracao(Math.abs(segundos));
    }

    static String duracao(int segundos) {
        int s = Math.abs(segundos);
        return "%02d:%02d:%02d".formatted(s / 3600, (s % 3600) / 60, s % 60);
    }

    private record Fabrica(DiaRelatorioRh rh, List<LocalTime> local, TipoDia tipoLocal, Integer saldoLocal) {
        Achado achado(TipoDivergencia tipo, String descricao, String motivoNaoAceitavel) {
            return new Achado(tipo, descricao, motivoNaoAceitavel == null, motivoNaoAceitavel, rh.horarios(), local,
                    rh.ocorrencia(), tipoLocal, rh.saldoSegundos(), saldoLocal);
        }
    }
}
