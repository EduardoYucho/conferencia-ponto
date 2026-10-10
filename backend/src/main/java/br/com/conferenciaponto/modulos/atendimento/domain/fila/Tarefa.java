package br.com.conferenciaponto.modulos.atendimento.domain.fila;

import java.util.UUID;

/**
 * Uma tarefa entregue a quem a executa (já marcada como "executando", com prazo).
 *
 * @param usuarioId  dono do atendimento (a chave do Gemini e os limites por pessoa são dele)
 * @param tentativa  número desta tentativa, a partir de 1
 */
public record Tarefa(long id, TipoDeTarefa tipo, UUID atendimentoId, UUID usuarioId, UUID arquivoId, UUID saidaId,
                     int tentativa, int maxTentativas) {

    public boolean ultimaTentativa() {
        return tentativa >= maxTentativas;
    }
}
