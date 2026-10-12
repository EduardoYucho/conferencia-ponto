package br.com.conferenciaponto.modulos.conhecimento.domain.vetor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Os vetores dos registros (conhecimento.vetor). Um registro sem vetor do modelo e do tamanho atuais está pendente:
 * aparece só na pesquisa exata até o vetor ser gerado.
 */
public interface Vetores {

    /** Todos os vetores do modelo e tamanho, para o índice em memória. */
    List<Guardado> doModelo(String modelo, int dimensao);

    /**
     * Os registros pendentes, do mais antigo para o mais novo, com o que vai no texto do vetor.
     *
     * @param limite quantos no máximo
     */
    List<Pendente> pendentes(String modelo, int dimensao, int limite);

    long contarPendentes(String modelo, int dimensao);

    /** Grava (ou troca) o vetor do registro e apaga os de outros modelos (troca de modelo = recalcular). */
    void salvar(UUID registroId, String modelo, int dimensao, float[] valores, String textoSha256, Instant agora);

    /**
     * O texto do registro mudou: apaga os vetores feitos com outro texto.
     *
     * @return se algum vetor saiu
     */
    boolean invalidarSeMudou(UUID registroId, String textoSha256);

    record Guardado(UUID registroId, float[] valores) {
    }

    /** Um registro sem vetor, com o autor (a chave dele paga o vetor) e os campos do texto. */
    record Pendente(UUID registroId, UUID autorId, String titulo, String sintoma, String mensagemErro, String causa,
                    String resolucao) {

        public String texto() {
            return TextoDoVetor.documento(titulo, sintoma, mensagemErro, causa, resolucao);
        }
    }
}
