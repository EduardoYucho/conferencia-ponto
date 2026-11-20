package br.com.receptorbackup.tunel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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

import br.com.receptorbackup.config.Propriedades;
import jakarta.annotation.PreDestroy;

/**
 * O endereço público por onde o navegador do cliente chega ao Receptor.
 *
 * <p>Com {@code receptor.endereco-publico} preenchido (túnel nomeado, com domínio próprio, rodando à parte), usa esse.
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

    private final Propriedades propriedades;
    private volatile Process processo;
    private volatile Situacao situacao = new Situacao("DESLIGADO", null, "Túnel ainda não iniciado.", Instant.now());
    private volatile boolean encerrando;
    private final List<String> ultimasLinhas = new ArrayList<>();

    public ServicoDeTunel(Propriedades propriedades) {
        this.propriedades = propriedades;
    }

    public Situacao situacao() {
        return situacao;
    }

    public synchronized List<String> ultimasLinhas() {
        return List.copyOf(ultimasLinhas);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void iniciar() {
        String fixo = propriedades.enderecoPublico();
        if (fixo != null && !fixo.isBlank()) {
            String endereco = fixo.trim().replaceAll("/+$", "");
            situacao = new Situacao("FIXO", endereco, "Endereço fixo da configuração.", Instant.now());
            LOG.info("Endereço público fixo: {}", endereco);
            return;
        }
        Optional<Path> executavel = localizarCloudflared();
        if (executavel.isEmpty()) {
            situacao = new Situacao("SEM_CLOUDFLARED", null,
                    "cloudflared não encontrado. Instale com: winget install --id Cloudflare.cloudflared", Instant.now());
            LOG.warn("cloudflared não encontrado: sem endereço público (os links só funcionam nesta máquina)");
            return;
        }
        Thread vigia = new Thread(() -> manterTunel(executavel.get()), "receptor-tunel");
        vigia.setDaemon(true);
        vigia.start();
    }

    /** Reinicia o túnel rápido (gera endereço novo). */
    public void reiniciar() {
        Process atual = processo;
        if (atual != null) {
            atual.destroy();
        }
    }

    private void manterTunel(Path executavel) {
        long espera = 5_000;
        while (!encerrando) {
            situacao = new Situacao("INICIANDO", null, "Abrindo o túnel...", Instant.now());
            try {
                ProcessBuilder construtor = new ProcessBuilder(executavel.toString(), "tunnel", "--no-autoupdate",
                        "--url", "http://127.0.0.1:" + propriedades.portaPublica());
                construtor.redirectErrorStream(true);
                Process novo = construtor.start();
                processo = novo;
                Instant inicio = Instant.now();
                try (BufferedReader leitor = new BufferedReader(
                        new InputStreamReader(novo.getInputStream(), StandardCharsets.UTF_8))) {
                    String linha;
                    while ((linha = leitor.readLine()) != null) {
                        guardarLinha(linha);
                        Matcher achou = ENDERECO.matcher(linha);
                        if (achou.find() && !"ATIVO".equals(situacao.modo())) {
                            situacao = new Situacao("ATIVO", achou.group(), "Túnel rápido do Cloudflare ativo.",
                                    Instant.now());
                            LOG.info("Túnel ativo: {}", achou.group());
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
        String configurado = propriedades.cloudflared();
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
