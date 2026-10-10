package br.com.conferenciaponto.modulos.atendimento.application.arquivo;

import br.com.conferenciaponto.modulos.atendimento.application.analise.EstadoDaAnalise;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;

import java.time.Instant;
import java.util.UUID;

/**
 * Um arquivo do atendimento como a tela mostra (sem caminho nem link).
 *
 * @param analise pendente, na_fila, analisando, pronta ou falhou; null quando o arquivo não tem análise
 */
public record ArquivoView(UUID id, String origem, int ordem, String nome, String categoria, String situacao, Long tamanho,
                          Instant momento, String erro, String analise, String analiseCodigo, String analiseErro) {

    public ArquivoView(UUID id, String origem, int ordem, String nome, String categoria, String situacao, Long tamanho,
                       Instant momento, String erro) {
        this(id, origem, ordem, nome, categoria, situacao, tamanho, momento, erro, null, null, null);
    }

    public static ArquivoView de(ArquivoDoAtendimento a) {
        return de(a, null);
    }

    public static ArquivoView de(ArquivoDoAtendimento a, EstadoDaAnalise analise) {
        return new ArquivoView(a.id(), a.origem(), a.ordem(), a.nome(), a.categoria().codigo(), a.situacao(), a.tamanho(),
                a.momento(), a.erroMensagem(), analise == null ? null : analise.situacao().codigo(),
                analise == null ? null : analise.codigo(), analise == null ? null : analise.mensagem());
    }
}
