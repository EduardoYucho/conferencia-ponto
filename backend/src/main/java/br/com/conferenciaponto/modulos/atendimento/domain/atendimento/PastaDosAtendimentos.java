package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Os arquivos dos atendimentos em disco: uma pasta por atendimento, dentro da pasta do módulo. Nenhum nome vindo
 * de fora vira caminho sem ser saneado, e todo caminho é conferido para ficar dentro da pasta.
 */
public interface PastaDosAtendimentos {

    /**
     * Grava o que chega num arquivo temporário (na mesma pasta, para a mudança final ser atômica).
     *
     * @param limite em bytes; passando dele, o arquivo é apagado e vem {@code LeituraDoPdfException} PDF_GRANDE_DEMAIS
     */
    Path receber(InputStream conteudo, long limite);

    /** Move o PDF recebido para a pasta do atendimento, como conversa.pdf (atômico: ou está inteiro, ou não está). */
    void guardarPdf(UUID atendimentoId, Path recebido);

    /** Apaga um arquivo recebido que não foi usado (não falha se ele já não existe). */
    void descartar(Path recebido);

    /** Apaga a pasta do atendimento inteira. */
    void apagar(UUID atendimentoId);
}
