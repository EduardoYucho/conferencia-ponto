package br.com.receptorbackup.coleta;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Um pedaço como a página manda: {@code "RBK1"}, tamanho do cabeçalho (4 bytes, big-endian), cabeçalho JSON e o
 * quadro zstd. O cabeçalho leva o SHA-256 do quadro, conferido aqui antes de aceitar.
 */
public record Envelope(JsonNode cabecalho, byte[] quadro) {

    private static final byte[] MAGICO = "RBK1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MAGICO_ZSTD = { 0x28, (byte) 0xB5, 0x2F, (byte) 0xFD };
    private static final int MAX_CABECALHO = 16 * 1024 * 1024;

    public static Envelope ler(byte[] corpo, ObjectMapper json) {
        if (corpo.length < 8 || !Arrays.equals(Arrays.copyOf(corpo, 4), MAGICO)) {
            throw new ErroDeColeta(400, "PEDACO_INVALIDO", "Pedaço em formato desconhecido.");
        }
        int tamanhoCabecalho = ByteBuffer.wrap(corpo, 4, 4).getInt();
        if (tamanhoCabecalho <= 0 || tamanhoCabecalho > MAX_CABECALHO || 8L + tamanhoCabecalho > corpo.length) {
            throw new ErroDeColeta(400, "PEDACO_INVALIDO", "Cabeçalho do pedaço com tamanho inválido.");
        }
        JsonNode cabecalho;
        try {
            cabecalho = json.readTree(new String(corpo, 8, tamanhoCabecalho, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ErroDeColeta(400, "PEDACO_INVALIDO", "Cabeçalho do pedaço não é JSON.");
        }
        byte[] quadro = Arrays.copyOfRange(corpo, 8 + tamanhoCabecalho, corpo.length);
        if (quadro.length < 4 || !Arrays.equals(Arrays.copyOf(quadro, 4), MAGICO_ZSTD)) {
            throw new ErroDeColeta(400, "PEDACO_INVALIDO", "O pedaço não traz um quadro zstd.");
        }
        String esperado = cabecalho.path("shaQuadro").asText("");
        if (!esperado.equalsIgnoreCase(sha256(quadro))) {
            throw new ErroDeColeta(422, "PEDACO_CORROMPIDO", "O pedaço chegou diferente do enviado; mande de novo.");
        }
        return new Envelope(cabecalho, quadro);
    }

    public static String sha256(byte[] dados) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dados));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Monta um envelope (o que a página faz; usado nos testes). */
    public static byte[] montar(JsonNode cabecalho, byte[] quadro, ObjectMapper json) {
        try {
            byte[] cab = json.writeValueAsBytes(cabecalho);
            ByteBuffer saida = ByteBuffer.allocate(8 + cab.length + quadro.length);
            saida.put(MAGICO).putInt(cab.length).put(cab).put(quadro);
            return saida.array();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
