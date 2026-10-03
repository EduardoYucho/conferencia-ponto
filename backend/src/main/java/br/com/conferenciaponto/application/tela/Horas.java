package br.com.conferenciaponto.application.tela;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * Como as telas escrevem horas e datas: do jeito que se fala ("8h 48min", "12 min", "Qua, 30/09"), não no
 * formato de relógio. Os valores exatos, com segundos, continuam indo junto para quem quiser conferir.
 */
public final class Horas {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");
    private static final String[] SEMANA_CURTA = {"Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom"};
    private static final String[] SEMANA = {"Segunda", "Terça", "Quarta", "Quinta", "Sexta", "Sábado", "Domingo"};
    private static final String[] SEMANA_EXTENSO = {"segunda-feira", "terça-feira", "quarta-feira", "quinta-feira",
            "sexta-feira", "sábado", "domingo"};
    private static final String[] MESES = {"janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho",
            "agosto", "setembro", "outubro", "novembro", "dezembro"};

    private Horas() {
    }

    /** 31680 → "8h 48min"; 3600 → "1h"; 720 → "12 min"; 45 → "45 s"; 0 → "0 min". Sempre sem sinal. */
    public static String duracao(long segundos) {
        long s = Math.abs(segundos);
        if (s == 0) {
            return "0 min";
        }
        if (s < 60) {
            return s + " s";
        }
        long horas = s / 3600;
        long minutos = (s % 3600) / 60;
        if (horas == 0) {
            return minutos + " min";
        }
        return minutos == 0 ? horas + "h" : "%dh %02dmin".formatted(horas, minutos);
    }

    /** +900 → "+ 15 min"; −720 → "− 12 min"; 0 → "0 min". */
    public static String saldo(long segundos) {
        if (segundos == 0) {
            return duracao(0);
        }
        return (segundos > 0 ? "+ " : "− ") + duracao(segundos);
    }

    /** +900 → "+ 15 min a favor"; −720 → "− 12 min devendo"; 0 → "em dia". */
    public static String saldoComSentido(long segundos) {
        if (segundos == 0) {
            return "em dia";
        }
        return saldo(segundos) + (segundos > 0 ? " a favor" : " devendo");
    }

    public static String hora(LocalTime horario) {
        return horario == null ? null : HORA.format(horario);
    }

    /** "30/09" */
    public static String diaMes(LocalDate data) {
        return DIA_MES.format(data);
    }

    /** "Qua, 30/09" */
    public static String dia(LocalDate data) {
        return SEMANA_CURTA[indice(data.getDayOfWeek())] + ", " + diaMes(data);
    }

    /** "Quarta, 30/09" */
    public static String diaLongo(LocalDate data) {
        return SEMANA[indice(data.getDayOfWeek())] + ", " + diaMes(data);
    }

    /** "Sábado", "Domingo", "Quarta"... */
    public static String diaDaSemana(LocalDate data) {
        return SEMANA[indice(data.getDayOfWeek())];
    }

    /** "sexta-feira, 2 de outubro de 2026" */
    public static String porExtenso(LocalDate data) {
        return "%s, %d de %s de %d".formatted(SEMANA_EXTENSO[indice(data.getDayOfWeek())], data.getDayOfMonth(),
                MESES[data.getMonthValue() - 1], data.getYear());
    }

    /** "outubro" */
    public static String mes(int mes) {
        return MESES[mes - 1];
    }

    /** "Setembro de 2026" */
    public static String mesAno(YearMonth referencia) {
        String nome = MESES[referencia.getMonthValue() - 1];
        return Character.toUpperCase(nome.charAt(0)) + nome.substring(1) + " de " + referencia.getYear();
    }

    /** "Bom dia" até 11:59, "Boa tarde" até 17:59, depois "Boa noite". */
    public static String saudacao(LocalTime agora) {
        if (agora.getHour() < 12) {
            return "Bom dia";
        }
        return agora.getHour() < 18 ? "Boa tarde" : "Boa noite";
    }

    private static int indice(DayOfWeek dia) {
        return dia.getValue() - 1;
    }
}
