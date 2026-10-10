package br.com.conferenciaponto.modulos.atendimento.domain.fila;

/**
 * Quem acompanha a fila: atualiza a situação do atendimento e avisa a tela. Chamado pela thread que executa a
 * tarefa, antes e depois dela.
 */
public interface AcompanhamentoDaFila {

    void aoComecar(Tarefa tarefa);

    /** @param resultado o resultado efetivo (uma falha temporária na última tentativa já chega como definitiva) */
    void aoTerminar(Tarefa tarefa, ResultadoDaTarefa resultado);
}
