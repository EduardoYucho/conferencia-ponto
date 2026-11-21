package br.com.expedio.modulos.backup.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Uma coleta: um link, um cliente, um backup que vai para o computador de quem gerou o link. Gravada (só os dados de
 * controle) em {@code <pasta-estado>\coletas\<id>\coleta.json}. O andamento (próximo pedaço, bytes entregues, onde a
 * leitura parou) é refeito a partir de {@code blocos.jsonl}, gravado quando o navegador de quem recebe confirma cada
 * pedaço.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Coleta {

    public enum Situacao {
        /** Link criado, a página do cliente ainda não começou. */
        AGUARDANDO,
        /** Pedaços chegando (e indo para o navegador de quem recebe). */
        RECEBENDO,
        /** Todos os pedaços entregues; o navegador de quem recebe monta o .tar.zst e confere cada arquivo. */
        FECHANDO,
        /** Conferido no computador de quem recebeu: todos os arquivos idênticos aos lidos no cliente. */
        INTEGRA,
        /** Conferido com diferença (veja a conferência). */
        DIVERGENTE,
        CANCELADA;

        public boolean recebendo() {
            return this == AGUARDANDO || this == RECEBENDO;
        }

        public boolean ativa() {
            return recebendo() || this == FECHANDO;
        }

        public boolean terminada() {
            return this == INTEGRA || this == DIVERGENTE;
        }
    }

    public record Progresso(String fase, long lidoBytes, long lidoArquivos, long compactadoBytes, long bufferBytes,
                            String arquivoAtual, Instant atualizadoEm) {
    }

    public record Resumo(long arquivos, long pastas, long bytesOriginais, long alterados, long naoLidos) {
    }

    /** O que o navegador de quem recebeu informou depois de montar e conferir o arquivo. */
    public record Conferencia(String situacao, Instant fim, long arquivosConferidos, long bytesConferidos,
                              List<String> divergentes, List<String> faltando, List<String> sobrando, String mensagem) {
    }

    public String id;
    public String token;
    public String cliente;
    /** Nome do arquivo sem extensão: {@code <Cliente>_<aaaa-mm-dd_hhmmss>}. */
    public String nome;
    public Instant criadaEm;
    /** Quem gerou o link: é o navegador dessa pessoa que recebe (e ela recebe o aviso no sino). */
    public UUID criadaPorId;
    public String criadaPor;
    public Instant expiraEm;
    public Situacao situacao = Situacao.AGUARDANDO;
    public String mensagem;
    /** Identifica uma leitura da página; outra leitura (página recarregada sem a cópia local) descarta o que esperava. */
    public String geracao;
    /**
     * SHA-256 do segredo de sessão do navegador que começou a coleta: só ele continua (um link vazado não serve para
     * mandar nada). Quem gerou o link pode liberar para outro computador.
     */
    public String sessaoHash;
    public String raiz;
    public List<String> pastas = new ArrayList<>();
    public String computador;
    public String navegador;
    public long totalArquivos;
    public long totalBytes;
    public Progresso progresso;
    public Instant inicioEnvio;
    public Instant ultimoContato;
    /** Quando a página concluiu (todos os pedaços entregues); entra no manifesto. */
    public Instant concluidaEm;
    /** Quando quem recebeu terminou de conferir. */
    public Instant conferidaEm;
    /** Tamanho do .tar.zst no computador de quem recebeu. */
    public long tamanhoArquivo;
    public Resumo resumo;
    public Conferencia conferencia;
    /** Arquivos que mudaram enquanto eram lidos no cliente (sistema em uso). */
    public List<String> alterados = new ArrayList<>();
    /** Arquivos que não puderam ser lidos, com o motivo. */
    public List<String> naoLidos = new ArrayList<>();

    // ---- refeito a partir de blocos.jsonl (não vai para o coleta.json)
    @JsonIgnore
    public long proximoSeq;
    /** Bytes compactados já gravados no computador de quem recebe. */
    @JsonIgnore
    public long bytesArquivo;
    @JsonIgnore
    public JsonNode cursor;
    @JsonIgnore
    public long brutoRecebido;
    @JsonIgnore
    public long arquivosRecebidos;
}
