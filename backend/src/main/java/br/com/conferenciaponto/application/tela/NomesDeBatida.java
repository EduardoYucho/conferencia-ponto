package br.com.conferenciaponto.application.tela;

import br.com.conferenciaponto.domain.model.GradeHoraria;

import java.time.LocalTime;

/**
 * Os nomes que as pessoas usam para as batidas: "Entrada", "Saída p/ almoço", "Volta do almoço", "Saída" — no
 * lugar de "Entrada 1, Saída 1, Entrada 2, Saída 2".
 */
public final class NomesDeBatida {

    private static final LocalTime ALMOCO_DESDE = LocalTime.of(10, 30);
    private static final LocalTime ALMOCO_ATE = LocalTime.of(14, 30);

    private NomesDeBatida() {
    }

    /**
     * @param grade   horário do dia ({@code null} em dia sem expediente)
     * @param posicao posição da batida no dia, a partir de 0 (pares são entradas)
     */
    public static String de(GradeHoraria grade, int posicao) {
        if (posicao == 0) {
            return "Entrada";
        }
        int periodos = grade == null ? 1 : grade.periodos().size();
        boolean entrada = posicao % 2 == 0;
        int intervalo = (posicao - 1) / 2; // 0 = primeiro intervalo do dia
        boolean dentroDaGrade = intervalo < periodos - 1;
        if (entrada) {
            return dentroDaGrade ? "Volta do " + nomeDoIntervalo(grade, intervalo) : "Volta";
        }
        return dentroDaGrade ? "Saída p/ " + nomeDoIntervalo(grade, intervalo) : "Saída";
    }

    /** "Bater a entrada agora", "Bater a saída para o almoço", "Bater a volta do almoço", "Bater a saída agora". */
    public static String botao(GradeHoraria grade, int posicao) {
        String nome = de(grade, posicao);
        if (nome.equals("Entrada")) {
            return "Bater a entrada agora";
        }
        if (nome.equals("Saída")) {
            return "Bater a saída agora";
        }
        if (nome.equals("Volta")) {
            return "Bater a volta agora";
        }
        return "Bater a " + nome.substring(0, 1).toLowerCase() + nome.substring(1).replace("p/ ", "para o ");
    }

    /** "almoço" quando o intervalo começa por volta do meio-dia; senão, "intervalo". */
    private static String nomeDoIntervalo(GradeHoraria grade, int intervalo) {
        LocalTime inicio = grade.periodos().get(intervalo).saida();
        return !inicio.isBefore(ALMOCO_DESDE) && !inicio.isAfter(ALMOCO_ATE) ? "almoço" : "intervalo";
    }
}
