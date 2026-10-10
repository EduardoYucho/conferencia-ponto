package br.com.conferenciaponto.modulos.atendimento.infrastructure.download;

import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.Baixador;
import br.com.conferenciaponto.modulos.atendimento.domain.arquivo.FalhaNoDownload;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.DigisacProperties;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.config.DownloadProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Download dos anexos com {@link HttpURLConnection}: tempo limite de conexão e de leitura (um servidor que para de
 * mandar dados não prende a tarefa), sem seguir redirecionamento, com limite de tamanho e continuando de onde
 * parou ({@code Range}) quando a conexão cai. O log registra só o host e o tamanho; a URL nunca.
 */
@Component
public class BaixadorHttp implements Baixador {

    private static final Logger log = LoggerFactory.getLogger(BaixadorHttp.class);
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)");
    /** Quedas seguidas sem nenhum byte novo antes de desistir desta tentativa (a fila tenta de novo depois). */
    private static final int QUEDAS_SEM_PROGRESSO = 3;
    /** Retomadas numa mesma tentativa, mesmo com progresso (para não ficar preso num servidor instável). */
    private static final int RETOMADAS = 20;

    private final PoliticaDeRede politica;
    private final Duration tempoConexao;
    private final Duration tempoLeitura;

    @Autowired
    public BaixadorHttp(DigisacProperties digisac, DownloadProperties download) {
        this(PoliticaDeRede.producao(digisac.hostsPermitidos()), download.tempoConexao(), download.tempoLeitura());
    }

    public BaixadorHttp(PoliticaDeRede politica, Duration tempoConexao, Duration tempoLeitura) {
        this.politica = politica;
        this.tempoConexao = tempoConexao;
        this.tempoLeitura = tempoLeitura;
    }

    @Override
    public long baixar(String url, Path parcial, long limite) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw FalhaNoDownload.definitiva("DOWNLOAD_RECUSADO", "O link do anexo é inválido. Envie o arquivo à mão.");
        }
        politica.conferir(uri);
        try {
            Files.createDirectories(parcial.getParent());
        } catch (IOException e) {
            throw erroDeDisco(e, parcial);
        }
        int semProgresso = 0;
        for (int retomada = 0; retomada <= RETOMADAS; retomada++) {
            long antes = tamanho(parcial);
            try {
                return tentar(uri, parcial, limite);
            } catch (Queda queda) {
                if (tamanho(parcial) > antes) {
                    semProgresso = 0;
                } else if (++semProgresso >= QUEDAS_SEM_PROGRESSO) {
                    log.info("Download de {} caiu {} vezes sem progresso ({})", uri.getHost(), semProgresso, queda.motivo);
                    throw FalhaNoDownload.temporaria("DOWNLOAD_FALHOU", "A conexão com o armazenamento do Digisac caiu. "
                            + "Uma nova tentativa será feita, continuando de onde parou.");
                }
            }
        }
        throw FalhaNoDownload.temporaria("DOWNLOAD_FALHOU", "A conexão com o armazenamento do Digisac está instável. "
                + "Uma nova tentativa será feita, continuando de onde parou.");
    }

    /** A conexão caiu no meio (ou nem abriu): vale continuar de onde parou. */
    private static final class Queda extends Exception {
        final String motivo;

        Queda(String motivo) {
            super(motivo, null, false, false);
            this.motivo = motivo;
        }
    }

    private long tentar(URI uri, Path parcial, long limite) throws Queda {
        long ja = tamanho(parcial);
        HttpURLConnection conexao = null;
        try {
            URLConnection aberta = uri.toURL().openConnection();
            if (!(aberta instanceof HttpURLConnection http)) {
                throw FalhaNoDownload.definitiva("DOWNLOAD_RECUSADO", "O link do anexo é inválido. Envie o arquivo à mão.");
            }
            conexao = http;
            conexao.setInstanceFollowRedirects(false);
            conexao.setConnectTimeout((int) tempoConexao.toMillis());
            conexao.setReadTimeout((int) tempoLeitura.toMillis());
            conexao.setUseCaches(false);
            conexao.setRequestProperty("Accept-Encoding", "identity");
            conexao.setRequestProperty("User-Agent", "conferencia-ponto/atendimentos");
            if (ja > 0) {
                conexao.setRequestProperty("Range", "bytes=" + ja + "-");
            }
            int status = conexao.getResponseCode();
            if (status == 416 && ja > 0) {
                long total = totalDoContentRange(conexao.getHeaderField("Content-Range"));
                if (total == ja) {
                    return ja; // já estava completo
                }
                truncar(parcial);
                throw new Queda("pedido parcial recusado (416)");
            }
            recusarSeErro(status, conexao, uri);
            boolean continuar = status == 206;
            long total;
            if (continuar) {
                Matcher m = CONTENT_RANGE.matcher(String.valueOf(conexao.getHeaderField("Content-Range")));
                if (!m.find() || Long.parseLong(m.group(1)) != ja) {
                    truncar(parcial); // o servidor mandou outro pedaço: começa do zero
                    throw new Queda("pedaço diferente do pedido");
                }
                total = m.group(3).equals("*") ? -1 : Long.parseLong(m.group(3));
            } else {
                if (ja > 0) {
                    truncar(parcial); // ignorou o Range e mandou tudo de novo
                    ja = 0;
                }
                total = conexao.getContentLengthLong();
            }
            if (total > limite) {
                apagar(parcial);
                throw grandeDemais(limite);
            }
            long gravados = ja;
            try (InputStream entrada = conexao.getInputStream();
                 OutputStream saida = Files.newOutputStream(parcial, StandardOpenOption.CREATE,
                         continuar ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                int lidos;
                while (true) {
                    try {
                        lidos = entrada.read(buffer);
                    } catch (IOException e) {
                        throw new Queda(e.getClass().getSimpleName());
                    }
                    if (lidos == -1) {
                        break;
                    }
                    gravados += lidos;
                    if (gravados > limite) {
                        saida.close();
                        apagar(parcial);
                        throw grandeDemais(limite);
                    }
                    saida.write(buffer, 0, lidos);
                }
            }
            if (total >= 0 && gravados < total) {
                throw new Queda("terminou antes do tamanho anunciado");
            }
            log.info("Anexo baixado de {}: {} bytes{}", uri.getHost(), gravados, ja > 0 ? " (continuando de " + ja + ")" : "");
            return gravados;
        } catch (FalhaNoDownload | Queda e) {
            throw e;
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw FalhaNoDownload.definitiva("DOWNLOAD_RECUSADO", "O link do anexo é inválido. Envie o arquivo à mão.");
        } catch (IOException e) {
            if (semEspaco(e, parcial)) {
                throw erroDeDisco(e, parcial);
            }
            throw new Queda(e.getClass().getSimpleName());
        } finally {
            if (conexao != null) {
                conexao.disconnect();
            }
        }
    }

    private void recusarSeErro(int status, HttpURLConnection conexao, URI uri) {
        if (status == 200 || status == 206) {
            return;
        }
        log.info("Download de {} recusado com HTTP {}", uri.getHost(), status);
        if (status >= 300 && status < 400) {
            throw new FalhaNoDownload("DOWNLOAD_RECUSADO", "O endereço do anexo tentou redirecionar para outro lugar; por "
                    + "segurança, o download não segue redirecionamentos. Envie o arquivo à mão.", false, status);
        }
        if (status == 401 || status == 403) {
            throw new FalhaNoDownload("DOWNLOAD_RECUSADO", "O armazenamento do Digisac recusou o download (o link pode "
                    + "ter vencido). Exporte a conversa de novo no Digisac e envie o PDF neste atendimento, ou envie o "
                    + "arquivo à mão.", false, status);
        }
        if (status == 404 || status == 410) {
            throw new FalhaNoDownload("DOWNLOAD_RECUSADO", "O arquivo não existe mais no armazenamento do Digisac. Envie o "
                    + "arquivo à mão.", false, status);
        }
        if (status == 408 || status == 429 || status >= 500) {
            throw new FalhaNoDownload("DOWNLOAD_FALHOU", "O armazenamento do Digisac não respondeu agora (HTTP " + status
                    + "). Uma nova tentativa será feita.", true, status);
        }
        throw new FalhaNoDownload("DOWNLOAD_RECUSADO", "O armazenamento do Digisac recusou o download (HTTP " + status
                + "). Envie o arquivo à mão.", false, status);
    }

    private static long totalDoContentRange(String cabecalho) {
        if (cabecalho == null) {
            return -1;
        }
        Matcher m = Pattern.compile("/(\\d+)").matcher(cabecalho);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    private static FalhaNoDownload grandeDemais(long limite) {
        return FalhaNoDownload.definitiva("ARQUIVO_GRANDE_DEMAIS", "Não suportado: o arquivo é maior que "
                + legivel(limite) + ".");
    }

    static String legivel(long bytes) {
        if (bytes >= 1L << 30) {
            return (bytes >> 30) + " GB";
        }
        return Math.max(1, bytes >> 20) + " MB";
    }

    private static FalhaNoDownload erroDeDisco(IOException e, Path parcial) {
        if (semEspaco(e, parcial)) {
            return FalhaNoDownload.definitiva("DISCO_CHEIO", "O disco do servidor está cheio. O atendimento foi pausado: "
                    + "libere espaço e retome.");
        }
        return FalhaNoDownload.temporaria("DOWNLOAD_FALHOU", "Não foi possível gravar o anexo no servidor agora. Uma nova "
                + "tentativa será feita.");
    }

    /** Disco cheio: pela mensagem do sistema (Windows e Linux) ou pelo espaço que sobrou. */
    static boolean semEspaco(IOException e, Path parcial) {
        String mensagem = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (mensagem.contains("no space left") || mensagem.contains("not enough space") || mensagem.contains("disk full")
                || mensagem.contains("espaço insuficiente") || mensagem.contains("espaco insuficiente")) {
            return true;
        }
        try {
            Path pasta = parcial.getParent();
            return pasta != null && Files.exists(pasta) && Files.getFileStore(pasta).getUsableSpace() < 1024 * 1024;
        } catch (IOException outra) {
            return false;
        }
    }

    private static long tamanho(Path arquivo) {
        try {
            return Files.exists(arquivo) ? Files.size(arquivo) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static void truncar(Path arquivo) {
        try {
            Files.deleteIfExists(arquivo);
        } catch (IOException e) {
            log.warn("Não foi possível recomeçar o download parcial: {}", e.toString());
        }
    }

    private static void apagar(Path arquivo) {
        truncar(arquivo);
    }
}
