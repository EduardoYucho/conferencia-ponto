package br.com.expedio.modulos.backup.infrastructure.tunel;

import br.com.expedio.modulos.backup.infrastructure.config.ConfiguracaoDoBackup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * O endereço público por onde o navegador do cliente chega ao sistema.
 *
 * <p>Com {@code backup-clientes.endereco-publico} preenchido (túnel nomeado, com domínio próprio, rodando à parte), usa esse.
 * Senão, se o cloudflared estiver instalado, sobe um túnel rápido ({@code cloudflared tunnel --url}) para a porta
 * pública e lê o endereço {@code https://....trycloudflare.com} que ele imprime. O túnel rápido muda de endereço cada
 * vez que sobe: os links mostrados na tela são sempre montados com o endereço atual.
 */
@Service
public class ServicoDeTunel {

    private static final Logger LOG = LoggerFactory.getLogger(ServicoDeTunel.class);
    private static final Pattern ENDERECO = Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");

    public record Situacao(String modo, String endereco, String mensagem, Instant desde) {
    }

    private final ConfiguracaoDoBackup configuracao;
    private volatile Process processo;
    private volatile Situacao situacao = new Situacao("DESLIGADO", null, "Túnel ainda não iniciado.", Instant.now());
    private volatile boolean encerrando;
    private final List<String> ultimasLinhas = new ArrayList<>();

    public ServicoDeTunel(ConfiguracaoDoBackup configuracao) {
        this.configuracao = configuracao;
    }

    public Situacao situacao() {
        return situacao;
    }

    public synchronized List<String> ultimasLinhas() {
        return List.copyOf(ultimasLinhas);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void iniciar() {
        if (!configuracao.tunelAtivo() || configuracao.portaPublica() <= 0) {
            situacao = new Situacao("DESLIGADO", null,
                    "Túnel desligado na configuração (backup-clientes.tunel-ativo): os links só abrem nesta rede.",
                    Instant.now());
            return;
        }
        String fixo = configuracao.enderecoPublico();
        if (fixo != null && !fixo.isBlank()) {
            String endereco = fixo.trim().replaceAll("/+$", "");
            situacao = new Situacao("FIXO", endereco, "Endereço fixo da configuração.", Instant.now());
            LOG.info("Endereço público fixo: {}", endereco);
            return;
        }
        Optional<Path> executavel = localizarCloudflared();
        if (executavel.isEmpty()) {
            situacao = new Situacao("SEM_CLOUDFLARED", null,
                    "Falta o cloudflared (o túnel que leva o link até este computador). Num terminal: expedio tunel",
                    Instant.now());
            LOG.warn("cloudflared não encontrado: sem endereço público para os backups de clientes (veja: expedio tunel)");
            return;
        }
        encerrarSobra();
        Thread vigia = new Thread(() -> manterTunel(executavel.get()), "backup-tunel");
        vigia.setDaemon(true);
        vigia.start();
    }

    private Path arquivoPid() {
        return configuracao.pastaInterna().resolve("cloudflared.pid");
    }

    private void anotarPid(long pid) {
        try {
            Files.createDirectories(arquivoPid().getParent());
            Files.writeString(arquivoPid(), Long.toString(pid));
        } catch (IOException e) {
            LOG.debug("Não consegui anotar o pid do cloudflared: {}", e.getMessage());
        }
    }

    /**
     * O cloudflared que sobrou da vez anterior: "expedio parar" derruba a aplicação à força e o filho continua vivo.
     * Sem isto, cada reinício deixaria mais um túnel aberto.
     */
    private void encerrarSobra() {
        try {
            if (!Files.exists(arquivoPid())) {
                return;
            }
            long pid = Long.parseLong(Files.readString(arquivoPid()).trim());
            ProcessHandle.of(pid)
                    .filter(p -> p.info().command().map(c -> c.toLowerCase().contains("cloudflared")).orElse(false))
                    .ifPresent(p -> {
                        LOG.info("Encerrando o cloudflared que sobrou da vez anterior (pid {})", pid);
                        p.destroy();
                    });
            Files.deleteIfExists(arquivoPid());
        } catch (IOException | RuntimeException e) {
            LOG.debug("Sobra do cloudflared não conferida: {}", e.getMessage());
        }
    }

    /** Reinicia o túnel rápido (gera endereço novo). */
    public void reiniciar() {
        Process atual = processo;
        if (atual != null) {
            atual.destroy();
        }
    }

    /**
     * Pede um arquivo da página de coleta pelo endereço público até ele responder (no máximo 5 minutos) e só então
     * marca o túnel como ativo, com o endereço que vai nos links.
     */
    private void esperarAcessivel(String endereco, Process doTunel) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest pedido = HttpRequest.newBuilder(URI.create(endereco + "/c/_/coleta.css"))
                .timeout(Duration.ofSeconds(15)).GET().build();
        Instant limite = Instant.now().plus(Duration.ofMinutes(5));
        while (!encerrando && doTunel.isAlive() && processo == doTunel) {
            try {
                if (http.send(pedido, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    situacao = new Situacao("ATIVO", endereco, "Túnel rápido do Cloudflare ativo.", Instant.now());
                    LOG.info("Túnel ativo: {}", endereco);
                    return;
                }
            } catch (IOException e) {
                // ainda sem DNS ou sem resposta: tenta de novo
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (Instant.now().isAfter(limite)) {
                // não deu para conferir daqui (rede deste computador?): libera mesmo assim, com o aviso
                situacao = new Situacao("ATIVO", endereco,
                        "Túnel ativo, mas não consegui abrir o endereço daqui para conferir.", Instant.now());
                LOG.warn("Túnel {}: não respondeu daqui em 5 minutos; liberado mesmo assim", endereco);
                return;
            }
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void manterTunel(Path executavel) {
        long espera = 5_000;
        while (!encerrando) {
            situacao = new Situacao("INICIANDO", null, "Abrindo o túnel...", Instant.now());
            try {
                ProcessBuilder construtor = new ProcessBuilder(executavel.toString(), "tunnel", "--no-autoupdate",
                        "--url", "http://127.0.0.1:" + configuracao.portaPublica());
                construtor.redirectErrorStream(true);
                Process novo = construtor.start();
                processo = novo;
                anotarPid(novo.pid());
                Instant inicio = Instant.now();
                try (BufferedReader leitor = new BufferedReader(
                        new InputStreamReader(novo.getInputStream(), StandardCharsets.UTF_8))) {
                    String linha;
                    while ((linha = leitor.readLine()) != null) {
                        guardarLinha(linha);
                        Matcher achou = ENDERECO.matcher(linha);
                        if (achou.find() && "INICIANDO".equals(situacao.modo())) {
                            // o endereço novo leva até uns minutos para existir no DNS: o link só é liberado quando
                            // ele abre daqui (um link aberto antes disso falha no cliente, e o navegador guarda a falha)
                            String endereco = achou.group();
                            situacao = new Situacao("VERIFICANDO", null,
                                    "Túnel aberto; esperando o endereço novo ficar acessível na internet (até 1 ou 2 minutos)...",
                                    Instant.now());
                            Thread conferir = new Thread(() -> esperarAcessivel(endereco, novo), "backup-tunel-dns");
                            conferir.setDaemon(true);
                            conferir.start();
                            espera = 5_000;
                        }
                    }
                }
                int codigo = novo.waitFor();
                if (encerrando) {
                    return;
                }
                LOG.warn("cloudflared saiu (código {}); subindo de novo em {} s", codigo, espera / 1000);
                if (Instant.now().isAfter(inicio.plusSeconds(120))) {
                    espera = 5_000;
                }
            } catch (IOException e) {
                LOG.warn("Não consegui rodar o cloudflared: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            situacao = new Situacao("CAIU", null, "O túnel caiu; abrindo de novo (o endereço vai mudar).",
                    Instant.now());
            try {
                Thread.sleep(espera);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            espera = Math.min(espera * 2, 120_000);
        }
    }

    private synchronized void guardarLinha(String linha) {
        ultimasLinhas.add(linha);
        while (ultimasLinhas.size() > 40) {
            ultimasLinhas.remove(0);
        }
    }

    Optional<Path> localizarCloudflared() {
        String configurado = configuracao.cloudflared();
        if (configurado != null && !configurado.isBlank()) {
            Path caminho = Path.of(configurado.trim());
            return Files.isRegularFile(caminho) ? Optional.of(caminho) : Optional.empty();
        }
        List<Path> candidatos = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String pasta : path.split(java.io.File.pathSeparator)) {
                if (!pasta.isBlank()) {
                    candidatos.add(Path.of(pasta.trim()).resolve("cloudflared.exe"));
                    candidatos.add(Path.of(pasta.trim()).resolve("cloudflared"));
                }
            }
        }
        candidatos.add(Path.of("C:\\Program Files (x86)\\cloudflared\\cloudflared.exe"));
        candidatos.add(Path.of("C:\\Program Files\\cloudflared\\cloudflared.exe"));
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            candidatos.add(Path.of(local, "Microsoft", "WinGet", "Links", "cloudflared.exe"));
        }
        return candidatos.stream().filter(Files::isRegularFile).findFirst();
    }

    @PreDestroy
    void parar() {
        encerrando = true;
        Process atual = processo;
        if (atual != null) {
            atual.destroy();
        }
    }
}
