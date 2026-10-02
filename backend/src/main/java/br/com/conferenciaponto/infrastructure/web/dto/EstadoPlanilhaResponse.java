package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.view.EstadoPlanilhaView;
import br.com.conferenciaponto.domain.model.VinculoPlanilha;

import java.time.Instant;

/**
 * Situação da planilha do Google de um usuário.
 *
 * @param situacao     SEM_INTEGRACAO, SEM_PLANILHA, PENDENTE, SINCRONIZADA ou ERRO
 * @param emailServico com quem compartilhar a planilha, como Editor (nulo = integração não configurada)
 * @param url          link da planilha (nulo = nenhuma vinculada)
 */
public record EstadoPlanilhaResponse(String situacao, String emailServico, String url, String titulo,
                                     Instant sincronizadaEm, String erro, Instant vinculadaEm, String vinculadaPor) {

    public static EstadoPlanilhaResponse de(EstadoPlanilhaView v) {
        VinculoPlanilha p = v.vinculo();
        return new EstadoPlanilhaResponse(v.situacao().name(), v.emailServico(),
                p == null ? null : p.url(), p == null ? null : p.titulo(),
                p == null ? null : p.sincronizadaEm(), p == null ? null : p.erro(),
                p == null ? null : p.vinculadaEm(), p == null ? null : p.vinculadaPor());
    }
}
