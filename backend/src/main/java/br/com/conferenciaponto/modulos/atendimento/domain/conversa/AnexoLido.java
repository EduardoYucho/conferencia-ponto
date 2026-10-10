package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import java.time.Instant;

/**
 * Um anexo da conversa, achado pelo link no PDF.
 *
 * @param ordem          posição entre os anexos do PDF, a partir de 1
 * @param nome           nome original (do parâmetro response-content-disposition do link)
 * @param extensao       em minúsculas, ou "" quando o nome não tem
 * @param mensagemOrdem  ordem da mensagem em que foi enviado (null se não deu para saber)
 * @param momento        o da mensagem
 * @param url            link assinado: só vai para o banco (até o download), nunca para log, tela ou toString
 * @param validoAte      até quando o link vale (X-Amz-Date + X-Amz-Expires), ou null se o link não informa
 */
public record AnexoLido(int ordem, String nome, String extensao, CategoriaDoArquivo categoria, String tipoDeMidia,
                        int pagina, float y, Integer mensagemOrdem, Instant momento, String url, Instant validoAte) {

    @Override
    public String toString() {
        return "AnexoLido[ordem=" + ordem + ", categoria=" + categoria + ", pagina=" + pagina + ", mensagemOrdem="
                + mensagemOrdem + ", validoAte=" + validoAte + "]";
    }
}
