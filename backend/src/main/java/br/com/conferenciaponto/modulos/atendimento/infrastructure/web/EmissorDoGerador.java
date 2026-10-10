package br.com.conferenciaponto.modulos.atendimento.infrastructure.web;

import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.atendimento.application.processamento.ProgressoAtualizado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O canal SSE do gerador ({@code GET /api/v1/atendimentos/eventos}), separado do canal do ponto: cada evento vai só
 * para o dono do atendimento (nem o administrador recebe o dos outros). Evento:
 * {@code atendimento-progresso} {atendimentoId, situacao, motivoPausa, percentual, arquivos: [{id, situacao, erro}]}.
 * Um comentário a cada 25 s mantém a conexão viva.
 */
@Component
public class EmissorDoGerador {

    public static final String EVENTO_PROGRESSO = "atendimento-progresso";
    private static final Logger log = LoggerFactory.getLogger(EmissorDoGerador.class);
    private static final long TEMPO = Duration.ofMinutes(30).toMillis();

    /** Uma conexão aberta (o SseEmitter; nos testes, um registro em memória). */
    interface Conexao {
        void enviar(String evento, Object dados) throws IOException;

        void ping() throws IOException;
    }

    private final Map<Conexao, UUID> conexoes = new ConcurrentHashMap<>();

    public SseEmitter conectar(Usuario usuario) {
        SseEmitter emissor = new SseEmitter(TEMPO);
        Conexao conexao = new Conexao() {
            @Override
            public void enviar(String evento, Object dados) throws IOException {
                emissor.send(SseEmitter.event().id(UUID.randomUUID().toString()).name(evento).data(dados,
                        MediaType.APPLICATION_JSON));
            }

            @Override
            public void ping() throws IOException {
                emissor.send(SseEmitter.event().comment("ping"));
            }
        };
        emissor.onCompletion(() -> conexoes.remove(conexao));
        emissor.onTimeout(() -> {
            conexoes.remove(conexao);
            emissor.complete();
        });
        emissor.onError(erro -> conexoes.remove(conexao));
        registrar(conexao, usuario.id());
        try {
            emissor.send(SseEmitter.event().name("conectado").data(Map.of("ok", true), MediaType.APPLICATION_JSON)
                    .reconnectTime(3000));
        } catch (IOException | IllegalStateException e) {
            conexoes.remove(conexao);
        }
        return emissor;
    }

    void registrar(Conexao conexao, UUID dono) {
        conexoes.put(conexao, dono);
    }

    @EventListener
    public void aoAtualizarProgresso(ProgressoAtualizado evento) {
        conexoes.forEach((conexao, dono) -> {
            if (dono.equals(evento.usuarioId())) {
                try {
                    conexao.enviar(EVENTO_PROGRESSO, evento.progresso());
                } catch (IOException | IllegalStateException e) {
                    descartar(conexao, e);
                }
            }
        });
    }

    @Scheduled(fixedRate = 25_000, initialDelay = 25_000)
    public void manterVivas() {
        for (Conexao conexao : conexoes.keySet()) {
            try {
                conexao.ping();
            } catch (IOException | IllegalStateException e) {
                descartar(conexao, e);
            }
        }
    }

    public int conexoes() {
        return conexoes.size();
    }

    private void descartar(Conexao conexao, Exception e) {
        conexoes.remove(conexao); // aba fechada ou rede perdida
        log.debug("Conexão SSE do gerador removida: {}", e.getMessage());
    }
}
