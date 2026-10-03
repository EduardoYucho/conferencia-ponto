package br.com.conferenciaponto.infrastructure.conciliacao;

import br.com.conferenciaponto.application.evento.CalendarioAlteradoEvento;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.RelatorioRhRecebidoEvento;
import br.com.conferenciaponto.application.usecase.ConferirConciliacaoUseCase;
import br.com.conferenciaponto.infrastructure.config.AsyncConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

/**
 * Conciliação fora da requisição HTTP e sempre depois do commit:
 * <ul>
 *   <li>relatório do RH recebido → confere o período dele;</li>
 *   <li>um dia mudou (PDF, ajuste, lançamento, exclusão, ausência...) → reconfere aquele dia, para a lista
 *       de divergências acompanhar a conferência em tempo real;</li>
 *   <li>na subida → reconfere tudo (a regra de cálculo ou o calendário podem ter mudado).</li>
 * </ul>
 */
@Component
class ConciliacaoEmSegundoPlano {

    private static final Logger log = LoggerFactory.getLogger(ConciliacaoEmSegundoPlano.class);

    private final ConferirConciliacaoUseCase conferir;

    ConciliacaoEmSegundoPlano(ConferirConciliacaoUseCase conferir) {
        this.conferir = conferir;
    }

    /** Sem interface (ApplicationRunner): o proxy do @Async precisa ser por subclasse. */
    @EventListener(ApplicationReadyEvent.class)
    public void aoSubir() {
        try {
            ConferirConciliacaoUseCase.Resultado r = conferir.conferirTudo();
            if (r.diasConferidos() > 0) {
                log.info("Conciliação com o RH: {} dia(s) conferido(s) na subida ({} nova(s), {} resolvida(s))",
                        r.diasConferidos(), r.novas(), r.resolvidas());
            }
        } catch (RuntimeException e) {
            log.warn("Falha ao reconferir a conciliação com o RH na subida", e);
        }
        try {
            // relatório recebido e ainda não conferido (o sistema parou no meio): confere agora
            for (java.util.UUID pendente : conferir.relatoriosPorConferir()) {
                aoReceberRelatorio(new RelatorioRhRecebidoEvento(pendente));
            }
        } catch (RuntimeException e) {
            log.warn("Falha ao retomar os relatórios do RH que estavam por conferir", e);
        }
    }

    @Async(AsyncConfig.EXECUTOR_CONCILIACAO)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoReceberRelatorio(RelatorioRhRecebidoEvento evento) {
        try {
            ConferirConciliacaoUseCase.Resultado r = conferir.processarRelatorio(evento.relatorioId());
            log.info("Relatório do RH conferido: {} dia(s), {} divergência(s) nova(s)", r.diasConferidos(), r.novas());
        } catch (RuntimeException e) {
            log.error("Falha ao conferir o relatório do RH {}", evento.relatorioId(), e);
            try {
                conferir.marcarErro(evento.relatorioId(), "Não foi possível conferir este relatório (erro interno). "
                        + "Remova-o e envie de novo; se continuar, avise o administrador.");
            } catch (RuntimeException falha) {
                log.error("O relatório do RH {} não pôde ser marcado com erro", evento.relatorioId(), falha);
            }
        }
    }

    @Async(AsyncConfig.EXECUTOR_CONCILIACAO)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAtualizarJornada(JornadaAtualizadaEvento evento) {
        String motivo = switch (evento.origem()) {
            case AJUSTE -> "Igual ao RH após ajuste manual";
            case CONCILIACAO -> "Igual ao RH (dados do RH aceitos)";
            case COMPROVANTE_PDF -> "Igual ao RH após novo comprovante";
            default -> "Igual ao RH após alteração do dia";
        };
        reconferir(() -> conferir.conferirDatas(evento.usuarioId(), Set.of(evento.data()), motivo));
    }

    @Async(AsyncConfig.EXECUTOR_CONCILIACAO)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAlterarCalendario(CalendarioAlteradoEvento evento) {
        String motivo = "Igual ao RH após ajuste de feriado, ausência ou horário";
        reconferir(() -> {
            if (evento.usuarioId() == null) { // feriado: vale para todos
                conferir.conferirDeTodos(evento.inicio(), evento.fim(), motivo);
            } else {
                conferir.conferir(evento.usuarioId(), evento.inicio(), evento.fim(), motivo);
            }
        });
    }

    private void reconferir(Runnable acao) {
        try {
            acao.run();
        } catch (RuntimeException e) {
            log.warn("Falha ao reconferir a conciliação (a lista de divergências pode estar desatualizada: use Reconferir)", e);
        }
    }
}
