package br.com.conferenciaponto.infrastructure.importacao;

import br.com.conferenciaponto.infrastructure.importacao.EstadoMonitor.Situacao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Usa o WatchService real do sistema operacional numa pasta temporária. */
class DiretorioPontoWatcherTest {

    private static final Duration SEM_VARREDURA = Duration.ZERO;
    private static final Duration RAPIDO = Duration.ofMillis(150);

    @TempDir
    Path pasta;

    private final BlockingQueue<String> processados = new LinkedBlockingQueue<>();
    private final List<EstadoMonitor> estados = new CopyOnWriteArrayList<>();
    private final ExecutorService thread = Executors.newSingleThreadExecutor();
    private DiretorioPontoWatcher watcher;

    @AfterEach
    void encerrar() {
        if (watcher != null) {
            watcher.parar();
        }
        thread.shutdownNow();
    }

    private DiretorioPontoWatcher criar(Path diretorio, boolean existentes, Duration varredura,
                                        ProcessadorDeComprovantes processador) {
        return new DiretorioPontoWatcher(diretorio, existentes, varredura, RAPIDO, processador,
                evento -> estados.add((EstadoMonitor) evento));
    }

    private ProcessadorDeComprovantes registrando() {
        return arquivo -> processados.add(arquivo.getFileName().toString());
    }

    @Test
    @DisplayName("Processa PDFs já existentes e os novos (ENTRY_CREATE), ignorando outros arquivos")
    void detectaNovosPdfs() throws Exception {
        Files.writeString(pasta.resolve("antigo.pdf"), "x");
        watcher = criar(pasta, true, SEM_VARREDURA, registrando());

        Future<?> execucao = thread.submit(watcher::monitorar);
        await().atMost(5, TimeUnit.SECONDS).until(watcher::isAtivo);
        assertThat(processados.poll(5, TimeUnit.SECONDS)).isEqualTo("antigo.pdf");

        Files.writeString(pasta.resolve("anotacoes.txt"), "ignorar");
        Files.writeString(pasta.resolve("Comprovante.PDF"), "x");

        assertThat(processados.poll(15, TimeUnit.SECONDS)).isEqualTo("Comprovante.PDF");
        assertThat(processados).isEmpty();

        watcher.parar();
        execucao.get(5, TimeUnit.SECONDS);
        assertThat(watcher.getEstado().situacao()).isEqualTo(Situacao.ENCERRADO);
    }

    @Test
    @DisplayName("Cria a pasta monitorada se ela não existir")
    void criaPasta() {
        Path inexistente = pasta.resolve("Downloads").resolve("Ponto");
        watcher = criar(inexistente, false, SEM_VARREDURA, registrando());

        thread.submit(watcher::monitorar);
        await().atMost(5, TimeUnit.SECONDS).until(watcher::isAtivo);
        assertThat(inexistente).isDirectory();
    }

    @Test
    @DisplayName("Pasta inacessível: fica INDISPONIVEL, tenta de novo e, ao voltar, importa o que chegou")
    void reconectaQuandoAPastaVolta() throws Exception {
        // um ARQUIVO no lugar da pasta-mãe impede criar/acessar a pasta, como uma pasta de rede fora do ar
        Path bloqueio = Files.writeString(pasta.resolve("servidor"), "fora do ar");
        Path compartilhada = bloqueio.resolve("Ponto");
        watcher = criar(compartilhada, true, SEM_VARREDURA, registrando());

        thread.submit(watcher::monitorar);
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> watcher.getEstado().situacao() == Situacao.INDISPONIVEL);
        assertThat(watcher.getEstado().mensagem()).isNotBlank();

        // "a rede volta" já com um comprovante que chegou enquanto estava fora
        Files.delete(bloqueio);
        Files.createDirectories(compartilhada);
        Files.writeString(compartilhada.resolve("chegou-offline.pdf"), "x");

        await().atMost(5, TimeUnit.SECONDS).until(watcher::isAtivo);
        assertThat(processados.poll(5, TimeUnit.SECONDS)).isEqualTo("chegou-offline.pdf");
        assertThat(estados).extracting(EstadoMonitor::situacao)
                .containsSubsequence(Situacao.INDISPONIVEL, Situacao.ATIVO);
    }

    @Test
    @DisplayName("Varredura: tenta de novo o PDF que falhou e não relê o que já foi tratado")
    void varreduraPeriodica() throws Exception {
        Files.writeString(pasta.resolve("em-uso.pdf"), "x");
        Files.writeString(pasta.resolve("ok.pdf"), "x");
        Map<String, AtomicInteger> chamadas = new ConcurrentHashMap<>();
        ProcessadorDeComprovantes processador = arquivo -> {
            String nome = arquivo.getFileName().toString();
            int vez = chamadas.computeIfAbsent(nome, n -> new AtomicInteger()).incrementAndGet();
            return !(nome.equals("em-uso.pdf") && vez == 1); // 1ª leitura falha (ex.: arquivo travado)
        };
        watcher = criar(pasta, true, RAPIDO, processador);

        thread.submit(watcher::monitorar);
        await().atMost(5, TimeUnit.SECONDS).until(() -> chamadas.containsKey("em-uso.pdf")
                && chamadas.get("em-uso.pdf").get() == 2);

        Thread.sleep(RAPIDO.toMillis() * 5); // várias varreduras depois...
        assertThat(chamadas.get("em-uso.pdf")).hasValue(2);
        assertThat(chamadas.get("ok.pdf")).hasValue(1);
        assertThat(watcher.getEstado().ultimaVarredura()).isNotNull();
    }

    @Test
    @DisplayName("Sem processar existentes: ignora o que já estava na pasta e importa só os novos")
    void somenteNovos() throws Exception {
        Files.writeString(pasta.resolve("historico.pdf"), "x");
        watcher = criar(pasta, false, RAPIDO, registrando());

        thread.submit(watcher::monitorar);
        await().atMost(5, TimeUnit.SECONDS).until(watcher::isAtivo);
        Thread.sleep(RAPIDO.toMillis() * 4);
        assertThat(processados).isEmpty();

        Files.writeString(pasta.resolve("novo.pdf"), "x");
        assertThat(processados.poll(15, TimeUnit.SECONDS)).isEqualTo("novo.pdf");
        Thread.sleep(RAPIDO.toMillis() * 4);
        assertThat(processados).isEmpty(); // não reprocessou pela varredura
    }
}
