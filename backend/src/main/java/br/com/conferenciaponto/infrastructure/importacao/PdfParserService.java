package br.com.conferenciaponto.infrastructure.importacao;

import br.com.conferenciaponto.domain.model.HashSha256;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leitura dos comprovantes do Ponto Fácil: trava o arquivo, extrai o texto com
 * Apache PDFBox e captura a data/hora com expressão regular.
 *
 * <p>Padrão alvo: {@code Comprovante de Ponto - dd/MM/yyyy HH:mm:ss}. A regex tolera
 * quebras de linha/espaços extras (comuns na extração de PDF), variações de traço
 * (-, –, —) e hora sem segundos.
 */
@Service
public class PdfParserService {

    public static final String REGEX_PADRAO =
            "Comprovante\\s+de\\s+Ponto\\s*[-\\u2010\\u2011\\u2012\\u2013\\u2014\\u2212:]?\\s*"
                    + "(?<data>\\d{2}/\\d{2}/\\d{4})\\s*(?:às|as|-)?\\s*"
                    + "(?<hora>\\d{2}:\\d{2}(?::\\d{2})?)";

    private static final DateTimeFormatter DATA =
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter HORA =
            DateTimeFormatter.ofPattern("HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT);

    private final Pattern padrao;
    private final long tamanhoMaximoBytes;

    @Autowired
    public PdfParserService(ImportacaoPdfProperties properties) {
        this(properties.regex(), properties.tamanhoMaximo().toBytes());
    }

    public PdfParserService(String regex, long tamanhoMaximoBytes) {
        if (!regex.contains("(?<data>") || !regex.contains("(?<hora>")) {
            throw new IllegalArgumentException("A regex precisa dos grupos nomeados (?<data>...) e (?<hora>...)");
        }
        this.padrao = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        this.tamanhoMaximoBytes = tamanhoMaximoBytes;
    }

    /** Conteúdo bruto do arquivo + hash SHA-256 (chave de deduplicação). */
    public record ArquivoLido(String nomeArquivo, byte[] conteudo, String hashSha256) {
    }

    /** Resultado completo da leitura de um comprovante (inclui os bytes, que serão arquivados). */
    public record ComprovanteLido(String nomeArquivo, String hashSha256, Optional<LocalDateTime> dataHora,
                                  byte[] conteudo) {
    }

    /**
     * Lê o PDF (com trava) e devolve a data/hora do comprovante.
     * PDF corrompido ou sem o padrão resulta em {@code dataHora} vazio.
     *
     * @throws ArquivoEmUsoException se o arquivo ainda estiver sendo gravado
     */
    public ComprovanteLido ler(Path arquivo) throws IOException {
        ArquivoLido lido = lerComBloqueio(arquivo);
        Optional<LocalDateTime> dataHora;
        try {
            dataHora = extrairDataHora(extrairTexto(lido.conteudo()));
        } catch (IOException | RuntimeException pdfInvalido) {
            dataHora = Optional.empty();
        }
        return new ComprovanteLido(lido.nomeArquivo(), lido.hashSha256(), dataHora, lido.conteudo());
    }

    /** PDF recebido pela tela (upload): mesmo tratamento do lido da pasta. */
    public ComprovanteLido lerConteudo(String nomeArquivo, byte[] conteudo) throws IOException {
        if (conteudo == null || conteudo.length == 0) {
            throw new IOException("O arquivo está vazio. Baixe o comprovante de novo.");
        }
        if (conteudo.length > tamanhoMaximoBytes) {
            throw new IOException("O arquivo passa do tamanho máximo de %d MB: não parece um comprovante de ponto."
                    .formatted(tamanhoMaximoBytes / 1024 / 1024));
        }
        Optional<LocalDateTime> dataHora;
        try {
            dataHora = extrairDataHora(extrairTexto(conteudo));
        } catch (IOException | RuntimeException pdfInvalido) {
            dataHora = Optional.empty();
        }
        return new ComprovanteLido(nomeArquivo, HashSha256.de(conteudo), dataHora, conteudo);
    }

    /**
     * Lê o arquivo inteiro sob uma trava compartilhada: enquanto a leitura acontece,
     * nenhum outro processo consegue alterar o conteúdo. Se outro processo detém uma
     * trava exclusiva (ex.: navegador ainda gravando), lança {@link ArquivoEmUsoException}.
     */
    public ArquivoLido lerComBloqueio(Path arquivo) throws IOException {
        try (FileChannel canal = FileChannel.open(arquivo, StandardOpenOption.READ)) {
            FileLock trava = canal.tryLock(0L, Long.MAX_VALUE, true);
            if (trava == null) {
                throw new ArquivoEmUsoException(arquivo, "travado por outro processo");
            }
            try {
                long tamanho = canal.size();
                if (tamanho == 0) {
                    throw new ArquivoEmUsoException(arquivo, "arquivo vazio (download em andamento?)");
                }
                if (tamanho > tamanhoMaximoBytes) {
                    throw new ArquivoRecusadoException("O arquivo passa do tamanho máximo de %d MB: não parece um "
                            .formatted(tamanhoMaximoBytes / 1024 / 1024) + "comprovante de ponto.");
                }
                ByteBuffer buffer = ByteBuffer.allocate((int) tamanho);
                while (buffer.hasRemaining() && canal.read(buffer) >= 0) {
                    // lê até o fim
                }
                byte[] conteudo = buffer.array();
                return new ArquivoLido(arquivo.getFileName().toString(), conteudo, HashSha256.de(conteudo));
            } finally {
                trava.release();
            }
        }
    }

    /** Extrai o texto plano de todas as páginas (ordenado pela posição na página). */
    public String extrairTexto(byte[] conteudoPdf) throws IOException {
        try (PDDocument documento = Loader.loadPDF(conteudoPdf)) {
            PDFTextStripper extrator = new PDFTextStripper();
            extrator.setSortByPosition(true);
            return extrator.getText(documento);
        }
    }

    /** Primeira data/hora válida que casa com o padrão (datas impossíveis, como 31/02, são ignoradas). */
    public Optional<LocalDateTime> extrairDataHora(String texto) {
        if (texto == null || texto.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = padrao.matcher(texto.replace(' ', ' '));
        while (matcher.find()) {
            try {
                LocalDate data = LocalDate.parse(matcher.group("data"), DATA);
                LocalTime hora = LocalTime.parse(matcher.group("hora"), HORA);
                return Optional.of(LocalDateTime.of(data, hora));
            } catch (DateTimeParseException ignorada) {
                // continua procurando uma ocorrência válida
            }
        }
        return Optional.empty();
    }
}
