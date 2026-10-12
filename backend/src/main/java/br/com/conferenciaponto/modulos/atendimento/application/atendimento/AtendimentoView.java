package br.com.conferenciaponto.modulos.atendimento.application.atendimento;

import br.com.conferenciaponto.modulos.atendimento.application.arquivo.ArquivoView;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.EstadoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.CamposInformados;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Omitidos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tela de detalhe: a conversa lida (já sem a chave do bot e dados de acesso remoto), os anexos e os arquivos
 * enviados pela pessoa (ligações, vídeo e prints).
 *
 * @param motivoPausa        por que está pausado (disco cheio, cota do Gemini), ou null
 * @param percentual         downloads e análises resolvidos sobre o total
 * @param arquivosApagadosEm quando a retenção tirou os arquivos do disco (null enquanto estão lá)
 * @param tokensEntrada      tokens do Gemini gastos nas análises deste atendimento
 * @param semChaveGemini     há arquivos para analisar e a pessoa não cadastrou a chave do Gemini
 */
public record AtendimentoView(UUID id, String chamado, String contato, Instant inicio, Instant fim, String situacao,
                              String motivoPausa, int percentual, Instant criadoEm, Instant apagarArquivosEm,
                              Instant arquivosApagadosEm, String assunto, String resumo, Instant linksValidosAte,
                              boolean linksVencidos, Omitidos omitidos, int versaoLeitor, List<Item> itens,
                              List<Anexo> anexos, List<ArquivoView> extras, long tokensEntrada, long tokensSaida,
                              boolean semChaveGemini, List<EstadoDaSaida> saidas, CamposInformados campos) {

    public AtendimentoView {
        saidas = saidas == null ? List.of() : List.copyOf(saidas);
        campos = campos == null ? CamposInformados.VAZIO : campos;
    }

    public AtendimentoView(UUID id, String chamado, String contato, Instant inicio, Instant fim, String situacao,
                           String motivoPausa, int percentual, Instant criadoEm, Instant apagarArquivosEm,
                           Instant arquivosApagadosEm, String assunto, String resumo, Instant linksValidosAte,
                           boolean linksVencidos, Omitidos omitidos, int versaoLeitor, List<Item> itens,
                           List<Anexo> anexos, List<ArquivoView> extras, long tokensEntrada, long tokensSaida,
                           boolean semChaveGemini, List<EstadoDaSaida> saidas) {
        this(id, chamado, contato, inicio, fim, situacao, motivoPausa, percentual, criadoEm, apagarArquivosEm,
                arquivosApagadosEm, assunto, resumo, linksValidosAte, linksVencidos, omitidos, versaoLeitor, itens, anexos,
                extras, tokensEntrada, tokensSaida, semChaveGemini, saidas, CamposInformados.VAZIO);
    }

    public AtendimentoView(UUID id, String chamado, String contato, Instant inicio, Instant fim, String situacao,
                           String motivoPausa, int percentual, Instant criadoEm, Instant apagarArquivosEm,
                           Instant arquivosApagadosEm, String assunto, String resumo, Instant linksValidosAte,
                           boolean linksVencidos, Omitidos omitidos, int versaoLeitor, List<Item> itens,
                           List<Anexo> anexos, List<ArquivoView> extras, long tokensEntrada, long tokensSaida,
                           boolean semChaveGemini) {
        this(id, chamado, contato, inicio, fim, situacao, motivoPausa, percentual, criadoEm, apagarArquivosEm,
                arquivosApagadosEm, assunto, resumo, linksValidosAte, linksVencidos, omitidos, versaoLeitor, itens, anexos,
                extras, tokensEntrada, tokensSaida, semChaveGemini, List.of());
    }

    public AtendimentoView(UUID id, String chamado, String contato, Instant inicio, Instant fim, String situacao,
                           String motivoPausa, int percentual, Instant criadoEm, Instant apagarArquivosEm,
                           Instant arquivosApagadosEm, String assunto, String resumo, Instant linksValidosAte,
                           boolean linksVencidos, Omitidos omitidos, int versaoLeitor, List<Item> itens,
                           List<Anexo> anexos, List<ArquivoView> extras) {
        this(id, chamado, contato, inicio, fim, situacao, motivoPausa, percentual, criadoEm, apagarArquivosEm,
                arquivosApagadosEm, assunto, resumo, linksValidosAte, linksVencidos, omitidos, versaoLeitor, itens, anexos,
                extras, 0, 0, false);
    }

    /** Mensagem ou evento. */
    public record Item(int ordem, String tipo, String lado, String remetente, Instant momento, String texto,
                       List<Integer> anexos) {
    }

    /**
     * Anexo da conversa (o link em si nunca vem para a tela).
     *
     * @param analise pendente, na_fila, analisando, pronta ou falhou; null quando o arquivo não tem análise
     */
    public record Anexo(UUID id, int ordem, String nome, String categoria, String situacao, Instant validoAte,
                        boolean vencido, Integer mensagemOrdem, Instant momento, Long tamanho, String erro, String analise,
                        String analiseCodigo, String analiseErro) {

        public Anexo(UUID id, int ordem, String nome, String categoria, String situacao, Instant validoAte,
                     boolean vencido, Integer mensagemOrdem, Instant momento, Long tamanho, String erro) {
            this(id, ordem, nome, categoria, situacao, validoAte, vencido, mensagemOrdem, momento, tamanho, erro, null, null,
                    null);
        }
    }
}
