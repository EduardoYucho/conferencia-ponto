package br.com.conferenciaponto.infrastructure.web.sse;

import br.com.conferenciaponto.application.evento.CalendarioAlteradoEvento;
import br.com.conferenciaponto.application.evento.CicloAtualizadoEvento;
import br.com.conferenciaponto.application.evento.ComprovanteNaoImportadoEvento;
import br.com.conferenciaponto.application.evento.ConciliacaoAtualizadaEvento;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.LancamentoBancoAlteradoEvento;
import br.com.conferenciaponto.application.evento.NotificacaoCriadaEvento;
import br.com.conferenciaponto.application.evento.UsuarioAlteradoEvento;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.importacao.EstadoMonitor;
import br.com.conferenciaponto.infrastructure.web.dto.CicloBancoResponse;
import br.com.conferenciaponto.infrastructure.web.dto.ComprovanteResponse;
import br.com.conferenciaponto.infrastructure.web.dto.ConciliacaoEventoResponse;
import br.com.conferenciaponto.infrastructure.web.dto.EventoJornadaResponse;
import br.com.conferenciaponto.infrastructure.web.dto.MonitoramentoResponse;
import br.com.conferenciaponto.infrastructure.web.dto.NotificacaoEventoResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Canal Server-Sent Events: mantém as conexões abertas pelo front-end e repassa os
 * eventos de aplicação <b>após o commit</b> da transação (o cliente que recarregar
 * dados em resposta ao evento já enxerga o estado gravado).
 *
 * <p>Cada usuário recebe os eventos dos próprios dados; administrador e coordenação recebem os de todos
 * (para acompanhar quem estiverem consultando). Todo payload leva {@code usuarioId}, o dono dos dados
 * ({@code null} quando vale para todos, como um feriado): a tela ignora o que não é do usuário que ela mostra.
 *
 * <p>Eventos emitidos:
 * <ul>
 *   <li>{@code conectado} – estado do monitor da pasta do usuário, ao abrir a conexão;</li>
 *   <li>{@code jornada-atualizada} – dia alterado (PDF, API, lançamento manual, exclusão);</li>
 *   <li>{@code comprovante-nao-importado} – PDF lido que não gerou batida;</li>
 *   <li>{@code monitor-atualizado} – a pasta dos PDFs ficou inacessível ou voltou;</li>
 *   <li>{@code ciclo-atualizado} – banco de horas fechado, fechamento desfeito ou período corrigido;</li>
 *   <li>{@code notificacao} – novo aviso no sino (prazo do banco, conciliação com o RH);</li>
 *   <li>{@code conciliacao-atualizada} – relatório do RH conferido ou divergência resolvida;</li>
 *   <li>{@code calendario-atualizado} – feriado, férias, folga, abono ou horário alterado no período;</li>
 *   <li>{@code banco-atualizado} – lançamento avulso no banco de horas criado ou removido;</li>
 *   <li>{@code usuarios-atualizados} – usuário cadastrado ou alterado (perfil, situação, pasta).</li>
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
    public static final String EVENTO_USUARIOS = "usuarios-atualizados";

    private static final Logger log = LoggerFactory.getLogger(EmissorEventosSse.class);
    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    /** Quem está do outro lado da conexão. */
    record Assinante(UUID usuarioId, boolean veTodos) {

        boolean recebe(UUID dono) {
            return dono == null || veTodos || dono.equals(usuarioId);
        }
    }

    private final Map<SseEmitter, Assinante> emissores = new ConcurrentHashMap<>();
    private final ObjectMapper json;

    public EmissorEventosSse(ObjectMapper json) {
        this.json = json;
    }

    /** Abre uma nova conexão e envia o evento inicial. O navegador reconecta sozinho ao expirar. */
    public SseEmitter conectar(Usuario usuario, Object estadoInicial) {
        SseEmitter emissor = new SseEmitter(TIMEOUT_MS);
        emissor.onCompletion(() -> emissores.remove(emissor));
        emissor.onTimeout(() -> {
            emissores.remove(emissor);
            emissor.complete();
        });
        emissor.onError(erro -> emissores.remove(emissor));
        emissores.put(emissor, new Assinante(usuario.id(), usuario.podeVerTodos()));

        Object inicial = comDono(estadoInicial == null ? Map.of() : estadoInicial, usuario.id());
        enviar(emissor, () -> evento(EVENTO_CONECTADO, inicial).reconnectTime(3000));
        log.debug("Cliente SSE de {} conectado ({} ativos)", usuario.login(), emissores.size());
        return emissor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAtualizarJornada(JornadaAtualizadaEvento evento) {
        difundir(evento.usuarioId(), EVENTO_JORNADA, EventoJornadaResponse.de(evento));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoNaoImportarComprovante(ComprovanteNaoImportadoEvento evento) {
        difundir(evento.usuarioId(), EVENTO_COMPROVANTE, ComprovanteResponse.de(evento));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAtualizarCiclo(CicloAtualizadoEvento evento) {
        difundir(evento.usuarioId(), EVENTO_CICLO, CicloBancoResponse.de(evento.cicloAberto()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoCriarNotificacao(NotificacaoCriadaEvento evento) {
        difundir(evento.notificacao().usuarioId(), EVENTO_NOTIFICACAO, NotificacaoEventoResponse.de(evento));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAtualizarConciliacao(ConciliacaoAtualizadaEvento evento) {
        difundir(evento.usuarioId(), EVENTO_CONCILIACAO,
                new ConciliacaoEventoResponse(evento.descricao(), evento.pendentes()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAlterarCalendario(CalendarioAlteradoEvento evento) {
        difundir(evento.usuarioId(), EVENTO_CALENDARIO,
                new CalendarioEvento(evento.inicio().toString(), evento.fim().toString()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAlterarBanco(LancamentoBancoAlteradoEvento evento) {
        difundir(evento.usuarioId(), EVENTO_BANCO, new BancoEvento(evento.data().toString(), evento.descricao()));
    }

    /** Vai para todos: a lista de usuários (seletor) e a "Minha conta" de quem foi alterado se atualizam. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAlterarUsuario(UsuarioAlteradoEvento evento) {
        difundir(null, EVENTO_USUARIOS, new UsuarioEvento(evento.usuarioId(), evento.descricao()));
    }

    record CalendarioEvento(String inicio, String fim) {
    }

    record BancoEvento(String data, String descricao) {
    }

    record UsuarioEvento(UUID alterado, String descricao) {
    }

    /** Mudança de situação do monitor de PDFs (não é transacional: publicado pela thread do monitor). */
    @EventListener
    public void aoAtualizarMonitor(EstadoMonitor estado) {
        difundir(estado.usuarioId(), EVENTO_MONITOR, MonitoramentoResponse.de(estado, List.of()));
    }

    @Scheduled(fixedRate = 25_000, initialDelay = 25_000)
    public void manterConexoesVivas() {
        for (SseEmitter emissor : emissores.keySet()) {
            enviar(emissor, () -> SseEmitter.event().comment("ping"));
        }
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

    /** Payload + {@code usuarioId} (o dono dos dados), sem mudar o formato de cada evento. */
    @SuppressWarnings("unchecked")
    Map<String, Object> comDono(Object payload, UUID dono) {
        Map<String, Object> mapa = new LinkedHashMap<>(json.convertValue(payload, Map.class));
        mapa.put("usuarioId", dono == null ? null : dono.toString());
        return mapa;
    }

    /** O builder é recriado por cliente: {@code SseEventBuilder} não é reutilizável. */
    private void difundir(UUID dono, String nome, Object payload) {
        Map<String, Object> dados = comDono(payload, dono);
        emissores.forEach((emissor, assinante) -> {
            if (assinante.recebe(dono)) {
                enviar(emissor, () -> evento(nome, dados));
            }
        });
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
