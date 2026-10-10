package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import java.time.Instant;
import java.util.List;

/**
 * Uma mensagem ou um evento, na ordem do PDF.
 *
 * @param ordem     posição na conversa, a partir de 1
 * @param lado      só nas mensagens
 * @param remetente só nas mensagens (null quando o PDF não trouxe o cabeçalho da mensagem)
 * @param momento   data e hora (null se o PDF não permitiu saber)
 * @param texto     já sem a chave do bot nem dados de acesso remoto, depois do {@link Mascaramento}
 * @param anexos    ordens dos anexos enviados nesta mensagem
 * @param pagina    página do PDF onde a mensagem começa
 */
public record ItemDaConversa(int ordem, TipoDeItem tipo, Lado lado, String remetente, Instant momento, String texto,
                             List<Integer> anexos, int pagina) {

    public ItemDaConversa {
        texto = texto == null ? "" : texto;
        anexos = anexos == null ? List.of() : List.copyOf(anexos);
    }

    public boolean mensagem() {
        return tipo == TipoDeItem.MENSAGEM;
    }

    public ItemDaConversa comTexto(String novo) {
        return new ItemDaConversa(ordem, tipo, lado, remetente, momento, novo, anexos, pagina);
    }

    @Override
    public String toString() {
        return "ItemDaConversa[ordem=" + ordem + ", tipo=" + tipo + ", lado=" + lado + ", momento=" + momento
                + ", caracteres=" + texto.length() + ", anexos=" + anexos + "]";
    }
}
