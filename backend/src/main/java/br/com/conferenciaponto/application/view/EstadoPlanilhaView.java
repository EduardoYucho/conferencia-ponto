package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.VinculoPlanilha;

/**
 * Situação da planilha do Google de um usuário.
 *
 * @param emailServico com quem a planilha deve ser compartilhada ({@code null} = o administrador ainda não
 *                     configurou a integração)
 * @param vinculo      a planilha do usuário ({@code null} = nenhuma)
 */
public record EstadoPlanilhaView(String emailServico, VinculoPlanilha vinculo) {

    public enum Situacao {
        /** O administrador ainda não configurou a conta de serviço. */
        SEM_INTEGRACAO,
        /** A pessoa ainda não informou a planilha dela. */
        SEM_PLANILHA,
        /** Vinculada, aguardando a primeira gravação. */
        PENDENTE,
        SINCRONIZADA,
        /** A última gravação falhou: ver {@link VinculoPlanilha#erro()}. */
        ERRO
    }

    public Situacao situacao() {
        if (vinculo != null && emailServico != null) {
            return vinculo.erro() != null ? Situacao.ERRO
                    : vinculo.sincronizadaEm() == null ? Situacao.PENDENTE : Situacao.SINCRONIZADA;
        }
        return emailServico == null ? Situacao.SEM_INTEGRACAO : Situacao.SEM_PLANILHA;
    }
}
