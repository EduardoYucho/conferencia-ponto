package br.com.conferenciaponto.modulos.atendimento.domain.chave;

import br.com.conferenciaponto.domain.exception.ConflitoException;

/**
 * O cofre não consegue ler ou guardar uma chave (HTTP 409, com a explicação para quem vê a tela):
 * <ul>
 *   <li>{@code CHAVE_MESTRA_TROCADA}: a chave mestra não é mais a que cifrou a chave guardada (arquivo apagado ou
 *       trocado): basta cadastrar a chave do Gemini de novo;</li>
 *   <li>{@code CHAVE_MESTRA_ILEGIVEL} / {@code CHAVE_MESTRA_INDISPONIVEL}: o arquivo da chave mestra está
 *       estragado ou não pôde ser lido nem criado: assunto do administrador.</li>
 * </ul>
 */
public class CofreException extends ConflitoException {

    public CofreException(String codigo, String mensagem) {
        super(codigo, mensagem);
    }

    public static CofreException chaveMestraTrocada() {
        return new CofreException("CHAVE_MESTRA_TROCADA",
                "A chave do Gemini guardada não pode mais ser lida: a chave mestra do servidor mudou (o arquivo foi "
                        + "apagado ou trocado). Cadastre a sua chave de novo.");
    }
}
