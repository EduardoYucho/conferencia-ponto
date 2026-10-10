package br.com.conferenciaponto.modulos.atendimento.domain.fila;

/**
 * Quem sabe fazer um tipo de tarefa. A fila chama {@link #executar} numa thread própria, com o limite de tarefas
 * por pessoa e no total; o executor não precisa (nem deve) mexer na situação da tarefa.
 */
public interface ExecutorDeTarefa {

    TipoDeTarefa tipo();

    /** Tarefas deste tipo ao mesmo tempo, no total (0 = só o limite geral da fila). */
    default int limiteSimultaneo() {
        return 0;
    }

    /**
     * Nunca lança exceção para falhas previstas: devolve o {@link ResultadoDaTarefa}. Uma exceção inesperada vira
     * falha temporária (e o erro vai para o log, sem conteúdo).
     */
    ResultadoDaTarefa executar(Tarefa tarefa);
}
