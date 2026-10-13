package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

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

    /** Como {@link #receber(InputStream, long)}, com a exceção que a pessoa deve ver quando passar do limite. */
    Path receber(InputStream conteudo, long limite, Supplier<? extends RuntimeException> seExceder);

    /** Move o PDF recebido para a pasta do atendimento, como conversa.pdf (atômico: ou está inteiro, ou não está). */
    void guardarPdf(UUID atendimentoId, Path recebido);

    /** Apaga um arquivo recebido que não foi usado (não falha se ele já não existe). */
    void descartar(Path recebido);

    /** Apaga a pasta do atendimento inteira. */
    void apagar(UUID atendimentoId);

    /** Onde fica o download em andamento de um anexo (continua daí se a conexão cair). */
    Path parcialDoAnexo(UUID atendimentoId, UUID arquivoId);

    /**
     * Move um arquivo recebido ou baixado para {@code <id>/arquivos/<prefixo>_<ordem>_<nome saneado>} (atômico).
     *
     * @return o caminho relativo à pasta dos atendimentos, que vai para o banco
     */
    String guardarArquivo(UUID atendimentoId, Path origem, String prefixo, int ordem, String nomeOriginal);

    /** Onde está, no disco, um arquivo pelo caminho relativo guardado no banco (sempre dentro da pasta). */
    Path localDoArquivo(String caminhoRelativo);

    /** Apaga um arquivo pelo caminho relativo guardado no banco (não falha se ele já não existe). */
    void apagarArquivo(String caminhoRelativo);

    /**
     * Onde fica o quadro de um vídeo num instante: {@code <id>/arquivos/quadros/<arquivo>/<instante>.png} (o caminho
     * relativo, como os outros que vão para o banco). O arquivo existe só depois que o quadro foi tirado.
     */
    String caminhoDoQuadro(UUID atendimentoId, UUID arquivoId, long instanteMs);

    /**
     * Onde fica a cópia marcada (em vermelho) de uma imagem de um texto: junto dos arquivos do atendimento, para a
     * retenção apagar com eles. Relativo à pasta dos atendimentos.
     */
    String caminhoDaMarcada(UUID atendimentoId, UUID imagemId);

    /**
     * Um arquivo do pedido ao app do Gemini ({@code <atendimento>/arquivos/app-gemini/<nome>}): a retenção apaga junto
     * com os outros arquivos.
     */
    String caminhoNoPedidoDoApp(UUID atendimentoId, String nome);

    /** Apaga os arquivos do último pedido ao app do Gemini (o próximo pedido monta de novo). */
    void apagarPedidoDoApp(UUID atendimentoId);

    /** Apaga os quadros tirados de um vídeo (o vídeo saiu do atendimento). */
    void apagarQuadros(UUID atendimentoId, UUID arquivoId);

    /** Apaga o PDF e os arquivos do atendimento, mantendo a pasta (retenção). */
    void apagarArquivosDoAtendimento(UUID atendimentoId);

    /** Espaço livre no disco da pasta, em bytes. */
    long espacoLivre();

    /** Quanto a pasta dos atendimentos ocupa, em bytes (sem a base de conhecimento e a chave mestra). */
    long espacoOcupado();
}
