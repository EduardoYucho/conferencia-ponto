package br.com.expedio.application.view;

import br.com.expedio.domain.model.ComprovanteArquivado;

/** Conteúdo de um comprovante pronto para download (integridade já conferida). */
public record ArquivoComprovanteView(ComprovanteArquivado comprovante, byte[] conteudo) {
}
