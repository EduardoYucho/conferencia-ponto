package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.Feriado;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

    /** Cadastra (ou substitui) o feriado da data. */
    default void salvar(Feriado feriado) {
        throw new UnsupportedOperationException("Calendário somente leitura");
    }

    default Optional<Feriado> buscar(LocalDate data) {
        return listar(data, data).stream().findFirst();
    }

    /** Remove o feriado da data (o dia volta a ser útil ou fim de semana). */
    default void excluir(LocalDate data) {
        throw new UnsupportedOperationException("Calendário somente leitura");
    }

    /** Feriados do período (para o painel rotular os dias sem registro). */
    default List<Feriado> listar(LocalDate inicio, LocalDate fim) {
        return List.of();
    }
}
