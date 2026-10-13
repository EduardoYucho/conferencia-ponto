package br.com.conferenciaponto.modulos.atendimento.domain.video;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Áudio e vídeo pelo ffmpeg, para o pedido ao app do Gemini: os áudios viram um arquivo só (o app aceita até 10
 * arquivos por pedido e nem todo formato do WhatsApp) e a duração entra nos avisos dos limites do app.
 */
public interface ConversorDeMidia {

    /** O ffmpeg está instalado agora. */
    boolean disponivel();

    /**
     * O áudio em WAV mono de 16 kHz e 16 bits (a gravação é atômica).
     *
     * @throws FalhaNoQuadro FFMPEG_AUSENTE ou FFMPEG_FALHOU (o arquivo não abriu)
     */
    void paraWav(Path origem, Path destino);

    /** O WAV em MP3 (bem menor para enviar); false se não deu, e então fica o WAV. */
    boolean paraMp3(Path wav, Path destino);

    /** A duração do áudio ou do vídeo, se o ffmpeg souber dizer. */
    Optional<Long> duracaoMs(Path midia);
}
