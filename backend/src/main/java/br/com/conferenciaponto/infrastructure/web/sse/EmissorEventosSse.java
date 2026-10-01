package br.com.conferenciaponto.infrastructure.web.sse;

import br.com.conferenciaponto.infrastructure.web.dto.ConciliacaoEventoResponse;
import br.com.conferenciaponto.application.evento.ConciliacaoAtualizadaEvento;
import br.com.conferenciaponto.infrastructure.web.dto.NotificacaoEventoResponse;
import br.com.conferenciaponto.infrastructure.web.dto.CicloBancoResponse;
import br.com.conferenciaponto.application.evento.NotificacaoCriadaEvento;
import br.com.conferenciaponto.application.evento.CalendarioAlteradoEvento;
import br.com.conferenciaponto.application.evento.CicloAtualizadoEvento;
import br.com.conferenciaponto.application.evento.LancamentoBancoAlteradoEvento;
import br.com.conferenciaponto.application.evento.ComprovanteNaoImportadoEvento;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.infrastructure.importacao.EstadoMonitor;
import br.com.conferenciaponto.infrastructure.web.dto.ComprovanteResponse;
import br.com.conferenciaponto.infrastructure.web.dto.EventoJornadaResponse;
import br.com.conferenciaponto.infrastructure.web.dto.MonitoramentoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Canal Server-Sent Events: mantém as conexões abertas pelo front-end e repassa os
 * eventos de aplicação <b>após o commit</b> da transação (o cliente que recarregar
 * dados em resposta ao evento já enxerga o estado gravado).
 *
 * <p>Eventos emitidos:
 * <ul>
 *   <li>{@code conectado} – estado do monitor de PDFs, ao abrir a conexão;</li>
 *   <li>{@code jornada-atualizada} – dia alterado (PDF, API, lançamento manual, exclusão);</li>
 *   <li>{@code comprovante-nao-importado} – PDF lido que não gerou batida;</li>
 *   <li>{@code monitor-atualizado} – a pasta dos PDFs ficou inacessível ou voltou;</li>
 *   <li>{@code ciclo-atualizado} – banco de horas fechado, fechamento desfeito ou período corrigido;</li>
 *   <li>{@code notificacao} – novo aviso no sino (prazo do banco, conciliação com o RH);</li>
 *   <li>{@code conciliacao-atualizada} – relatório do RH conferido ou divergência resolvida;</li>
 *   <li>{@code calendario-atualizado} – feriado, férias, folga ou abono cadastrado/removido no período;</li>
 *   <li>{@code banco-atualizado} – lançamento avulso no banco de horas criado ou removido.</li>
 * </ul>
 * Um comentário {@code :ping} a cada 25 s mantém a conexão viva em proxies e
 * detecta clientes desconectados.
 */
@Component
public class EmissorEventosSse {

    public static final String EVENTO_CONECTADO = "conectado";
    public static final String EVENTO_JORNADA = "jornada-atualizada";
    public static final String EVENTO_COMPROVANTE = "comprovante-nao-importado";
    public static final String EVENTO_MONITOR = "monitor-atualizado";
    public static final String EVENTO_CICLO = "ciclo-atualizado";
    public static final String EVENTO_NOTIFICACAO = "notificacao";
    public static final String EVENTO_CONCILIACAO = "conciliacao-atualizada";
    public static final String EVENTO_CALENDARIO = "calendario-atualizado";
    public static final String EVENTO_BANCO = "banco-atualizado";

    private static final Logger log = LoggerFactory.getLogger(EmissorEventosSse.class);
    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final Set<SseEmitter> emissores = ConcurrentHashMap.newKeySet();

    /** Abre uma nova conexão e envia o evento inicial. O navegador reconecta sozinho ao expirar. */
    public SseEmitter conectar(Object estadoInicial) {
        SseEmitter emissor = new SseEmitter(TIMEOUT_MS);
        emissor.onCompletion(() -> emissores.remove(emissor));
        emissor.onTimeout(() -> {
            emissores.remove(emissor);
            emissor.complete();
        });
        emissor.onError(erro -> emissores.remove(emissor));
        emissores.add(emissor);

        enviar(emissor, () -> evento(EVENTO_CONECTADO, estadoInicial).reconnectTime(3000));
        log.debug("Cliente SSE conectado ({} ativos)", emissores.size());
        return emissor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAtualizarJornada(JornadaAtualizadaEvento evento) {
        EventoJornadaResponse payload = EventoJornadaResponse.de(evento);
        difundir(() -> evento(EVENTO_JORNADA, payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoNaoImportarComprovante(ComprovanteNaoImportadoEvento evento) {
        ComprovanteResponse payload = ComprovanteResponse.de(evento);
        difundir(() -> evento(EVENTO_COMPROVANTE, payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAtualizarCiclo(CicloAtualizadoEvento evento) {
        CicloBancoResponse payload = CicloBancoResponse.de(evento.cicloAberto());
        difundir(() -> evento(EVENTO_CICLO, payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoCriarNotificacao(NotificacaoCriadaEvento evento) {
        NotificacaoEventoResponse payload = NotificacaoEventoResponse.de(evento);
        difundir(() -> evento(EVENTO_NOTIFICACAO, payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAtualizarConciliacao(ConciliacaoAtualizadaEvento evento) {
        ConciliacaoEventoResponse payload = new ConciliacaoEventoResponse(evento.descricao(), evento.pendentes());
        difundir(() -> evento(EVENTO_CONCILIACAO, payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAlterarCalendario(CalendarioAlteradoEvento evento) {
        CalendarioEvento payload = new CalendarioEvento(evento.inicio().toString(), evento.fim().toString());
        difundir(() -> evento(EVENTO_CALENDARIO, payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAlterarBanco(LancamentoBancoAlteradoEvento evento) {
        BancoEvento payload = new BancoEvento(evento.data().toString(), evento.descricao());
        difundir(() -> evento(EVENTO_BANCO, payload));
    }

    record CalendarioEvento(String inicio, String fim) {
    }

    record BancoEvento(String data, String descricao) {
    }

    /** Mudança de situação do monitor de PDFs (não é transacional: publicado pela thread do monitor). */
    @EventListener
    public void aoAtualizarMonitor(EstadoMonitor estado) {
        MonitoramentoResponse payload = MonitoramentoResponse.de(estado, List.of());
        difundir(() -> evento(EVENTO_MONITOR, payload));
    }

    @Scheduled(fixedRate = 25_000, initialDelay = 25_000)
    public void manterConexoesVivas() {
        difundir(() -> SseEmitter.event().comment("ping"));
    }

    public int conexoesAtivas() {
        return emissores.size();
    }

    private static SseEmitter.SseEventBuilder evento(String nome, Object dados) {
        return SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .name(nome)
                .data(dados, MediaType.APPLICATION_JSON);
    }

    /** O builder é recriado por cliente: {@code SseEventBuilder} não é reutilizável. */
    private void difundir(Supplier<SseEmitter.SseEventBuilder> fabrica) {
        for (SseEmitter emissor : emissores) {
            enviar(emissor, fabrica);
        }
    }

    private void enviar(SseEmitter emissor, Supplier<SseEmitter.SseEventBuilder> fabrica) {
        try {
            emissor.send(fabrica.get());
        } catch (IOException | IllegalStateException e) {
            // cliente fechou a aba/perdeu a rede: descarta a conexão
            emissores.remove(emissor);
            log.debug("Cliente SSE removido: {}", e.getMessage());
        }
    }
}
