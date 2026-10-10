package br.com.conferenciaponto.modulos.atendimento.domain.ia;

import java.time.Instant;
import java.util.Objects;

/**
 * Um arquivo no armazenamento temporário do modelo.
 *
 * @param nome     identificador no modelo (ex.: files/abc123), usado para consultar e apagar
 * @param uri      endereço usado no pedido de geração
 * @param expiraEm quando o modelo apaga sozinho (null se ele não informou)
 */
public record ArquivoRemoto(String nome, String uri, String tipoDeMidia, Estado estado, Instant expiraEm) {

    public enum Estado {
        PROCESSANDO, ATIVO, FALHOU
    }

    public ArquivoRemoto {
        Objects.requireNonNull(nome, "nome");
        Objects.requireNonNull(estado, "estado");
    }

    public boolean ativo() {
        return estado == Estado.ATIVO;
    }
}
