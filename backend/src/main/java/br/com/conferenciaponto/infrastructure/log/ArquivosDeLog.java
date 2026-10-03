package br.com.conferenciaponto.infrastructure.log;

import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Os arquivos de log por usuário e por hora ({@code usuarios/<login>/<aaaa-mm-dd>/<hh>h.log}): lista o que
 * existe, lê um arquivo e apaga os dias mais antigos que o prazo de guarda.
 */
@Component
public class ArquivosDeLog {

    /** Quanto de um arquivo é devolvido para a tela (o fim dele, onde está o que acabou de acontecer). */
    static final int BYTES_NA_TELA = 400_000;

    /** @param bytes tamanho do arquivo */
    public record Hora(int hora, long bytes) {
    }

    public record Dia(LocalDate data, List<Hora> horas) {
    }

    /** @param login nome da pasta: o login do usuário ou "sistema" */
    public record Pasta(String login, List<Dia> dias) {
    }

    /** @param cortado o arquivo é maior do que o devolvido: veio só o fim */
    public record Conteudo(String login, LocalDate data, int hora, long bytes, boolean cortado, String texto) {
    }

    private static final Logger log = LoggerFactory.getLogger(ArquivosDeLog.class);
    private static final Pattern LOGIN = Pattern.compile("[a-z0-9_][a-z0-9._-]{0,59}");
    private static final Pattern ARQUIVO = Pattern.compile("([01]\\d|2[0-3])h\\.log");

    private final LogsProperties properties;
    private final Clock clock;

    public ArquivosDeLog(LogsProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Pastas por usuário ("sistema" primeiro), com os dias do mais recente para o mais antigo. */
    public List<Pasta> listar() {
        Path raiz = properties.pastaDosUsuarios();
        List<Pasta> pastas = new ArrayList<>();
        for (Path pastaUsuario : filhos(raiz)) {
            String login = pastaUsuario.getFileName().toString();
            if (!Files.isDirectory(pastaUsuario) || !LOGIN.matcher(login).matches()) {
                continue;
            }
            List<Dia> dias = new ArrayList<>();
            for (Path pastaDia : filhos(pastaUsuario)) {
                LocalDate data = data(pastaDia.getFileName().toString());
                if (data == null || !Files.isDirectory(pastaDia)) {
                    continue;
                }
                List<Hora> horas = new ArrayList<>();
                for (Path arquivo : filhos(pastaDia)) {
                    String nome = arquivo.getFileName().toString();
                    if (ARQUIVO.matcher(nome).matches()) {
                        horas.add(new Hora(Integer.parseInt(nome.substring(0, 2)), tamanho(arquivo)));
                    }
                }
                horas.sort(Comparator.comparingInt(Hora::hora).reversed());
                if (!horas.isEmpty()) {
                    dias.add(new Dia(data, horas));
                }
            }
            dias.sort(Comparator.comparing(Dia::data).reversed());
            if (!dias.isEmpty()) {
                pastas.add(new Pasta(login, dias));
            }
        }
        pastas.sort(Comparator.comparing((Pasta p) -> !p.login().equals(ContextoDeLog.SISTEMA)).thenComparing(Pasta::login));
        return pastas;
    }

    /** O fim do arquivo daquela hora (até {@link #BYTES_NA_TELA}). */
    public Conteudo ler(String login, LocalDate data, int hora) {
        if (login == null || !LOGIN.matcher(login).matches() || data == null || hora < 0 || hora > 23) {
            throw new RegraNegocioException("LOG_INVALIDO", "Escolha o usuário, o dia e a hora do log.");
        }
        Path raiz = properties.pastaDosUsuarios();
        Path arquivo = raiz.resolve(login).resolve(data.toString()).resolve("%02dh.log".formatted(hora)).normalize();
        if (!arquivo.startsWith(raiz) || !Files.isRegularFile(arquivo)) {
            throw new RecursoNaoEncontradoException("LOG_NAO_ENCONTRADO",
                    "Não há log de %s nesse dia e hora.".formatted(login));
        }
        try (RandomAccessFile leitor = new RandomAccessFile(arquivo.toFile(), "r")) {
            long bytes = leitor.length();
            long inicio = Math.max(0, bytes - BYTES_NA_TELA);
            byte[] conteudo = new byte[(int) (bytes - inicio)];
            leitor.seek(inicio);
            leitor.readFully(conteudo);
            String texto = new String(conteudo, StandardCharsets.UTF_8);
            if (inicio > 0) {
                // começa numa linha inteira
                int quebra = texto.indexOf('\n');
                texto = quebra < 0 ? texto : texto.substring(quebra + 1);
            }
            return new Conteudo(login, data, hora, bytes, inicio > 0, texto);
        } catch (IOException e) {
            log.warn("Não foi possível ler o log {}: {}", arquivo, e.toString());
            throw new RegraNegocioException("LOG_ILEGIVEL", "Não foi possível ler esse arquivo de log agora. Tente de novo.");
        }
    }

    /** Todo dia, apaga as pastas de dias mais antigos que o prazo de guarda ({@code ponto.logs.dias}). */
    @Scheduled(cron = "0 20 0 * * *")
    public void limparAntigos() {
        int apagados = limpar(LocalDate.now(clock));
        if (apagados > 0) {
            log.info("Logs por usuário: {} dia(s) com mais de {} dias apagado(s)", apagados, properties.dias());
        }
    }

    /** @return quantas pastas de dia foram apagadas */
    int limpar(LocalDate hoje) {
        if (properties.dias() <= 0) {
            return 0;
        }
        LocalDate limite = hoje.minusDays(properties.dias());
        int apagados = 0;
        for (Path pastaUsuario : filhos(properties.pastaDosUsuarios())) {
            if (!Files.isDirectory(pastaUsuario)) {
                continue;
            }
            for (Path pastaDia : filhos(pastaUsuario)) {
                LocalDate data = data(pastaDia.getFileName().toString());
                if (data != null && data.isBefore(limite) && apagar(pastaDia)) {
                    apagados++;
                }
            }
        }
        return apagados;
    }

    private static boolean apagar(Path pastaDia) {
        try {
            for (Path arquivo : filhos(pastaDia)) {
                Files.deleteIfExists(arquivo);
            }
            Files.deleteIfExists(pastaDia);
            return true;
        } catch (IOException e) {
            log.warn("Não foi possível apagar os logs antigos em {}: {}", pastaDia, e.toString());
            return false;
        }
    }

    private static List<Path> filhos(Path pasta) {
        List<Path> filhos = new ArrayList<>();
        if (!Files.isDirectory(pasta)) {
            return filhos;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pasta)) {
            stream.forEach(filhos::add);
        } catch (IOException e) {
            log.warn("Não foi possível listar {}: {}", pasta, e.toString());
        }
        return filhos;
    }

    private static LocalDate data(String nome) {
        try {
            return LocalDate.parse(nome);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static long tamanho(Path arquivo) {
        try {
            return Files.size(arquivo);
        } catch (IOException e) {
            return 0;
        }
    }
}
