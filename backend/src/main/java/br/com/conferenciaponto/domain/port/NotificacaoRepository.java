package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.Notificacao;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificacaoRepository {

    boolean existeChave(UUID usuarioId, String chave);

    void salvar(Notificacao notificacao);

    /** As mais recentes primeiro. */
    List<Notificacao> listarRecentes(UUID usuarioId, int limite);

    int contarNaoLidas(UUID usuarioId);

    /** @return false se a notificação não existe */
    boolean marcarLida(UUID usuarioId, UUID id, Instant quando);

    int marcarTodasLidas(UUID usuarioId, Instant quando);

    /** Marca como lidos os avisos de prazo (CICLO_*) que não terminam com o sufixo informado (ciclo atual). */
    int marcarLidosAvisosDeCicloExceto(UUID usuarioId, String sufixoChave, Instant quando);
}
