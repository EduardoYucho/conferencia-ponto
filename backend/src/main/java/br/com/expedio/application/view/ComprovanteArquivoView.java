package br.com.expedio.application.view;

import br.com.expedio.domain.model.ComprovanteArquivado;

import java.net.URI;

/** Comprovante arquivado + URI de download. */
public record ComprovanteArquivoView(ComprovanteArquivado comprovante, URI uriDownload) {
}
