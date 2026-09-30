package br.com.conferenciaponto.domain.service;

import br.com.conferenciaponto.domain.model.ApuracaoDiaria;
import br.com.conferenciaponto.domain.model.Batidas;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.MarcacaoApurada;
import br.com.conferenciaponto.domain.model.StatusJornada;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.model.TipoDia;

import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Motor de cálculo da jornada (serviço de domínio, sem dependência de framework).
 *
 * <p>Reproduz a regra do sistema de ponto do RH, validada ao segundo contra os relatórios
 * de banco de horas de nov/2025 a ago/2026:
 * <ol>
 *   <li><b>Precisão de segundos:</b> nada é arredondado; 08:05:22 é 5 min 22 s de atraso.</li>
 *   <li><b>Trabalhado:</b> soma bruta dos intervalos fechados (até 3 intervalos por dia), como a coluna
 *       "Hr. Trabalhadas". Intervalo aberto (entrada sem saída) não é somado.</li>
 *   <li><b>Tolerância (apenas dias úteis):</b> cada horário da grade (08:00 e 13:00 de entrada, 12:00 e
 *       17:48 de saída) é casado com a batida mais próxima do mesmo tipo. Se a diferença for de até
 *       5:00 (inclusive), vale o horário da grade; senão, o horário real. Batidas extras (ex.: saída
 *       às 11:01 e volta às 11:15) não têm horário de grade: o tempo fora conta integralmente.</li>
 *   <li><b>Saldo:</b> {@code trabalhado + ajustes de tolerância - jornada prevista} (31.680 s = 08:48 em
 *       dia útil; zero em fim de semana/feriado/ausência, quando 100% vira crédito). Com a jornada em
 *       andamento o saldo não é apurado ({@code null}).</li>
 * </ol>
 */
public final class MotorCalculoJornadaService {

    public static final int JORNADA_BASE_PADRAO_MINUTOS = 528;
    public static final int JORNADA_BASE_PADRAO_SEGUNDOS = JORNADA_BASE_PADRAO_MINUTOS * 60;
    public static final Duration TOLERANCIA_PADRAO = Duration.ofMinutes(5);

    private final GradeHoraria grade;
    private final Duration tolerancia;

    public MotorCalculoJornadaService() {
        this(GradeHoraria.PADRAO, TOLERANCIA_PADRAO);
    }

    public MotorCalculoJornadaService(GradeHoraria grade, Duration tolerancia) {
        this.grade = Objects.requireNonNull(grade, "grade");
        Objects.requireNonNull(tolerancia, "tolerancia");
        if (tolerancia.isNegative() || tolerancia.toMinutes() > 60) {
            throw new IllegalArgumentException("Tolerância deve estar entre 0 e 60 minutos");
        }
        this.tolerancia = tolerancia;
    }

    public GradeHoraria grade() {
        return grade;
    }

    public long toleranciaMinutos() {
        return tolerancia.toMinutes();
    }

    /** Carga base de um dia útil, derivada da grade (padrão: 31.680 s = 08:48). */
    public int jornadaBaseSegundos() {
        return grade.cargaHorariaSegundos();
    }

    public int jornadaPrevistaSegundos(TipoDia tipoDia) {
        return tipoDia.isUtil() ? jornadaBaseSegundos() : 0;
    }

    /**
     * Aplica a regra de tolerância a UMA batida isolada.
     *
     * @return o horário oficial se a batida estiver a até 5:00 dele; senão, o horário real (com segundos)
     */
    public LocalTime aplicarTolerancia(LocalTime real, LocalTime oficial) {
        if (real == null || oficial == null) {
            return real;
        }
        return dentroDaTolerancia(real, oficial) ? oficial : real;
    }

