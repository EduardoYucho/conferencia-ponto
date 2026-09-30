package br.com.conferenciaponto.domain.port;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Porta de saída: onde os PDFs ficam guardados (sistema de arquivos local hoje;
 * um adapter para MinIO/S3 pode substituir esta implementação sem tocar no domínio).
 */
public interface ArmazenamentoComprovantes {

    /**
     * Grava o conteúdo como {@code comprovante_<id>.pdf}.
     *
     * @return caminho relativo à raiz do armazenamento (o que vai para tb_comprovante.caminho_arquivo)
     */
    String armazenar(UUID id, byte[] conteudo, LocalDate dataReferencia) throws IOException;

    byte[] ler(String caminhoArquivo) throws IOException;

    /** URI de acesso (download) do comprovante. */
    URI uriDeAcesso(UUID id);
}
