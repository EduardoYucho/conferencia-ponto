package br.com.conferenciaponto.modulos.atendimento.application.processamento;

import br.com.conferenciaponto.modulos.atendimento.application.analise.EtapaDeAnalise;
import br.com.conferenciaponto.modulos.atendimento.application.analise.PanoramaDasAnalises;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.EstadoDaSaida;
import br.com.conferenciaponto.modulos.atendimento.application.redacao.EtapaDeRedacao;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.EstadoDoAtendimento;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Monta o progresso de um atendimento (downloads e análises) e o publica para o SSE do gerador (só para o dono). */
@Component
public class PublicadorDeProgresso {

    private static final Logger log = LoggerFactory.getLogger(PublicadorDeProgresso.class);

    private final Atendimentos atendimentos;
    private final Arquivos arquivos;
    private final ApplicationEventPublisher eventos;
    private final EtapaDeAnalise analises;
    private final EtapaDeRedacao redacao;

    @Autowired
    public PublicadorDeProgresso(Atendimentos atendimentos, Arquivos arquivos, ApplicationEventPublisher eventos,
                                 EtapaDeAnalise analises, EtapaDeRedacao redacao) {
        this.atendimentos = atendimentos;
        this.arquivos = arquivos;
        this.eventos = eventos;
        this.analises = analises;
        this.redacao = redacao;
    }

    /** Sem redação (os testes da análise). */
    public PublicadorDeProgresso(Atendimentos atendimentos, Arquivos arquivos, ApplicationEventPublisher eventos,
                                 EtapaDeAnalise analises) {
        this(atendimentos, arquivos, eventos, analises, EtapaDeRedacao.NENHUMA);
    }

    /** Sem análise (os testes do download). */
    public PublicadorDeProgresso(Atendimentos atendimentos, Arquivos arquivos, ApplicationEventPublisher eventos) {
        this(atendimentos, arquivos, eventos, EtapaDeAnalise.NENHUMA);
    }

    public Optional<ProgressoView> progresso(UUID atendimentoId) {
        return atendimentos.estado(atendimentoId).map(this::montar);
    }

    /** As saídas do atendimento (a tela de detalhe também mostra). */
    public List<EstadoDaSaida> saidas(UUID atendimentoId) {
        return redacao.estados(atendimentoId);
    }

    /** Tokens das redações: [entrada, saída]. */
    public long[] tokensDaRedacao(UUID atendimentoId) {
        return redacao.tokens(atendimentoId);
    }

    /** As análises do atendimento (a tela de detalhe também mostra). */
    public PanoramaDasAnalises panorama(UUID atendimentoId) {
        return analises.panorama(atendimentoId);
    }

    /** Nunca falha: o aviso à tela não pode atrapalhar o processamento. */
    public void publicar(UUID atendimentoId) {
        try {
            atendimentos.estado(atendimentoId).ifPresent(estado ->
                    eventos.publishEvent(new ProgressoAtualizado(estado.usuarioId(), montar(estado))));
        } catch (RuntimeException e) {
            log.warn("Não foi possível avisar o progresso do atendimento {}: {}", atendimentoId, e.toString());
        }
    }

    private ProgressoView montar(EstadoDoAtendimento estado) {
        return ProgressoView.de(estado, arquivos.doAtendimento(estado.id()), analises.panorama(estado.id()),
                redacao.estados(estado.id()), redacao.tokens(estado.id()));
    }
}
