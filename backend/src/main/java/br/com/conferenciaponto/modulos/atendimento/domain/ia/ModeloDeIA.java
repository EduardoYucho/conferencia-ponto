package br.com.conferenciaponto.modulos.atendimento.domain.ia;

import java.nio.file.Path;

/**
 * O modelo de IA (o Gemini), com a chave do dono do atendimento em cada chamada. Arquivos grandes vão antes para o
 * armazenamento temporário do modelo ({@link #enviar}), que os apaga sozinho depois de 48 h; quem envia apaga
 * assim que termina.
 *
 * @throws FalhaDoModelo em toda chamada que não deu certo, já traduzida para o que fazer
 */
public interface ModeloDeIA {

    /** Envia um arquivo (envio retomável: se a conexão cair, continua de onde parou). */
    ArquivoRemoto enviar(String chave, Path arquivo, String tipoDeMidia, long tamanho, String nomeParaExibir);

    /** A situação atual de um arquivo enviado (o modelo processa vídeo e áudio antes de deixar usar). */
    ArquivoRemoto consultar(String chave, String nome);

    /** Apaga o arquivo enviado (não falha se ele já não existe). */
    void apagar(String chave, String nome);

    /** Uma geração com resposta em JSON no esquema pedido. */
    RespostaDoModelo gerar(String chave, PedidoAoModelo pedido);

    /**
     * O vetor (embedding) de um texto, com {@code dimensao} valores, normalizado (tamanho 1): a comparação vira o
     * produto escalar.
     */
    float[] vetor(String chave, String modelo, int dimensao, String texto);
}
