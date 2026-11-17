package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.AvisoDeModulo;
import br.com.conferenciaponto.application.evento.NotificacaoCriadaEvento;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Notificacao;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import br.com.conferenciaponto.domain.port.NotificacaoRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Avisos do sino do painel (prazo do banco de horas, conciliação com o RH), de cada usuário. */
@Service
public class NotificacoesUseCase {

    public static final int LIMITE_LISTAGEM = 30;

    private final NotificacaoRepository repository;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public NotificacoesUseCase(NotificacaoRepository repository, ApplicationEventPublisher eventos, Clock clock) {
        this.repository = repository;
        this.eventos = eventos;
        this.clock = clock;
    }

    public record Caixa(List<Notificacao> notificacoes, int naoLidas) {
    }

    @Transactional(readOnly = true)
    public Caixa listar(UUID usuarioId) {
        return new Caixa(repository.listarRecentes(usuarioId, LIMITE_LISTAGEM), repository.contarNaoLidas(usuarioId));
    }

    /** Cria o aviso se a chave ainda não foi usada. */
    @Transactional
    public Optional<Notificacao> notificar(UUID usuarioId, TipoNotificacao tipo, String chave, String titulo,
                                           String mensagem, String link) {
        if (repository.existeChave(usuarioId, chave)) {
            return Optional.empty();
        }
        Notificacao nova = Notificacao.nova(usuarioId, tipo, chave, titulo, mensagem, link, clock.instant());
        repository.salvar(nova);
        eventos.publishEvent(new NotificacaoCriadaEvento(nova, repository.contarNaoLidas(usuarioId)));
        return Optional.of(nova);
    }

    /**
     * O aviso que um módulo pede ({@link AvisoDeModulo}): o sino é do ponto, e o módulo não conhece esta classe. Cada
     * parte é cortada no tamanho da tabela; a chave ganha o prefixo "MODULO:".
     */
    @EventListener
    @Transactional
    public void aoPedirAviso(AvisoDeModulo aviso) {
        notificar(aviso.usuarioId(), TipoNotificacao.MODULO, cortar("MODULO:" + aviso.chave(), 120),
                cortar(aviso.titulo(), 120), cortar(aviso.mensagem(), 500),
                aviso.link() == null ? null : cortar(aviso.link(), 200));
    }

    private static String cortar(String texto, int tamanho) {
        return texto.length() <= tamanho ? texto : texto.substring(0, tamanho - 1) + "…";
    }

    @Transactional
    public void marcarLida(UUID usuarioId, UUID id) {
        if (!repository.marcarLida(usuarioId, id, clock.instant())) {
            throw new RecursoNaoEncontradoException("NOTIFICACAO_NAO_ENCONTRADA", "Notificação não encontrada.");
        }
    }

    /** Avisos de prazo de um ciclo já fechado (ou de uma previsão corrigida) deixam de ser pendência. */
    @Transactional
    public int arquivarAvisosDeCicloExceto(UUID usuarioId, String sufixoChave) {
        return repository.marcarLidosAvisosDeCicloExceto(usuarioId, sufixoChave, clock.instant());
    }

    @Transactional
    public int marcarTodasLidas(UUID usuarioId) {
        return repository.marcarTodasLidas(usuarioId, clock.instant());
    }
}
