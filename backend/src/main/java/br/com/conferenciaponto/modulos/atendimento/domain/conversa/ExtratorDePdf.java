package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import java.nio.file.Path;

/** Tira de um arquivo PDF o texto com a posição e os links. */
public interface ExtratorDePdf {

    /**
     * @throws LeituraDoPdfException PDF_INVALIDO (não é PDF ou está corrompido) ou PDF_PROTEGIDO (pede senha)
     */
    PdfPosicionado extrair(Path pdf);
}