    /** Apura o dia: horários considerados, tempo trabalhado e saldo (em segundos). */
    public ApuracaoDiaria apurar(TipoDia tipoDia, Batidas batidas) {
        Objects.requireNonNull(tipoDia, "tipoDia");
        Objects.requireNonNull(batidas, "batidas");

        List<LocalTime> horarios = batidas.horarios();
        LocalTime[] oficialDaBatida = new LocalTime[horarios.size()];
        if (tipoDia.isUtil()) {
            casarComGrade(horarios, oficialDaBatida);
        }

        List<MarcacaoApurada> marcacoes = new ArrayList<>(TipoBatida.MAXIMO);
        int ajusteTolerancia = 0;
        for (TipoBatida tipo : TipoBatida.values()) {
            int i = tipo.ordinal();
            if (i >= horarios.size()) {
                LocalTime oficialVazio = tipoDia.isUtil() ? grade.horarioOficial(tipo) : null;
                marcacoes.add(new MarcacaoApurada(tipo, oficialVazio, null, null, null, false));
                continue;
            }
            LocalTime real = horarios.get(i);
            LocalTime oficial = oficialDaBatida[i];
            boolean tolerada = oficial != null && dentroDaTolerancia(real, oficial);
            Integer desvio = oficial == null ? null : (int) ChronoUnit.SECONDS.between(oficial, real);
            marcacoes.add(new MarcacaoApurada(tipo, oficial, real, tolerada ? oficial : real, desvio, tolerada));

            boolean emIntervaloFechado = i + 1 < horarios.size() || !tipo.isEntrada();
            if (tolerada && emIntervaloFechado) {
                // entrada atrasada dentro da tolerância devolve o atraso; saída depois do horário não gera crédito
                ajusteTolerancia += tipo.isEntrada() ? desvio : -desvio;
            }
        }

        int trabalhados = segundosTrabalhados(horarios);
        int prevista = jornadaPrevistaSegundos(tipoDia);
        if (batidas.isJornadaAberta()) {
            return new ApuracaoDiaria(tipoDia, marcacoes, prevista, trabalhados, null, StatusJornada.EM_ANDAMENTO);
        }
        return new ApuracaoDiaria(tipoDia, marcacoes, prevista, trabalhados,
                trabalhados + ajusteTolerancia - prevista, StatusJornada.FECHADA);
    }

    /**
     * Casa cada horário da grade com a batida mais próxima do mesmo tipo (entrada com entrada, saída com
     * saída), escolhendo primeiro os pares mais próximos. Cada batida atende a no máximo um horário.
     */
    private void casarComGrade(List<LocalTime> horarios, LocalTime[] oficialDaBatida) {
        record Par(GradeHoraria.Marca marca, int indice, long distancia) {
        }
        List<Par> pares = new ArrayList<>();
        for (GradeHoraria.Marca marca : grade.marcas()) {
            for (int i = 0; i < horarios.size(); i++) {
                boolean entrada = i % 2 == 0;
                if (entrada == marca.entrada()) {
                    pares.add(new Par(marca, i, Math.abs(ChronoUnit.SECONDS.between(marca.horario(), horarios.get(i)))));
                }
            }
        }
        pares.sort(Comparator.comparingLong(Par::distancia).thenComparingInt(Par::indice));
        List<GradeHoraria.Marca> usadas = new ArrayList<>();
        for (Par par : pares) {
            if (oficialDaBatida[par.indice()] == null && !usadas.contains(par.marca())) {
                oficialDaBatida[par.indice()] = par.marca().horario();
                usadas.add(par.marca());
            }
        }
    }

    private boolean dentroDaTolerancia(LocalTime real, LocalTime oficial) {
        return Math.abs(ChronoUnit.SECONDS.between(oficial, real)) <= tolerancia.toSeconds();
    }

    /** Soma bruta dos intervalos fechados (Entrada 1→Saída 1, Entrada 2→Saída 2, Entrada 3→Saída 3). */
    private static int segundosTrabalhados(List<LocalTime> horarios) {
        int total = 0;
        for (int i = 0; i + 1 < horarios.size(); i += 2) {
            total += (int) ChronoUnit.SECONDS.between(horarios.get(i), horarios.get(i + 1));
        }
        return total;
    }
}
