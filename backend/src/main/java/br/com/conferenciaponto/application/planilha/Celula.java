package br.com.conferenciaponto.application.planilha;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Uma célula da planilha de conferência, sem saber se vai para o Excel ou para o Google Sheets.
 *
 * <p>Durações e horários são números de verdade (somam, filtram e ordenam na planilha); uma fórmula leva
 * também o valor já calculado, usado pelo Excel antes do primeiro recálculo e pelos testes.
 *
 * @param texto   conteúdo de {@link Tipo#TEXTO}
 * @param data    conteúdo de {@link Tipo#DATA}
 * @param valor   segundos ({@link Tipo#DURACAO}, com sinal, e {@link Tipo#HORA}, desde 00:00) ou o número
 *                ({@link Tipo#INTEIRO}); em fórmulas, o resultado já calculado
 * @param formula fórmula sem o "=" (ex.: {@code SUM(H6:H36)}), nos tipos numéricos; {@code null} = valor fixo
 */
public record Celula(Tipo tipo, String texto, LocalDate data, long valor, String formula, Estilo estilo) {

    public enum Tipo { VAZIA, TEXTO, DATA, HORA, DURACAO, INTEIRO }

    public static final Celula VAZIA = new Celula(Tipo.VAZIA, null, null, 0, null, Estilo.NORMAL);

    public Celula {
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(estilo, "estilo");
    }

    public static Celula vazia(Estilo estilo) {
        return new Celula(Tipo.VAZIA, null, null, 0, null, estilo);
    }

    public static Celula texto(String texto) {
        return texto(texto, Estilo.NORMAL);
    }

    public static Celula texto(String texto, Estilo estilo) {
        return texto == null || texto.isEmpty() ? vazia(estilo) : new Celula(Tipo.TEXTO, texto, null, 0, null, estilo);
    }

    public static Celula data(LocalDate data, Estilo estilo) {
        return new Celula(Tipo.DATA, null, Objects.requireNonNull(data, "data"), 0, null, estilo);
    }

    public static Celula hora(LocalTime hora, Estilo estilo) {
        return new Celula(Tipo.HORA, null, null, hora.toSecondOfDay(), null, estilo);
    }

    public static Celula duracao(long segundos, Estilo estilo) {
        return new Celula(Tipo.DURACAO, null, null, segundos, null, estilo);
    }

    public static Celula inteiro(long numero, Estilo estilo) {
        return new Celula(Tipo.INTEIRO, null, null, numero, null, estilo);
    }

    /** @param calculado resultado da fórmula, em segundos */
    public static Celula formulaDuracao(String formula, long calculado, Estilo estilo) {
        return new Celula(Tipo.DURACAO, null, null, calculado, Objects.requireNonNull(formula, "formula"), estilo);
    }

    public static Celula formulaInteiro(String formula, long calculado, Estilo estilo) {
        return new Celula(Tipo.INTEIRO, null, null, calculado, Objects.requireNonNull(formula, "formula"), estilo);
    }

    public boolean temFormula() {
        return formula != null;
    }

    public boolean numerica() {
        return tipo == Tipo.HORA || tipo == Tipo.DURACAO || tipo == Tipo.INTEIRO;
    }

    /** Valor como fração de dia (o número que o Excel e o Google Sheets guardam para horas e durações). */
    public double fracaoDoDia() {
        return valor / 86_400.0;
    }
}
