package br.com.conferenciaponto.modulos.atendimento.application.atendimento;

import br.com.conferenciaponto.modulos.atendimento.application.arquivo.ArquivoView;
import br.com.conferenciaponto.modulos.atendimento.domain.conversa.Omitidos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tela de detalhe: a conversa lida (já sem a chave do bot e dados de acesso remoto), os anexos e os arquivos
 * enviados pela pessoa (ligações, vídeo e prints).
 *
 * @param motivoPausa        por que está pausado (disco cheio), ou null
 * @param percentual         anexos resolvidos sobre o total
 * @param arquivosApagadosEm quando a retenção tirou os arquivos do disco (null enquanto estão lá)
 */
public record AtendimentoView(UUID id, String chamado, String contato, Instant inicio, Instant fim, String situacao,
                              String motivoPausa, int percentual, Instant criadoEm, Instant apagarArquivosEm,
                              Instant arquivosApagadosEm, String assunto, String resumo, Instant linksValidosAte,
                              boolean linksVencidos, Omitidos omitidos, int versaoLeitor, List<Item> itens,
                              List<Anexo> anexos, List<ArquivoView> extras) {

    /** Mensagem ou evento. */
    public record Item(int ordem, String tipo, String lado, String remetente, Instant momento, String texto,
                       List<Integer> anexos) {
    }

    /** Anexo da conversa (o link em si nunca vem para a tela). */
    public record Anexo(UUID id, int ordem, String nome, String categoria, String situacao, Instant validoAte,
                        boolean vencido, Integer mensagemOrdem, Instant momento, Long tamanho, String erro) {
    }
}
