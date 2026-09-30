package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;

import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Value object com as batidas REAIS de um dia (precisão de segundos), até 3 intervalos.
 *
 * <p>Invariantes garantidas na construção:
 * <ul>
 *   <li>Sequência: nenhuma batida existe sem a anterior (ex.: Saída 1 exige Entrada 1).</li>
 *   <li>Cronologia: saída &gt; entrada dentro do mesmo intervalo; a entrada seguinte &ge; saída anterior.</li>
 * </ul>
 * Frações de segundo são descartadas (as colunas no banco são TIME(0)).
 */
public record Batidas(LocalTime entrada1, LocalTime saida1, LocalTime entrada2, LocalTime saida2,
                      LocalTime entrada3, LocalTime saida3) {

    private static final int MAXIMO_INTERVALOS = TipoBatida.MAXIMO / 2;

    public Batidas {
        entrada1 = semFracao(entrada1);
        saida1 = semFracao(saida1);
        entrada2 = semFracao(entrada2);
        saida2 = semFracao(saida2);
        entrada3 = semFracao(entrada3);
        saida3 = semFracao(saida3);

        LocalTime[] horarios = {entrada1, saida1, entrada2, saida2, entrada3, saida3};
        validarSequencia(horarios);
        validarCronologia(horarios);
    }

    /** Dia com até 2 intervalos (o caso comum). */
    public Batidas(LocalTime entrada1, LocalTime saida1, LocalTime entrada2, LocalTime saida2) {
        this(entrada1, saida1, entrada2, saida2, null, null);
    }

    public static Batidas vazia() {
        return new Batidas(null, null, null, null, null, null);
    }

    /** Monta as batidas a partir de 1 a 3 intervalos (lançamento manual). */
    public static Batidas deIntervalos(List<Intervalo> intervalos) {
        if (intervalos == null || intervalos.isEmpty() || intervalos.size() > MAXIMO_INTERVALOS) {
            throw new RegraNegocioException("QUANTIDADE_INTERVALOS_INVALIDA",
                    "Informe de 1 a %d intervalos de entrada/saída.".formatted(MAXIMO_INTERVALOS));
        }
        List<LocalTime> horarios = new ArrayList<>();
        for (Intervalo intervalo : intervalos) {
            horarios.add(intervalo.entrada());
            horarios.add(intervalo.saida());
        }
        return posicionais(horarios);
    }

    /**
     * Monta as batidas a partir de até 6 horários em qualquer ordem: ordena e distribui em
     * Entrada 1, Saída 1, Entrada 2... (ajuste manual, conciliação com o RH).
     */
    public static Batidas deHorarios(List<LocalTime> horarios) {
        List<LocalTime> ordenados = new ArrayList<>(horarios);
        if (ordenados.size() > TipoBatida.MAXIMO) {
            throw new RegraNegocioException("JORNADA_COMPLETA",
                    "O dia comporta no máximo %d batidas.".formatted(TipoBatida.MAXIMO));
        }
        Collections.sort(ordenados);
        return posicionais(ordenados);
    }

    /** Horários na ordem das posições (sem reordenar), completando com vazio. */
    private static Batidas posicionais(List<LocalTime> horarios) {
        List<LocalTime> h = new ArrayList<>(horarios);
        while (h.size() < TipoBatida.MAXIMO) {
            h.add(null);
        }
        return new Batidas(h.get(0), h.get(1), h.get(2), h.get(3), h.get(4), h.get(5));
    }

    /** Horários registrados, em ordem cronológica. */
    public List<LocalTime> horarios() {
        List<LocalTime> lista = new ArrayList<>(TipoBatida.MAXIMO);
        for (TipoBatida tipo : TipoBatida.values()) {
            if (horario(tipo) != null) {
                lista.add(horario(tipo));
            }
        }
        return List.copyOf(lista);
    }

    public LocalTime horario(TipoBatida tipo) {
        return switch (tipo) {
            case ENTRADA_1 -> entrada1;
            case SAIDA_1 -> saida1;
            case ENTRADA_2 -> entrada2;
            case SAIDA_2 -> saida2;
            case ENTRADA_3 -> entrada3;
            case SAIDA_3 -> saida3;
        };
    }

    /** Próxima posição livre, ou vazio se o dia já está completo. */
    public Optional<TipoBatida> proximaBatida() {
        for (TipoBatida tipo : TipoBatida.values()) {
            if (horario(tipo) == null) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }

    /** Retorna uma nova instância com o horário registrado na próxima posição livre. */
    public Batidas comBatida(LocalTime horario) {
        if (horario == null) {
            throw new RegraNegocioException("BATIDA_SEM_HORARIO", "Horário da batida não informado.");
        }
        if (proximaBatida().isEmpty()) {
            throw new RegraNegocioException("JORNADA_COMPLETA",
                    "As %d batidas do dia já foram registradas.".formatted(TipoBatida.MAXIMO));
        }
        List<LocalTime> lista = new ArrayList<>(horarios());
        lista.add(horario);
        return posicionais(lista);
    }

    /**
     * Insere o horário na posição cronológica correta, reorganizando as colunas.
     * Usado na importação de comprovantes, que podem chegar fora de ordem
     * (ex.: o PDF das 12:00 baixado depois do das 13:00).
     */
    public Batidas comBatidaOrdenada(LocalTime horario) {
        if (horario == null) {
            throw new RegraNegocioException("BATIDA_SEM_HORARIO", "Horário da batida não informado.");
        }
        LocalTime novo = semFracao(horario);
        List<LocalTime> lista = new ArrayList<>(horarios());
        if (lista.contains(novo)) {
            throw new RegraNegocioException("BATIDA_DUPLICADA", "A batida %s já está registrada.".formatted(novo));
        }
        if (lista.size() == TipoBatida.MAXIMO) {
            throw new RegraNegocioException("JORNADA_COMPLETA",
                    "As %d batidas do dia já foram registradas.".formatted(TipoBatida.MAXIMO));
        }
        lista.add(novo);
        Collections.sort(lista);
        return posicionais(lista);
    }

    /** Primeira batida existente a menos de {@code janela} do horário informado (inclusive). */
    public Optional<LocalTime> batidaProxima(LocalTime horario, Duration janela) {
        for (LocalTime existente : horarios()) {
            if (Math.abs(ChronoUnit.SECONDS.between(existente, horario)) <= janela.toSeconds()) {
                return Optional.of(existente);
            }
        }
        return Optional.empty();
    }

    /** Posição ocupada por um horário (segundos inclusos), se existir. */
    public Optional<TipoBatida> posicaoDe(LocalTime horario) {
        LocalTime alvo = semFracao(horario);
        for (TipoBatida tipo : TipoBatida.values()) {
            if (alvo != null && alvo.equals(horario(tipo))) {
                return Optional.of(tipo);
            }
        }
        return Optional.empty();
    }

    /** Há entrada sem a saída correspondente (número ímpar de batidas). */
    public boolean isJornadaAberta() {
        return quantidade() % 2 == 1;
    }

    public boolean isVazia() {
        return entrada1 == null;
    }

    public int quantidade() {
        return horarios().size();
    }

    private static LocalTime semFracao(LocalTime horario) {
        return horario == null ? null : horario.truncatedTo(ChronoUnit.SECONDS);
    }

    private static void validarSequencia(LocalTime[] horarios) {
        for (int i = 1; i < horarios.length; i++) {
            if (horarios[i] != null && horarios[i - 1] == null) {
                throw new RegraNegocioException("BATIDA_FORA_DE_SEQUENCIA",
                        "%s informada sem %s.".formatted(
                                TipoBatida.values()[i].rotulo(), TipoBatida.values()[i - 1].rotulo()));
            }
        }
    }

    private static void validarCronologia(LocalTime[] horarios) {
        for (int i = 1; i < horarios.length; i++) {
            LocalTime atual = horarios[i];
            LocalTime anterior = horarios[i - 1];
            if (atual == null) {
                continue;
            }
            // i ímpar = saída comparada com a entrada do mesmo intervalo (estritamente posterior);
            // i par   = entrada comparada com a saída anterior (pode ser igual: intervalo zero).
            boolean mesmoIntervalo = i % 2 == 1;
            boolean valido = mesmoIntervalo ? atual.isAfter(anterior) : !atual.isBefore(anterior);
            if (!valido) {
                throw new RegraNegocioException("BATIDA_FORA_DE_ORDEM",
                        "%s (%s) deve ser %s %s (%s).".formatted(
                                TipoBatida.values()[i].rotulo(), atual,
                                mesmoIntervalo ? "posterior à" : "igual ou posterior à",
                                TipoBatida.values()[i - 1].rotulo(), anterior));
            }
        }
    }
}
