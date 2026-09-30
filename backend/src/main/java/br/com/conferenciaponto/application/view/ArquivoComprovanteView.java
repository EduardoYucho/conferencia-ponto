package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.ComprovanteArquivado;

/** Conteúdo de um comprovante pronto para download (integridade já conferida). */
public record ArquivoComprovanteView(ComprovanteArquivado comprovante, byte[] conteudo) {
}
