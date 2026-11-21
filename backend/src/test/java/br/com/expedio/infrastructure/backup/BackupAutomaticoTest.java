package br.com.expedio.infrastructure.backup;

import br.com.expedio.application.usecase.NotificacoesUseCase;
import br.com.expedio.domain.acesso.Acesso;
import br.com.expedio.domain.model.TipoNotificacao;
import br.com.expedio.domain.model.Usuario;
import br.com.expedio.domain.port.UsuarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupAutomaticoTest {

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate HOJE = LocalDate.of(2026, 11, 17);

    @TempDir
    Path pasta;

    private final Relogio relogio = new Relogio(HOJE.atTime(13, 0).atZone(FUSO).toInstant());
    private final ExecutorFalso executor = new ExecutorFalso();
    private final NotificacoesUseCase notificacoes = mock(NotificacoesUseCase.class);
    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final Usuario admin = usuario("admin", Acesso.administrador(), true);
    private final Usuario comum = usuario("maria", Acesso.usuario(), true);
    private final Usuario adminInativo = usuario("antigo", Acesso.administrador(), false);
    private BackupAutomatico backup;

    @BeforeEach
    void preparar() {
        when(usuarios.listar()).thenReturn(List.of(admin, comum, adminInativo));
        backup = new BackupAutomatico(executor, pasta, LocalTime.of(12, 15), true, relogio, notificacoes, usuarios);
    }

    @Test
    void depoisDoHorarioSemBackupDoDiaFazOBackupDiario() {
        assertThat(backup.conferirAgora()).isEqualTo(BackupAutomatico.Resultado.FEITO);
        assertThat(executor.motivos).containsExactly("diario");
        verify(notificacoes, never()).notificar(any(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void antesDoHorarioNaoFazNada() {
        relogio.agora = HOJE.atTime(12, 14).atZone(FUSO).toInstant();
        assertThat(backup.conferirAgora()).isEqualTo(BackupAutomatico.Resultado.ANTES_DO_HORARIO);
        assertThat(executor.motivos).isEmpty();
    }

    @Test
    void umBackupDeHojeQualquerContaEOsOutrosNao() throws IOException {
        criarBackup("2026-11-16_121500_diario");                 // ontem
        Files.createDirectories(pasta.resolve("2026-11-17_090000_diario.parcial"));   // interrompido
        Files.createDirectories(pasta.resolve("2026-11-17_091000_sem-json"));         // sem backup.json
        assertThat(backup.existeBackupDo(HOJE)).isFalse();

        criarBackup("2026-11-17_054228_antes-de-atualizar-revisao-novembro");
        assertThat(backup.existeBackupDo(HOJE)).isTrue();
        assertThat(backup.conferirAgora()).isEqualTo(BackupAutomatico.Resultado.JA_FEITO);
        assertThat(executor.motivos).isEmpty();
    }

    @Test
    void semPastaDeBackupsAindaFaz() {
        BackupAutomatico semPasta = new BackupAutomatico(executor, pasta.resolve("nao-existe"), LocalTime.of(12, 15),
                true, relogio, notificacoes, usuarios);
        assertThat(semPasta.conferirAgora()).isEqualTo(BackupAutomatico.Resultado.FEITO);
    }

    @Test
    void desligadoOuIndisponivelNaoRoda() {
        BackupAutomatico desligado = new BackupAutomatico(executor, pasta, LocalTime.of(12, 15), false, relogio,
                notificacoes, usuarios);
        assertThat(desligado.conferirAgora()).isEqualTo(BackupAutomatico.Resultado.DESLIGADO);

        executor.indisponivel = "só no Windows";
        assertThat(backup.conferirAgora()).isEqualTo(BackupAutomatico.Resultado.INDISPONIVEL);
        assertThat(executor.motivos).isEmpty();
    }

    @Test
    void falhaAvisaQuemVeOsLogsEEsperaUmaHoraParaTentarDeNovo() {
        executor.proximo = new ExecutorDoBackup.Execucao(1, List.of("Backup da Conferência de Ponto",
                "  Backup não feito: o pg_dump não conseguiu copiar o banco (connection refused). Confira se o PostgreSQL está rodando."));

        assertThat(backup.conferirAgora()).isEqualTo(BackupAutomatico.Resultado.FALHOU);
        verify(notificacoes).notificar(eq(admin.id()), eq(TipoNotificacao.BACKUP), eq("BACKUP_FALHOU:2026-11-17"),
                eq("O backup de hoje falhou"), contains("Confira se o PostgreSQL está rodando."), eq("/logs"));
        verify(notificacoes, never()).notificar(eq(comum.id()), any(), anyString(), anyString(), anyString(), anyString());
        verify(notificacoes, never()).notificar(eq(adminInativo.id()), any(), anyString(), anyString(), anyString(), anyString());

        relogio.agora = relogio.agora.plus(Duration.ofMinutes(59));
        assertThat(backup.conferirAgora()).isEqualTo(BackupAutomatico.Resultado.AGUARDANDO);
        assertThat(executor.motivos).hasSize(1);

        executor.proximo = new ExecutorDoBackup.Execucao(0, List.of("  Backup feito"));
        relogio.agora = relogio.agora.plus(Duration.ofMinutes(2));
        assertThat(backup.conferirAgora()).isEqualTo(BackupAutomatico.Resultado.FEITO);
        assertThat(executor.motivos).hasSize(2);
    }

    @Test
    void copiaForaDoPcQueNaoSaiuAvisaMasOBackupValeComoFeito() {
        executor.proximo = new ExecutorDoBackup.Execucao(ExecutorDoBackup.SEM_COPIA_FORA,
                List.of("  Backup feito: ...", "  Cópia fora do PC não feita: a pasta E:\\Backups não está acessível."));

        assertThat(backup.conferirAgora()).isEqualTo(BackupAutomatico.Resultado.FEITO_SEM_COPIA);
        verify(notificacoes).notificar(eq(admin.id()), eq(TipoNotificacao.BACKUP), eq("BACKUP_SEM_COPIA:2026-11-17"),
                anyString(), contains("a pasta E:\\Backups não está acessível."), eq("/logs"));
        // não conta como falha: sem a espera de 1 hora (aqui o executor falso não deixa a pasta do backup)
        executor.proximo = new ExecutorDoBackup.Execucao(0, List.of("  Backup feito"));
        assertThat(backup.conferirAgora()).isEqualTo(BackupAutomatico.Resultado.FEITO);
        assertThat(executor.motivos).hasSize(2);
    }

    @Test
    void mensagemLongaDemaisCabeNoSino() {
        executor.proximo = new ExecutorDoBackup.Execucao(1, List.of("Backup não feito: " + "x".repeat(800)));
        backup.conferirAgora();
        verify(notificacoes).notificar(eq(admin.id()), any(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.argThat(m -> m.length() <= 500 && m.endsWith("…")), anyString());
    }

    @Test
    void umErroNaConferenciaNaoEscapa() {
        when(usuarios.listar()).thenThrow(new IllegalStateException("banco fora"));
        executor.proximo = new ExecutorDoBackup.Execucao(1, List.of("Backup não feito: disco cheio."));
        backup.conferir(); // não lança, nem o aviso derruba o resultado
        assertThat(executor.motivos).hasSize(1);

        ExecutorFalso quebrado = new ExecutorFalso() {
            @Override
            public Execucao executar(String motivo) {
                throw new IllegalStateException("inesperado");
            }
        };
        new BackupAutomatico(quebrado, pasta, LocalTime.of(12, 15), true, relogio, notificacoes, usuarios).conferir();
    }

    @Test
    void oMotivoVemDaExplicacaoDoComando() {
        assertThat(new ExecutorDoBackup.Execucao(1, List.of("a", "  Backup não feito: disco cheio.", "")).motivo())
                .isEqualTo("disco cheio.");
        assertThat(new ExecutorDoBackup.Execucao(1, List.of("primeira", "última", " ")).motivo()).isEqualTo("última");
        assertThat(new ExecutorDoBackup.Execucao(5, List.of()).motivo()).contains("código 5");
        assertThat(new ExecutorDoBackup.Execucao(0, List.of()).feito()).isTrue();
        assertThat(new ExecutorDoBackup.Execucao(3, List.of()).feito()).isTrue();
        assertThat(new ExecutorDoBackup.Execucao(-1, List.of()).feito()).isFalse();
    }

    @Test
    void peloScriptSoNoWindowsEComOComandoInstalado() throws IOException {
        assertThat(new BackupPeloScript(pasta.resolve("expedio.ps1"), "Linux").indisponivel()).contains("só existe no Windows");
        assertThat(new BackupPeloScript(pasta.resolve("expedio.ps1"), "Windows 11").indisponivel()).contains("não foi encontrado");
        Files.writeString(pasta.resolve("expedio.ps1"), "# comando");
        assertThat(new BackupPeloScript(pasta.resolve("expedio.ps1"), "Windows 11").indisponivel()).isNull();
    }

    private void criarBackup(String nome) throws IOException {
        Path dir = Files.createDirectories(pasta.resolve(nome));
        Files.writeString(dir.resolve("backup.json"), "{}");
    }

    private static Usuario usuario(String login, Acesso acesso, boolean ativo) {
        return new Usuario(UUID.randomUUID(), login, login, "hash", ativo, acesso, null);
    }

    private static class ExecutorFalso implements ExecutorDoBackup {
        final List<String> motivos = new ArrayList<>();
        String indisponivel;
        Execucao proximo = new Execucao(0, List.of("  Backup feito"));

        @Override
        public String indisponivel() {
            return indisponivel;
        }

        @Override
        public Execucao executar(String motivo) {
            motivos.add(motivo);
            return proximo;
        }
    }

    private static final class Relogio extends Clock {
        Instant agora;

        Relogio(Instant agora) {
            this.agora = agora;
        }

        @Override
        public ZoneId getZone() {
            return FUSO;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }
}
