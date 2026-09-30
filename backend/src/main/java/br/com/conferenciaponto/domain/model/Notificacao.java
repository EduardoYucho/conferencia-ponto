package br.com.conferenciaponto.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aviso exibido no sino do painel.
 *
 * @param chave identifica o aviso para não repeti-lo (ex.: {@code CICLO_30_DIAS:<id do ciclo>})
 * @param link  rota do front-end relacionada (opcional)
 */
public record Notificacao(UUID id, TipoNotificacao tipo, String chave, String titulo, String mensagem, String link,
                          Instant criadaEm, Instant lidaEm) {

    public Notificacao {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tipo, "tipo");
        Objects.requireNonNull(chave, "chave");
        Objects.requireNonNull(titulo, "titulo");
        Objects.requireNonNull(mensagem, "mensagem");
        Objects.requireNonNull(criadaEm, "criadaEm");
    }

    public static Notificacao nova(TipoNotificacao tipo, String chave, String titulo, String mensagem, String link,
                                   Instant agora) {
        return new Notificacao(UUID.randomUUID(), tipo, chave, titulo, mensagem, link, agora, null);
    }

    public boolean isLida() {
        return lidaEm != null;
    }
}
