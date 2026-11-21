package br.com.expedio.modulos.backup.domain;

import br.com.expedio.modulos.backup.domain.Coleta.Conferencia;
import br.com.expedio.modulos.backup.domain.Coleta.Resumo;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;


/** O que a página de coleta manda e recebe. */
public final class Pedidos {

    private Pedidos() {
    }

    /**
     * Começo (ou recomeço) da leitura. {@code geracao} diferente da atual descarta os pedaços que chegaram fora de
     * ordem. {@code voltarAoInicioDe}: a leitura parou no meio de um arquivo que mudou desde então; o sistema volta o
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

    /**
     * Resposta da página: o que mostrar e de onde continuar. {@code receptorOnline}: o navegador de quem gerou o link
     * está com a tela aberta (sem ele, os pedaços esperam no navegador do cliente).
     */
    public record Info(String cliente, String pastaSistema, List<String> selecaoAnterior, String situacao,
            String mensagem, int nivelZstd, long tamanhoBloco, long maxParte, String geracao, long proximoSeq,
            JsonNode cursor, long bytesRecebidos, Resumo resumo, Conferencia conferencia, String nomeArquivo,
            String raiz, boolean receptorOnline, String recebidoPor, boolean emOutroComputador) {
    }

    /** O pedaço foi gravado no computador de quem recebe. */
    public record ParteAceita(long proximoSeq, long bytesRecebidos) {
    }

    /** Um pedaço para o navegador de quem recebe gravar. */
    public record Entrega(long seq, byte[] quadro, String sha256) {
    }

    /**
     * O que o navegador de quem recebe informa depois de montar o .tar.zst e conferir cada arquivo (SHA-256 contra o
     * manifesto).
     */
    public record Resultado(boolean integra, long arquivosConferidos, long bytesConferidos, long tamanhoArquivo,
            List<String> divergentes, List<String> faltando, List<String> sobrando, String mensagem) {
    }
}
