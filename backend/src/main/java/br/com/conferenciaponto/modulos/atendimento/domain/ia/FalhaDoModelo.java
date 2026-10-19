package br.com.conferenciaponto.modulos.atendimento.domain.ia;

import java.time.Duration;
import java.util.Objects;

/**
 * Uma chamada ao modelo que não deu certo, já classificada pelo que fazer. A mensagem nunca leva a chave nem o
 * conteúdo enviado.
 */
public class FalhaDoModelo extends RuntimeException {

    public enum Tipo {
        /** A chave foi recusada (inválida, apagada, sem a API ativada, com restrição). */
        CHAVE_RECUSADA,
        /** Limite por minuto: esperar o tempo pedido e tentar de novo. */
        LIMITE,
        /** A cota do dia acabou: só volta depois que ela renovar. */
        COTA_DIARIA,
        /** Os créditos pré-pagos acabaram (HTTP 402). */
        SEM_CREDITO,
        /** Erro do lado do Google ou resposta que não chegou a tempo. */
        INDISPONIVEL,
        /** O servidor não conseguiu falar com o Google. */
        SEM_INTERNET,
        /** O modelo se recusou a responder (segurança, recitação...). */
        BLOQUEOU,
        /** A resposta veio vazia, cortada ou fora do JSON pedido. */
        RESPOSTA_INVALIDA,
        /** O modelo da configuração não existe ou não está liberado para a chave. */
        MODELO_INEXISTENTE,
        /** O Google não aceitou o pedido (arquivo, tipo ou parâmetro). */
        PEDIDO_RECUSADO,
        /** O Google não conseguiu processar o arquivo enviado. */
        ARQUIVO_FALHOU,
        /** IA local: o programa que roda o modelo neste computador (o Ollama) não respondeu. */
        IA_LOCAL_PARADA,
        /** IA local: a placa de vídeo está ocupada (um jogo aberto) e não sobra memória para o modelo. */
        PLACA_OCUPADA
    }

    private final Tipo tipo;
    private final Duration esperar;
    private final int status;

    public FalhaDoModelo(Tipo tipo, String mensagem, Duration esperar, int status) {
        super(mensagem);
        this.tipo = Objects.requireNonNull(tipo, "tipo");
        this.esperar = esperar;
        this.status = status;
    }

    public FalhaDoModelo(Tipo tipo, String mensagem) {
        this(tipo, mensagem, null, 0);
    }

    public Tipo tipo() {
        return tipo;
    }

    /** O tempo que o Google pediu para esperar (só no limite por minuto), ou null. */
    public Duration esperar() {
        return esperar;
    }

    /** O código HTTP da resposta (0 quando não houve resposta). */
    public int status() {
        return status;
    }
}
