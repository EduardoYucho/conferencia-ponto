package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import java.util.List;

/**
 * O que o leitor precisa de um PDF: o título, a largura da página, o texto com a posição e os links. Quem tira
 * isso do arquivo é o {@link ExtratorDePdf} (PDFBox, na infraestrutura); o {@link LeitorConversaDigisac} só
 * interpreta.
 */
public record PdfPosicionado(String titulo, int paginas, float largura, List<Trecho> trechos, List<LinkDoPdf> links) {

    public PdfPosicionado {
        trechos = List.copyOf(trechos);
        links = List.copyOf(links);
    }
}
