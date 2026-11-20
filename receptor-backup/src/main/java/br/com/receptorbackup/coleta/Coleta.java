package br.com.receptorbackup.coleta;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Uma coleta: um link, um cliente, um arquivo .tar.zst. Gravada em {@code .receptor\coletas\<id>\coleta.json}.
 * O andamento do recebimento (próximo pedaço, bytes gravados, onde a leitura parou) não fica aqui: é refeito a
 * partir de {@code blocos.jsonl} ao carregar, que é gravado depois de cada pedaço entrar no arquivo.
 */
public class Coleta {

    public enum Situacao {
        /** Link criado, a página ainda não começou. */
        AGUARDANDO,
        /** Recebendo pedaços. */
        RECEBENDO,
        /** Todos os pedaços chegaram; fechando o arquivo com o manifesto. */
        MONTANDO,
        /** Arquivo pronto; conferindo o SHA-256 de cada arquivo. */
        CONFERINDO,
        /** Conferido: todos os arquivos idênticos aos lidos no cliente. */
        INTEGRA,
        /** Conferido com diferença (veja a conferência). */
        DIVERGENTE,
        FALHOU,
        CANCELADA;

        public boolean recebendo() {
            return this == AGUARDANDO || this == RECEBENDO;
        }

        public boolean temArquivo() {
            return this == CONFERINDO || this == INTEGRA || this == DIVERGENTE;
        }
    }

    public record Progresso(String fase, long lidoBytes, long lidoArquivos, long compactadoBytes, long bufferBytes,
            String arquivoAtual, Instant atualizadoEm) {
    }

    public record Resumo(long arquivos, long pastas, long bytesOriginais, long alterados, long naoLidos) {
    }

    public record Conferencia(String situacao, Instant inicio, Instant fim, long arquivosConferidos, long bytesConferidos,
            List<String> divergentes, List<String> faltando, List<String> sobrando, String mensagem) {
    }

    public record Extracao(String situacao, String destino, long bytes, long total, Instant inicio, Instant fim,
            List<String> ignorados, String mensagem) {
    }

    public String id;
    public String token;
    public String cliente;
    /** Nome da pasta do cliente dentro da pasta dos backups. */
    public String pastaCliente;
    /** Nome do arquivo sem extensão: {@code <Cliente>_<aaaa-mm-dd_hhmmss>}. */
    public String nome;
    public Instant criadaEm;
    public Instant expiraEm;
    public Situacao situacao = Situacao.AGUARDANDO;
    public String mensagem;
    /** Identifica uma leitura da página; outra leitura (página recarregada sem a cópia local) descarta o que esperava. */
    public String geracao;
    public String raiz;
    public List<String> pastas = new ArrayList<>();
    public String computador;
    public String navegador;
    public long totalArquivos;
    public long totalBytes;
    public Progresso progresso;
    public Instant inicioEnvio;
    public Instant ultimoContato;
    public Instant concluidaEm;
    public long tamanhoArquivo;
    public Resumo resumo;
    public Conferencia conferencia;
    public Extracao extracao;
    /** Arquivos que mudaram enquanto eram lidos no cliente (sistema em uso). */
    public List<String> alterados = new ArrayList<>();
    /** Arquivos que não puderam ser lidos, com o motivo. */
    public List<String> naoLidos = new ArrayList<>();

    // ---- refeito a partir de blocos.jsonl (não vai para o coleta.json)
    @JsonIgnore
    public long proximoSeq;
    @JsonIgnore
    public long bytesArquivo;
    @JsonIgnore
    public JsonNode cursor;
    @JsonIgnore
    public long brutoRecebido;
    @JsonIgnore
    public long arquivosRecebidos;
}
