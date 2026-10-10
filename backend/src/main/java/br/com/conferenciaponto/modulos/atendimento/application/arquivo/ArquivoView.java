package br.com.conferenciaponto.modulos.atendimento.application.arquivo;

import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;

import java.time.Instant;
import java.util.UUID;

/** Um arquivo do atendimento como a tela mostra (sem caminho nem link). */
public record ArquivoView(UUID id, String origem, int ordem, String nome, String categoria, String situacao, Long tamanho,
                          Instant momento, String erro) {

    public static ArquivoView de(ArquivoDoAtendimento a) {
        return new ArquivoView(a.id(), a.origem(), a.ordem(), a.nome(), a.categoria().codigo(), a.situacao(), a.tamanho(),
                a.momento(), a.erroMensagem());
    }
}
