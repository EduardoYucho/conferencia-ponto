package br.com.conferenciaponto.domain.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Um ponto do horário ou dos dias trabalhados que esbarra numa regra da lei. É só aviso: o sistema não impede
 * de salvar (há acordos e convenções coletivas que mudam a regra), mas mostra para quem lança e para quem confere.
 *
 * @param codigo     identifica a regra (ex.: SEMANA_SEM_FOLGA), para a tela e para não repetir o aviso no sino
 * @param data       o dia (ou o início da semana) a que o aviso se refere; null = o horário como um todo
 * @param mensagem   o que acontece, em português simples
 * @param fundamento de onde vem a regra (ex.: "CLT, art. 71")
 */
public record AvisoLegal(Nivel nivel, String codigo, LocalDate data, String mensagem, String fundamento) {

    public enum Nivel {
        /** Passa da regra em qualquer caso (ex.: semana sem folga). */
        ALERTA,
        /** Só vale com acordo ou convenção coletiva, ou passa de uma regra que ainda vai valer. */
        AVISO,
        /** Para saber (ex.: a compensação precisa ser no mesmo mês). */
        INFO
    }

    public AvisoLegal {
        Objects.requireNonNull(nivel, "nivel");
        Objects.requireNonNull(codigo, "codigo");
        Objects.requireNonNull(mensagem, "mensagem");
        Objects.requireNonNull(fundamento, "fundamento");
    }
}
