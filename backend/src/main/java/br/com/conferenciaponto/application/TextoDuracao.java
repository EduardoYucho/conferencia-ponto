package br.com.conferenciaponto.application;

/** Formatação de durações em mensagens (ex.: saldo +12:34:56). */
public final class TextoDuracao {

    private TextoDuracao() {
    }

    public static String saldo(long segundos) {
        return (segundos < 0 ? "-" : "+") + duracao(Math.abs(segundos));
    }

    public static String duracao(long segundos) {
        long s = Math.abs(segundos);
        return "%02d:%02d:%02d".formatted(s / 3600, (s % 3600) / 60, s % 60);
    }
}
