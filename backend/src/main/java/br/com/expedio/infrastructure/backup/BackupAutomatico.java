package br.com.expedio.infrastructure.backup;

import br.com.expedio.application.usecase.NotificacoesUseCase;
import br.com.expedio.domain.acesso.PermissoesDaAdministracao;
import br.com.expedio.domain.model.TipoNotificacao;
import br.com.expedio.domain.model.Usuario;
import br.com.expedio.domain.port.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;

/**
 * Backup automático: todo dia, depois de {@code expedio.backup.horario}, se ainda não houver um backup do dia na pasta
 * (o do {@code expedio atualizar} conta), a aplicação roda o {@code expedio backup diario}. Com o PC desligado no
 * horário, ele sai na primeira conferência depois de ligar. Falhou: tenta de novo depois de 1 hora e avisa no sino
 * de quem vê os logs (uma vez por dia). A cópia fora do PC, quando configurada, é feita pelo próprio comando.
 */
@Component
public class BackupAutomatico {

    private static final Logger log = LoggerFactory.getLogger(BackupAutomatico.class);
    static final String MOTIVO = "diario";
    static final Duration ESPERA_DEPOIS_DE_FALHAR = Duration.ofHours(1);
    /** O tamanho da mensagem no sino (tb_notificacao.mensagem). */
    private static final int TAMANHO_DA_MENSAGEM = 500;

    /** O que a conferência fez. */
    enum Resultado { DESLIGADO, INDISPONIVEL, ANTES_DO_HORARIO, JA_FEITO, AGUARDANDO, FEITO, FEITO_SEM_COPIA, FALHOU }

    private final ExecutorDoBackup executor;
    private final Path pasta;
    private final LocalTime horario;
    private final boolean ativo;
    private final Clock clock;
    private final NotificacoesUseCase notificacoes;
    private final UsuarioRepository usuarios;

    private volatile Instant ultimaFalha;
    private volatile String indisponivelAvisado;

    @Autowired
    public BackupAutomatico(@Value("${expedio.backup.automatico:true}") boolean ativo,
                            @Value("${expedio.backup.horario:12:15}") String horario,
                            @Value("${expedio.backup.pasta:${user.home}/.expedio/backups}") String pasta,
                            @Value("${expedio.backup.script:${user.home}/.expedio/servico/bin/expedio.ps1}") String script,
                            Clock clock, NotificacoesUseCase notificacoes, UsuarioRepository usuarios) {
        this(new BackupPeloScript(Path.of(script)), Path.of(pasta), LocalTime.parse(horario), ativo, clock,
                notificacoes, usuarios);
    }

    BackupAutomatico(ExecutorDoBackup executor, Path pasta, LocalTime horario, boolean ativo, Clock clock,
                     NotificacoesUseCase notificacoes, UsuarioRepository usuarios) {
        this.executor = executor;
        this.pasta = pasta;
        this.horario = horario;
        this.ativo = ativo;
        this.clock = clock;
        this.notificacoes = notificacoes;
        this.usuarios = usuarios;
    }

    /** Nada escapa daqui: uma exceção não pode parar as conferências seguintes. */
    @Scheduled(fixedDelayString = "${expedio.backup.verificacao:15m}", initialDelayString = "${expedio.backup.primeira-verificacao:3m}")
    public void conferir() {
        try {
            conferirAgora();
        } catch (RuntimeException | Error e) {
            log.error("A conferência do backup automático falhou (tenta de novo na próxima)", e);
        }
    }

    Resultado conferirAgora() {
        if (!ativo) {
            return Resultado.DESLIGADO;
        }
        String motivo = executor.indisponivel();
        if (motivo != null) {
            if (!motivo.equals(indisponivelAvisado)) {
                log.info("Backup automático desligado nesta máquina: {}", motivo);
                indisponivelAvisado = motivo;
            }
            return Resultado.INDISPONIVEL;
        }
        indisponivelAvisado = null;
        Instant agora = clock.instant();
        ZonedDateTime local = agora.atZone(clock.getZone());
        if (local.toLocalTime().isBefore(horario)) {
            return Resultado.ANTES_DO_HORARIO;
        }
        LocalDate hoje = local.toLocalDate();
        if (existeBackupDo(hoje)) {
            return Resultado.JA_FEITO;
        }
        Instant falha = ultimaFalha;
        if (falha != null && agora.isBefore(falha.plus(ESPERA_DEPOIS_DE_FALHAR))) {
            return Resultado.AGUARDANDO;
        }

        log.info("Backup automático do dia: começando");
        ExecutorDoBackup.Execucao execucao = executor.executar(MOTIVO);
        execucao.linhas().forEach(l -> log.debug("expedio backup: {}", l));
        if (!execucao.feito()) {
            ultimaFalha = clock.instant();
            log.error("O backup automático falhou (tenta de novo em 1 hora): {}", execucao.motivo());
            avisar(TipoNotificacao.BACKUP, "BACKUP_FALHOU:" + hoje, "O backup de hoje falhou",
                    execucao.motivo() + " O sistema tenta de novo a cada hora; para ver o motivo na hora, rode "
                            + "\"expedio backup\" no terminal.");
            return Resultado.FALHOU;
        }
        ultimaFalha = null;
        if (execucao.codigo() == ExecutorDoBackup.SEM_COPIA_FORA) {
            log.warn("Backup automático feito, mas a cópia fora do PC não: {}", execucao.motivo());
            avisar(TipoNotificacao.BACKUP, "BACKUP_SEM_COPIA:" + hoje, "A cópia do backup fora do PC não foi feita",
                    execucao.motivo() + " O backup no PC foi feito; a cópia vai junto no próximo.");
            return Resultado.FEITO_SEM_COPIA;
        }
        log.info("Backup automático do dia feito");
        return Resultado.FEITO;
    }

    /** Um backup do comando (pasta com backup.json) com a data de hoje no nome: o automático ou outro. */
    boolean existeBackupDo(LocalDate dia) {
        if (!Files.isDirectory(pasta)) {
            return false;
        }
        String prefixo = dia.toString();
        try (Stream<Path> pastas = Files.list(pasta)) {
            return pastas.anyMatch(p -> {
                String nome = p.getFileName().toString();
                return nome.startsWith(prefixo) && !nome.endsWith(".parcial") && Files.isRegularFile(p.resolve("backup.json"));
            });
        } catch (IOException e) {
            log.warn("A pasta dos backups não pôde ser lida ({}): o backup do dia será feito", e.toString());
            return false;
        }
    }

    /** No sino de quem vê os logs; uma falha aqui não muda o resultado do backup. */
    private void avisar(TipoNotificacao tipo, String chave, String titulo, String mensagem) {
        try {
            List<Usuario> quem = usuarios.listar().stream()
                    .filter(u -> u.ativo() && u.pode(PermissoesDaAdministracao.LOGS))
                    .toList();
            String texto = mensagem.length() <= TAMANHO_DA_MENSAGEM ? mensagem
                    : mensagem.substring(0, TAMANHO_DA_MENSAGEM - 1) + "…";
            for (Usuario u : quem) {
                notificacoes.notificar(u.id(), tipo, chave, titulo, texto, "/logs");
            }
        } catch (RuntimeException e) {
            log.warn("O aviso do backup não foi para o sino: {}", e.toString());
        }
    }
}
