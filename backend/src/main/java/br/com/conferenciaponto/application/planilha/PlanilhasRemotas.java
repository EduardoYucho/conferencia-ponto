package br.com.conferenciaponto.application.planilha;

import java.util.Optional;
import java.util.Set;

/**
 * Porta de saída: o serviço de planilhas on-line (Google Sheets) em que a conferência é publicada. O sistema
 * entra com uma conta própria (conta de serviço); cada pessoa compartilha a planilha dela com essa conta.
 */
public interface PlanilhasRemotas {

    /**
     * Conta com que o sistema acessa as planilhas.
     *
     * @param email   com quem cada pessoa compartilha a planilha (como Editor)
     * @param projeto projeto do Google Cloud em que a conta foi criada
     */
    record Conta(String email, String projeto) {
    }

    /** A conta configurada pelo administrador (vazia = integração desligada). */
    Optional<Conta> conta();

    /**
     * Grava a chave da conta (o arquivo JSON baixado do Google Cloud) depois de conferir que o Google a aceita.
     *
     * @throws PlanilhaRemotaException chave inválida ou recusada
     */
    Conta configurar(String chaveJson);

    /** Apaga a chave: as planilhas deixam de ser atualizadas. */
    void desconfigurar();

    /**
     * Confere que a conta enxerga a planilha.
     *
     * @return o título da planilha
     * @throws PlanilhaRemotaException não compartilhada, inexistente, API desativada...
     */
    String verificar(String planilhaId);

    /**
     * Grava as abas na planilha, criando as que faltam. As abas da planilha que não são do sistema ficam como
     * estão.
     *
     * @param abas ids das abas a gravar; {@code null} = todas, removendo também as abas de meses que não existem
     *             mais
     * @return o título da planilha
     */
    String publicar(String planilhaId, PlanilhaConferencia planilha, Set<Integer> abas);
}
