package br.com.receptorbackup.coleta;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import br.com.receptorbackup.coleta.Coleta.Conferencia;
import br.com.receptorbackup.coleta.Coleta.Resumo;

/** O que a página de coleta manda e recebe. */
public final class Pedidos {

    private Pedidos() {
    }

    /**
     * Começo (ou recomeço) da leitura. {@code geracao} diferente da atual descarta os pedaços que chegaram fora de
     * ordem. {@code voltarAoInicioDe}: a leitura parou no meio de um arquivo que mudou desde então; o Receptor volta o
     * backup para logo antes desse arquivo, que será lido de novo inteiro.
     */
    public record Geracao(String geracao, String raiz, List<String> pastas, String computador, String navegador,
            long totalArquivos, long totalBytes, String voltarAoInicioDe) {
    }

    public record Progresso(String fase, long lidoBytes, long lidoArquivos, long compactadoBytes, long bufferBytes,
            String arquivoAtual) {
    }

    public record Conclusao(String geracao, long totalPartes) {
    }

    /** Resposta da página: o que mostrar e de onde continuar. */
    public record Info(String cliente, String pastaSistema, List<String> selecaoAnterior, String situacao,
            String mensagem, int nivelZstd, long tamanhoBloco, long maxParte, String geracao, long proximoSeq,
            JsonNode cursor, long bytesRecebidos, Resumo resumo, Conferencia conferencia, String nomeArquivo,
            String raiz) {
    }

    public record ParteAceita(long proximoSeq, long bytesRecebidos) {
    }
}
