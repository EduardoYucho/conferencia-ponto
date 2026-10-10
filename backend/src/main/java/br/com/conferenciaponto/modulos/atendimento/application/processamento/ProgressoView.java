package br.com.conferenciaponto.modulos.atendimento.application.processamento;

import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ArquivoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.EstadoDoAtendimento;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.ProgressoDoAtendimento;

import java.util.List;
import java.util.UUID;

/**
 * O acompanhamento ao vivo (também é o evento {@code atendimento-progresso} do SSE do gerador).
 *
 * @param motivoPausa por que o atendimento está pausado (ex.: disco cheio)
 * @param percentual  anexos da conversa resolvidos sobre o total
 */
public record ProgressoView(UUID atendimentoId, String situacao, String motivoPausa, int percentual, List<Arquivo> arquivos) {

    /** Um arquivo: a situação e, se falhou, o motivo para a pessoa ler. */
    public record Arquivo(UUID id, String situacao, String erro, Long tamanho) {
    }

    public static ProgressoView de(EstadoDoAtendimento estado, List<ArquivoDoAtendimento> arquivos) {
        ProgressoDoAtendimento progresso = new ProgressoDoAtendimento(estado.id(), estado.usuarioId(), estado.situacao(),
                estado.motivoPausa(), arquivos);
        return new ProgressoView(estado.id(), estado.situacao().codigo(), estado.motivoPausa(), progresso.percentual(),
                arquivos.stream().map(a -> new Arquivo(a.id(), a.situacao(), a.erroMensagem(), a.tamanho())).toList());
    }
}
