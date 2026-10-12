package br.com.conferenciaponto.modulos.atendimento.application.processamento;

import br.com.conferenciaponto.modulos.atendimento.application.analise.EstadoDaAnalise;
import br.com.conferenciaponto.modulos.atendimento.application.analise.PanoramaDasAnalises;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.EstadoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.EstadoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.SituacaoDoArquivo;

import java.util.List;
import java.util.UUID;

/**
 * O acompanhamento ao vivo (também é o evento {@code atendimento-progresso} do SSE do gerador).
 *
 * @param motivoPausa    por que o atendimento está pausado (ex.: disco cheio, cota diária do Gemini)
 * @param percentual     downloads e análises resolvidos sobre o total
 * @param tokensEntrada  tokens do Gemini gastos nas análises e nas redações deste atendimento
 * @param semChaveGemini há arquivos para analisar e a pessoa não cadastrou a chave do Gemini
 * @param saidas         a versão mais recente de cada texto pedido (resumo...), com a situação da redação
 */
public record ProgressoView(UUID atendimentoId, String situacao, String motivoPausa, int percentual, List<Arquivo> arquivos,
                            long tokensEntrada, long tokensSaida, boolean semChaveGemini, List<EstadoDaSaida> saidas) {

    public ProgressoView {
        saidas = saidas == null ? List.of() : List.copyOf(saidas);
    }

    public ProgressoView(UUID atendimentoId, String situacao, String motivoPausa, int percentual, List<Arquivo> arquivos,
                         long tokensEntrada, long tokensSaida, boolean semChaveGemini) {
        this(atendimentoId, situacao, motivoPausa, percentual, arquivos, tokensEntrada, tokensSaida, semChaveGemini, List.of());
    }

    /**
     * Um arquivo: a situação do download e, se falhou, o motivo; e a análise.
     *
     * @param analise       pendente, na_fila, analisando, pronta ou falhou; null quando o arquivo não tem análise
     *                      (vídeo, tipo não analisado, ainda não baixado)
     * @param analiseCodigo o código do erro da análise (ex.: SEM_CHAVE_GEMINI), ou null
     * @param analiseErro   o motivo para a pessoa ler, ou null
     */
    public record Arquivo(UUID id, String situacao, String erro, Long tamanho, String analise, String analiseCodigo,
                          String analiseErro) {

        public Arquivo(UUID id, String situacao, String erro, Long tamanho) {
            this(id, situacao, erro, tamanho, null, null, null);
        }
    }

    public ProgressoView(UUID atendimentoId, String situacao, String motivoPausa, int percentual, List<Arquivo> arquivos) {
        this(atendimentoId, situacao, motivoPausa, percentual, arquivos, 0, 0, false);
    }

    public static ProgressoView de(EstadoDoAtendimento estado, List<ArquivoDoAtendimento> arquivos) {
        return de(estado, arquivos, PanoramaDasAnalises.VAZIO);
    }

    public static ProgressoView de(EstadoDoAtendimento estado, List<ArquivoDoAtendimento> arquivos,
                                   PanoramaDasAnalises analises) {
        return de(estado, arquivos, analises, List.of(), new long[]{0, 0});
    }

    /** @param tokensDaRedacao [entrada, saída] gastos nas redações */
    public static ProgressoView de(EstadoDoAtendimento estado, List<ArquivoDoAtendimento> arquivos,
                                   PanoramaDasAnalises analises, List<EstadoDaSaida> saidas, long[] tokensDaRedacao) {
        return new ProgressoView(estado.id(), estado.situacao().codigo(), estado.motivoPausa(), percentual(arquivos, analises),
                arquivos.stream().map(a -> arquivo(a, analises.de(a.id()))).toList(),
                analises.tokensEntrada() + tokensDaRedacao[0], analises.tokensSaida() + tokensDaRedacao[1],
                analises.semChaveGemini(), saidas);
    }

    /** Downloads (anexos resolvidos) e análises (prontas ou que falharam de vez) sobre o total; 100 sem nada a fazer. */
    public static int percentual(List<ArquivoDoAtendimento> arquivos, PanoramaDasAnalises analises) {
        List<ArquivoDoAtendimento> anexos = arquivos.stream()
                .filter(ArquivoDoAtendimento::daConversa)
                .filter(a -> !SituacaoDoArquivo.REMOVIDO.codigo().equals(a.situacao()))
                .toList();
        long baixados = anexos.stream().filter(a -> SituacaoDoArquivo.doCodigo(a.situacao()).terminada()).count();
        long total = anexos.size() + analises.esperadas();
        if (total == 0) {
            return 100;
        }
        return (int) ((baixados + analises.resolvidas()) * 100 / total);
    }

    private static Arquivo arquivo(ArquivoDoAtendimento a, EstadoDaAnalise analise) {
        return new Arquivo(a.id(), a.situacao(), a.erroMensagem(), a.tamanho(),
                analise == null ? null : analise.situacao().codigo(), analise == null ? null : analise.codigo(),
                analise == null ? null : analise.mensagem());
    }
}
