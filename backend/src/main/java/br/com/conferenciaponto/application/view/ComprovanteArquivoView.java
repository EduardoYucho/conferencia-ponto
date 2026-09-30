package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.ComprovanteArquivado;

import java.net.URI;

/** Comprovante arquivado + URI de download. */
public record ComprovanteArquivoView(ComprovanteArquivado comprovante, URI uriDownload) {
}
