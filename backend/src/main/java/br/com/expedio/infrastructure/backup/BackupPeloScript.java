package br.com.expedio.infrastructure.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Roda o {@code expedio backup <motivo>} instalado ({@code servico\bin\expedio.ps1}): o mesmo backup do comando, com a
 * mesma conferência e a mesma regra de guarda. Sem janela (o Java cria o processo sem console visível).
 */
class BackupPeloScript implements ExecutorDoBackup {

    private static final Logger log = LoggerFactory.getLogger(BackupPeloScript.class);
    /** Mais que isso, algo travou (o banco inteiro leva segundos). */
    static final Duration TEMPO_LIMITE = Duration.ofMinutes(30);
    private static final int LINHAS_GUARDADAS = 200;

    private final Path script;
    private final String sistema;

    BackupPeloScript(Path script) {
        this(script, System.getProperty("os.name", ""));
    }

    BackupPeloScript(Path script, String sistema) {
        this.script = script;
        this.sistema = sistema;
    }

    @Override
    public String indisponivel() {
        if (!sistema.toLowerCase(Locale.ROOT).startsWith("windows")) {
            return "o backup automático usa o comando \"ponto\", que só existe no Windows";
        }
        if (script == null || !Files.isRegularFile(script)) {
            return "o comando \"ponto\" não foi encontrado em " + script + " (ele é instalado pelo \"expedio atualizar\")";
        }
        return null;
    }

    @Override
    public Execucao executar(String motivo) {
        // a saída em UTF-8 (o padrão do console é a página de código do Windows) e o código de saída do comando
        String comando = "[Console]::OutputEncoding = [Text.Encoding]::UTF8; & '"
                + script.toString().replace("'", "''") + "' backup " + motivo + "; exit $LASTEXITCODE";
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-Command", comando)
                .redirectErrorStream(true);
        List<String> linhas = new ArrayList<>();
        try {
            Process processo = pb.start();
            processo.getOutputStream().close();
            Thread leitor = new Thread(() -> ler(processo, linhas), "backup-saida");
            leitor.setDaemon(true);
            leitor.start();
            if (!processo.waitFor(TEMPO_LIMITE.toMinutes(), TimeUnit.MINUTES)) {
                processo.descendants().forEach(ProcessHandle::destroyForcibly);
                processo.destroyForcibly();
                return new Execucao(-1, comMotivo(linhas, "Backup não feito: passou de "
                        + TEMPO_LIMITE.toMinutes() + " minutos e foi interrompido."));
            }
            leitor.join(5_000);
            synchronized (linhas) {
                return new Execucao(processo.exitValue(), linhas);
            }
        } catch (IOException e) {
            return new Execucao(-1, List.of("Backup não feito: o PowerShell não pôde ser aberto (" + e.getMessage() + ")."));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Execucao(-1, List.of("Backup não feito: o sistema estava parando."));
        }
    }

    private static void ler(Process processo, List<String> linhas) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(processo.getInputStream(), StandardCharsets.UTF_8))) {
            String linha;
            while ((linha = r.readLine()) != null) {
                synchronized (linhas) {
                    linhas.add(linha);
                    if (linhas.size() > LINHAS_GUARDADAS) {
                        linhas.remove(0);
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Saída do backup interrompida: {}", e.toString());
        }
    }

    private static List<String> comMotivo(List<String> linhas, String motivo) {
        synchronized (linhas) {
            List<String> todas = new ArrayList<>(linhas);
            todas.add(motivo);
            return todas;
        }
    }
}
