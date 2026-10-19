package br.com.conferenciaponto.modulos.atendimento.application.redacao;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Os áudios do atendimento num WAV só, um depois do outro com 1 s de silêncio entre eles, e onde cada um começa e
 * termina (o mapa vai no pedido ao app do Gemini). Os WAVs de entrada são os que o ffmpeg converte: mono, 16 kHz e
 * 16 bits.
 */
final class JuncaoDeAudios {

    static final int TAXA = 16_000;
    static final int BYTES_POR_SEGUNDO = TAXA * 2;
    static final int SILENCIO = BYTES_POR_SEGUNDO;

    /** Um trecho do arquivo junto: do início ao fim, em milissegundos. */
    record Trecho(long inicioMs, long fimMs) {
    }

    private JuncaoDeAudios() {
    }

    static List<Trecho> juntar(List<Path> wavs, Path destino) throws IOException {
        List<long[]> dados = new ArrayList<>(); // [início dos dados, tamanho] de cada WAV
        long total = 0;
        for (Path wav : wavs) {
            long[] d = dados(wav);
            dados.add(d);
            total += d[1];
        }
        total += (long) SILENCIO * Math.max(0, wavs.size() - 1);
        if (total > Integer.MAX_VALUE - 64) {
            throw new IOException("áudio longo demais para um WAV");
        }
        List<Trecho> trechos = new ArrayList<>();
        Files.createDirectories(destino.getParent());
        try (OutputStream saida = Files.newOutputStream(destino)) {
            saida.write(cabecalho((int) total));
            long posicao = 0;
            byte[] silencio = new byte[SILENCIO];
            for (int i = 0; i < wavs.size(); i++) {
                if (i > 0) {
                    saida.write(silencio);
                    posicao += SILENCIO;
                }
                long inicio = posicao;
                try (InputStream entrada = Files.newInputStream(wavs.get(i))) {
                    entrada.skipNBytes(dados.get(i)[0]);
                    long falta = dados.get(i)[1];
                    byte[] buffer = new byte[64 * 1024];
                    while (falta > 0) {
                        int lidos = entrada.read(buffer, 0, (int) Math.min(buffer.length, falta));
                        if (lidos < 0) {
                            break;
                        }
                        saida.write(buffer, 0, lidos);
                        falta -= lidos;
                    }
                }
                posicao += dados.get(i)[1];
                trechos.add(new Trecho(inicio * 1000 / BYTES_POR_SEGUNDO, posicao * 1000 / BYTES_POR_SEGUNDO));
            }
        }
        return trechos;
    }

    /** Onde começam os dados do WAV e quantos bytes eles têm (o pedaço "data"). */
    static long[] dados(Path wav) throws IOException {
        try (InputStream entrada = Files.newInputStream(wav)) {
            byte[] riff = entrada.readNBytes(12);
            if (riff.length < 12 || riff[0] != 'R' || riff[1] != 'I' || riff[2] != 'F' || riff[3] != 'F') {
                throw new IOException("não é WAV");
            }
            long posicao = 12;
            while (true) {
                byte[] pedaco = entrada.readNBytes(8);
                if (pedaco.length < 8) {
                    throw new IOException("WAV sem dados");
                }
                long tamanho = ByteBuffer.wrap(pedaco, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
                posicao += 8;
                if (pedaco[0] == 'd' && pedaco[1] == 'a' && pedaco[2] == 't' && pedaco[3] == 'a') {
                    long arquivo = Files.size(wav);
                    return new long[]{posicao, Math.min(tamanho, arquivo - posicao)};
                }
                entrada.skipNBytes(tamanho + (tamanho % 2));
                posicao += tamanho + (tamanho % 2);
            }
        }
    }

    static byte[] cabecalho(int bytesDeDados) {
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + bytesDeDados).put(new byte[]{'W', 'A', 'V', 'E'});
        b.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1).putInt(TAXA)
                .putInt(BYTES_POR_SEGUNDO).putShort((short) 2).putShort((short) 16);
        b.put(new byte[]{'d', 'a', 't', 'a'}).putInt(bytesDeDados);
        return b.array();
    }
}
