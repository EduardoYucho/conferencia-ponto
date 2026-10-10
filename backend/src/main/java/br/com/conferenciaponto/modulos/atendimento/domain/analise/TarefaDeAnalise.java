package br.com.conferenciaponto.modulos.atendimento.domain.analise;

import java.util.UUID;

/**
 * A última tarefa "analisar" de um arquivo.
 *
 * @param situacao pendente, executando, concluida, falhou, cancelada ou pausada
 */
public record TarefaDeAnalise(UUID arquivoId, String situacao, String erroCodigo, String erroMensagem, int tentativas,
                              int maxTentativas) {

    public boolean ativa() {
        return "pendente".equals(situacao) || "executando".equals(situacao) || "pausada".equals(situacao);
    }
}
