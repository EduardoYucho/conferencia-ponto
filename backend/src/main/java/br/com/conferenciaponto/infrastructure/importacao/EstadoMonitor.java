package br.com.conferenciaponto.infrastructure.importacao;

import java.time.Instant;

/**
 * Situação do monitor de comprovantes. Também é publicado como evento de aplicação
 * a cada mudança de situação (o canal SSE repassa ao front-end).
 *
 * @param mensagem        motivo quando a pasta está inacessível
 * @param desde           quando entrou na situação atual
 * @param ultimaVarredura última conferência completa da pasta
 */
public record EstadoMonitor(Situacao situacao, String diretorio, String mensagem, Instant desde,
                            Instant ultimaVarredura) {

    public enum Situacao {
        /** Ainda tentando acessar a pasta pela primeira vez. */
        INICIANDO,
        /** Observando a pasta. */
        ATIVO,
        /** Pasta inacessível; novas tentativas em intervalos fixos. */
        INDISPONIVEL,
        /** Aplicação encerrando. */
        ENCERRADO
    }

    public boolean ativo() {
        return situacao == Situacao.ATIVO;
    }

    EstadoMonitor comVarredura(Instant quando) {
        return new EstadoMonitor(situacao, diretorio, mensagem, desde, quando);
    }
}
