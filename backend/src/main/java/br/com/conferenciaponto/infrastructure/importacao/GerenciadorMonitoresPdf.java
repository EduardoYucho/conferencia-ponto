package br.com.conferenciaponto.infrastructure.importacao;

import br.com.conferenciaponto.application.evento.UsuarioAlteradoEvento;
import br.com.conferenciaponto.application.usecase.GerenciarUsuariosUseCase;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Um monitor de pasta por usuário: cada titular ativo com "pasta de comprovantes" configurada tem a sua pasta
 * observada numa thread própria. Quando o cadastro muda (pasta nova, usuário desativado...), os monitores se
 * reorganizam sem reiniciar a aplicação.
 *
 * <p>Na primeira subida depois da atualização para vários usuários, a pasta da configuração
 * ({@code ponto.importacao-pdf.diretorio}) passa a ser a pasta do primeiro administrador, se ninguém tiver
 * pasta ainda.
 */
@Component
public class GerenciadorMonitoresPdf {

    private static final Logger log = LoggerFactory.getLogger(GerenciadorMonitoresPdf.class);

    private final UsuarioRepository usuarios;
    private final GerenciarUsuariosUseCase gerenciarUsuarios;
    private final ProcessadorComprovantePdf processador;
    private final ImportacaoPdfProperties properties;
    private final ApplicationEventPublisher eventos;
    private final Map<UUID, DiretorioPontoWatcher> monitores = new ConcurrentHashMap<>();
    private final ExecutorService threads;
    private volatile boolean iniciado;
    private volatile boolean encerrado;

    public GerenciadorMonitoresPdf(UsuarioRepository usuarios, GerenciarUsuariosUseCase gerenciarUsuarios,
                                   ProcessadorComprovantePdf processador, ImportacaoPdfProperties properties,
                                   ApplicationEventPublisher eventos) {
        this.usuarios = usuarios;
        this.gerenciarUsuarios = gerenciarUsuarios;
        this.processador = processador;
        this.properties = properties;
        this.eventos = eventos;
        AtomicInteger contador = new AtomicInteger();
        this.threads = Executors.newCachedThreadPool(tarefa -> {
            Thread t = new Thread(tarefa, "monitor-pdf-" + contador.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Depois dos inicializadores de usuários e horários. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void iniciar() {
        if (!properties.habilitado()) {
            log.info("Monitoramento de pastas de comprovantes desligado (ponto.importacao-pdf.habilitado=false)");
            return;
        }
        iniciado = true;
        try {
            levarPastaDaConfiguracao();
        } catch (RuntimeException e) {
            log.warn("Não foi possível usar a pasta da configuração para o administrador: {}", e.getMessage());
        }
        sincronizar();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAlterarUsuario(UsuarioAlteradoEvento evento) {
        if (iniciado) {
            sincronizar();
        }
    }

    /** Liga, desliga ou troca os monitores conforme o cadastro atual. */
    public synchronized void sincronizar() {
        if (encerrado || !properties.habilitado()) {
            return;
        }
        Map<UUID, String> desejados = new LinkedHashMap<>();
        for (Usuario u : usuarios.listar()) {
            if (u.ativo() && u.isTitular() && u.pastaComprovantes() != null) {
                desejados.put(u.id(), u.pastaComprovantes());
            }
        }
        monitores.forEach((usuarioId, monitor) -> {
            String pasta = desejados.get(usuarioId);
            if (pasta == null || !caminho(pasta).map(monitor.getDiretorio()::equals).orElse(false)) {
                log.info("Parando o monitor de {}", monitor.getDiretorio());
                monitor.parar();
                monitores.remove(usuarioId);
            }
        });
        desejados.forEach((usuarioId, pasta) -> {
            if (monitores.containsKey(usuarioId)) {
                return;
            }
            Optional<Path> caminho = caminho(pasta);
            if (caminho.isEmpty()) {
                eventos.publishEvent(new EstadoMonitor(usuarioId, EstadoMonitor.Situacao.INDISPONIVEL, pasta,
                        "caminho de pasta inválido", Instant.now(), null));
                return;
            }
            DiretorioPontoWatcher monitor = new DiretorioPontoWatcher(usuarioId, caminho.get(), false,
                    properties.processarExistentes(), properties.varreduraPeriodica(),
                    properties.intervaloReconexao(), processador.doUsuario(usuarioId), eventos);
            monitores.put(usuarioId, monitor);
            threads.execute(monitor::monitorar);
        });
    }

    /** Situação do monitor do usuário (vazio = sem pasta configurada ou monitoramento desligado). */
    public Optional<EstadoMonitor> estado(UUID usuarioId) {
        return Optional.ofNullable(monitores.get(usuarioId)).map(DiretorioPontoWatcher::getEstado);
    }

    public boolean habilitado() {
        return properties.habilitado();
    }

    /** Pasta configurada como caminho (vazio se o texto não for um caminho válido neste sistema). */
    public static Optional<Path> caminho(String pasta) {
        try {
            return Optional.of(Path.of(pasta).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private void levarPastaDaConfiguracao() {
        String pastaConfigurada = properties.diretorio();
        if (pastaConfigurada == null || pastaConfigurada.isBlank()) {
            return;
        }
        boolean alguemTemPasta = usuarios.listar().stream().anyMatch(u -> u.pastaComprovantes() != null);
        if (alguemTemPasta) {
            return;
        }
        usuarios.listar().stream()
                .filter(u -> u.ativo() && u.isAdmin())
                .findFirst()
                .ifPresent(admin -> {
                    gerenciarUsuarios.definirPasta(admin.id(), pastaConfigurada.strip(), admin);
                    log.info("Pasta de comprovantes da configuração ({}) atribuída a {}. A partir de agora cada "
                            + "usuário escolhe a própria pasta em \"Minha conta\".", pastaConfigurada, admin.login());
                });
    }

    @PreDestroy
    public void encerrar() {
        encerrado = true;
        monitores.values().forEach(DiretorioPontoWatcher::parar);
        monitores.clear();
        threads.shutdownNow();
    }
}
