package br.com.conferenciaponto.modulos.backup.infrastructure.coleta;

import br.com.conferenciaponto.modulos.backup.domain.ErroDeColeta;

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
 * quadro zstd cifrado (AES-256-GCM, com a chave do link, que este sistema não conhece). O cabeçalho leva o SHA-256 do
 * quadro cifrado, conferido aqui antes de aceitar (estragou no caminho: manda de novo).
 */
public record Envelope(JsonNode cabecalho, byte[] quadro) {

    private static final byte[] MAGICO = "RBK1".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_CABECALHO = 4 * 1024 * 1024;
    /** O quadro cifrado: 12 bytes do vetor de inicialização e 16 da etiqueta de autenticação, no mínimo. */
    private static final int MIN_CIFRADO = 12 + 16 + 1;

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
        // o conteúdo vem cifrado no navegador do cliente com a chave que só está no link (depois do #): nem este
        // sistema nem o túnel conseguem lê-lo. Pedaço sem cifra é recusado.
        if (!cabecalho.path("cifrado").asBoolean(false) || quadro.length < MIN_CIFRADO) {
            throw new ErroDeColeta(400, "PEDACO_SEM_CIFRA", "Pedaço sem criptografia: recarregue a página pelo link completo.");
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
