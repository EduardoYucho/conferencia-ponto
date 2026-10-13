package br.com.conferenciaponto.modulos.atendimento.domain.pacote;

import java.nio.file.Path;
import java.util.List;

/**
 * Junta as imagens e os PDFs do atendimento num PDF só, cada item com o rótulo no alto da primeira página (o
 * identificador do material, como A3, para o Gemini ligar a página à conversa). O app do Gemini aceita até 10 arquivos
 * por pedido: um atendimento com muitas imagens cabe num arquivo.
 */
public interface JuntadorDePdf {

    /**
     * Um item do PDF.
     *
     * @param rotulo  o texto no alto da página (ex.: "A1 · imagem enviada pelo cliente às 10:41 · foto.jpeg")
     * @param arquivo a imagem (JPEG, PNG, GIF ou BMP) ou o PDF
     * @param pdf     é um PDF (as páginas entram depois de uma página com o rótulo)
     */
    record Item(String rotulo, Path arquivo, boolean pdf) {
    }

    /**
     * @param paginas a primeira página de cada item, na ordem pedida (contando de 1), ou 0 se ele não entrou (o
     *                arquivo não abriu)
     * @param total   quantas páginas o PDF tem
     */
    record Resultado(List<Integer> paginas, int total) {
    }

    /** Grava o PDF em {@code destino} (sem nenhum item aberto, não grava nada e o total é 0). */
    Resultado juntar(List<Item> itens, Path destino);
}
