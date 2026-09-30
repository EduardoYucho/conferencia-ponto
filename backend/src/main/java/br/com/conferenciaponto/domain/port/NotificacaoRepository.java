package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.Notificacao;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificacaoRepository {

    boolean existeChave(String chave);

    void salvar(Notificacao notificacao);

    /** As mais recentes primeiro. */
    List<Notificacao> listarRecentes(int limite);

    int contarNaoLidas();

    /** @return false se a notificação não existe */
    boolean marcarLida(UUID id, Instant quando);

    int marcarTodasLidas(Instant quando);

    /** Marca como lidos os avisos de prazo (CICLO_*) que não terminam com o sufixo informado (ciclo atual). */
    int marcarLidosAvisosDeCicloExceto(String sufixoChave, Instant quando);
}
