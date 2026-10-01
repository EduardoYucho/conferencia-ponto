package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.exception.RegraNegocioException;

import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Grade horária oficial de um dia de expediente: de 1 a 3 períodos (ex.: 08:00–12:00 e 13:00–17:48).
 * A carga horária do dia é a soma dos períodos (padrão: 240 + 288 = 528 minutos).
 */
public record GradeHoraria(List<Periodo> periodos) {

    public static final int MAXIMO_PERIODOS = 3;

    public static final GradeHoraria PADRAO = new GradeHoraria(
            LocalTime.of(8, 0),
            LocalTime.of(12, 0),
            LocalTime.of(13, 0),
            LocalTime.of(17, 48));

    /** Um período de trabalho da grade (entrada → saída). */
    public record Periodo(LocalTime entrada, LocalTime saida) {
        public Periodo {
            Objects.requireNonNull(entrada, "entrada");
            Objects.requireNonNull(saida, "saida");
        }

        @Override
        public String toString() {
            return hhmm(entrada) + "-" + hhmm(saida);
        }
    }

    public GradeHoraria {
        if (periodos == null || periodos.isEmpty() || periodos.size() > MAXIMO_PERIODOS) {
            throw new RegraNegocioException("GRADE_INVALIDA", "Um dia de trabalho tem de 1 a 3 períodos.");
        }
        periodos = List.copyOf(periodos);
        LocalTime anterior = null;
        for (Periodo p : periodos) {
            if (!p.entrada().isBefore(p.saida())) {
                throw new RegraNegocioException("GRADE_INVALIDA",
                        "No período %s a saída deve ser depois da entrada.".formatted(p));
            }
            if (anterior != null && p.entrada().isBefore(anterior)) {
                throw new RegraNegocioException("GRADE_INVALIDA",
                        "Os períodos precisam estar em ordem e sem sobreposição (%s).".formatted(p));
            }
            anterior = p.saida();
        }
    }

    /** Grade de dois períodos (compatível com a configuração antiga: entrada1, saida1, entrada2, saida2). */
    public GradeHoraria(LocalTime entrada1, LocalTime saida1, LocalTime entrada2, LocalTime saida2) {
        this(List.of(new Periodo(entrada1, saida1), new Periodo(entrada2, saida2)));
    }

    /**
     * Lê o texto usado no banco e na tela: períodos "HH:MM-HH:MM" separados por espaço, vírgula ou ponto e
     * vírgula (ex.: "08:00-12:00 13:00-17:48"). Texto vazio = sem expediente ({@code null}).
     */
    public static GradeHoraria ler(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        List<Periodo> periodos = new ArrayList<>();
        for (String parte : texto.trim().split("[\\s,;]+")) {
            String[] horas = parte.split("-");
            if (horas.length != 2) {
                throw new RegraNegocioException("GRADE_INVALIDA",
                        "Período inválido: \"%s\" (use HH:MM-HH:MM, ex.: 08:00-12:00).".formatted(parte));
            }
            try {
                periodos.add(new Periodo(LocalTime.parse(normalizar(horas[0])), LocalTime.parse(normalizar(horas[1]))));
            } catch (java.time.format.DateTimeParseException e) {
                throw new RegraNegocioException("GRADE_INVALIDA",
                        "Horário inválido em \"%s\" (use HH:MM-HH:MM, ex.: 08:00-12:00).".formatted(parte));
            }
        }
        return new GradeHoraria(periodos);
    }

    /** "08:00-12:00 13:00-17:48" */
    public String texto() {
        return String.join(" ", periodos.stream().map(Periodo::toString).toList());
    }

    /** Um horário da grade: entrada (08:00, 13:00) ou saída (12:00, 17:48). */
    public record Marca(LocalTime horario, boolean entrada) {
    }

    /** As marcas da grade (entrada e saída de cada período), em ordem. */
    public List<Marca> marcas() {
        List<Marca> marcas = new ArrayList<>(periodos.size() * 2);
        for (Periodo p : periodos) {
            marcas.add(new Marca(p.entrada(), true));
            marcas.add(new Marca(p.saida(), false));
        }
        return marcas;
    }

    /** Horário oficial da posição da batida; posições além dos períodos da grade não têm horário. */
    public LocalTime horarioOficial(TipoBatida tipo) {
        int periodo = tipo.ordinal() / 2;
        if (periodo >= periodos.size()) {
            return null;
        }
        return tipo.isEntrada() ? periodos.get(periodo).entrada() : periodos.get(periodo).saida();
    }

    public LocalTime entrada1() {
        return horarioOficial(TipoBatida.ENTRADA_1);
    }

    public LocalTime saida1() {
        return horarioOficial(TipoBatida.SAIDA_1);
    }

    public LocalTime entrada2() {
        return horarioOficial(TipoBatida.ENTRADA_2);
    }

    public LocalTime saida2() {
        return horarioOficial(TipoBatida.SAIDA_2);
    }

    public int cargaHorariaMinutos() {
        return cargaHorariaSegundos() / 60;
    }

    /** Carga do dia (padrão: 240 + 288 min = 31.680 s = 08:48). */
    public int cargaHorariaSegundos() {
        return periodos.stream().mapToInt(p -> (int) ChronoUnit.SECONDS.between(p.entrada(), p.saida())).sum();
    }

    private static String normalizar(String hora) {
        String h = hora.trim();
        return h.length() == 4 && h.charAt(1) == ':' ? "0" + h : h; // "8:00" -> "08:00"
    }

    private static String hhmm(LocalTime t) {
        return "%02d:%02d".formatted(t.getHour(), t.getMinute());
    }
}
