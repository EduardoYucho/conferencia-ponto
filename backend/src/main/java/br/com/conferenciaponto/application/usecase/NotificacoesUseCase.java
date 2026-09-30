package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.NotificacaoCriadaEvento;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Notificacao;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import br.com.conferenciaponto.domain.port.NotificacaoRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Avisos do sino do painel (prazo do banco de horas, conciliação com o RH). */
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
    public Caixa listar() {
        return new Caixa(repository.listarRecentes(LIMITE_LISTAGEM), repository.contarNaoLidas());
    }

    /** Cria o aviso se a chave ainda não foi usada. */
    @Transactional
    public Optional<Notificacao> notificar(TipoNotificacao tipo, String chave, String titulo, String mensagem,
                                           String link) {
        if (repository.existeChave(chave)) {
            return Optional.empty();
        }
        Notificacao nova = Notificacao.nova(tipo, chave, titulo, mensagem, link, clock.instant());
        repository.salvar(nova);
        eventos.publishEvent(new NotificacaoCriadaEvento(nova, repository.contarNaoLidas()));
        return Optional.of(nova);
    }

    @Transactional
    public void marcarLida(UUID id) {
        if (!repository.marcarLida(id, clock.instant())) {
            throw new RecursoNaoEncontradoException("NOTIFICACAO_NAO_ENCONTRADA", "Notificação não encontrada.");
        }
    }

    /** Avisos de prazo de um ciclo já fechado (ou de uma previsão corrigida) deixam de ser pendência. */
    @Transactional
    public int arquivarAvisosDeCicloExceto(String sufixoChave) {
        return repository.marcarLidosAvisosDeCicloExceto(sufixoChave, clock.instant());
    }

    @Transactional
    public int marcarTodasLidas() {
        return repository.marcarTodasLidas(clock.instant());
    }
}
