package br.com.conferenciaponto.modulos.atendimento.application.processamento;

import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Monta o progresso de um atendimento e o publica para o SSE do gerador (que entrega só ao dono). */
@Component
public class PublicadorDeProgresso {

    private static final Logger log = LoggerFactory.getLogger(PublicadorDeProgresso.class);

    private final Atendimentos atendimentos;
    private final Arquivos arquivos;
    private final ApplicationEventPublisher eventos;

    public PublicadorDeProgresso(Atendimentos atendimentos, Arquivos arquivos, ApplicationEventPublisher eventos) {
        this.atendimentos = atendimentos;
        this.arquivos = arquivos;
        this.eventos = eventos;
    }

    public Optional<ProgressoView> progresso(UUID atendimentoId) {
        return atendimentos.estado(atendimentoId).map(e -> ProgressoView.de(e, arquivos.doAtendimento(atendimentoId)));
    }

    /** Nunca falha: o aviso à tela não pode atrapalhar o processamento. */
    public void publicar(UUID atendimentoId) {
        try {
            atendimentos.estado(atendimentoId).ifPresent(estado -> eventos.publishEvent(new ProgressoAtualizado(
                    estado.usuarioId(), ProgressoView.de(estado, arquivos.doAtendimento(atendimentoId)))));
        } catch (RuntimeException e) {
            log.warn("Não foi possível avisar o progresso do atendimento {}: {}", atendimentoId, e.toString());
        }
    }
}
