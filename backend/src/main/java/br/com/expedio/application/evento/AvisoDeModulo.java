package br.com.expedio.application.evento;

import java.util.Objects;
import java.util.UUID;

/**
 * Um módulo (atendimentos, por exemplo) pede um aviso no sino de uma pessoa. O sino é do ponto: o módulo publica este
 * evento e o ponto grava o aviso (NotificacoesUseCase), sem que um conheça as classes do outro. A mesma chave não
 * vira dois avisos.
 *
 * @param chave do módulo, única por pessoa (ex.: "ATENDIMENTO_PARADO:&lt;id&gt;"); o ponto acrescenta o prefixo
 *              "MODULO:"
 * @param link  a tela que o aviso abre (ex.: "/atendimentos/&lt;id&gt;"), ou {@code null}
 */
public record AvisoDeModulo(UUID usuarioId, String chave, String titulo, String mensagem, String link) {

    public AvisoDeModulo {
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(chave, "chave");
        Objects.requireNonNull(titulo, "titulo");
        Objects.requireNonNull(mensagem, "mensagem");
    }
}
