package br.com.conferenciaponto.domain.port;

import java.util.List;
import br.com.conferenciaponto.domain.model.Feriado;
import java.time.LocalDate;

/** Porta de saída: consulta (e cadastro) do calendário de feriados. */
public interface CalendarioFeriados {

    boolean isFeriado(LocalDate data);

    /**
     * Cadastra um feriado da empresa (ex.: aceito na conciliação com o relatório do RH).
     * Não faz nada se a data já for feriado.
     */
    default void cadastrar(LocalDate data, String descricao) {
        throw new UnsupportedOperationException("Calendário somente leitura");
    }

    /** Feriados do período (para o painel rotular os dias sem registro). */
    default List<Feriado> listar(LocalDate inicio, LocalDate fim) {
        return List.of();
    }
}
