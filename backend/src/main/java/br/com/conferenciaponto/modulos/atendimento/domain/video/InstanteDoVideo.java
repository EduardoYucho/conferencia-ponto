package br.com.conferenciaponto.modulos.atendimento.domain.video;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Os instantes do vídeo como o Gemini escreve (MM:SS, ou H:MM:SS em vídeo longo) e como o sistema mostra: sempre
 * MM:SS, com os minutos passando de 59 quando o vídeo tem mais de uma hora (75:10). É também o que vai no
 * identificador do quadro (V1@02:15).
 */
public final class InstanteDoVideo {

    private static final Pattern INSTANTE = Pattern.compile("^\\s*(?:(\\d{1,2}):)?(\\d{1,3}):(\\d{2})(?:[.,](\\d{1,3}))?\\s*$");

    private InstanteDoVideo() {
    }

    /** "02:15" → 135000; "1:02:15" → 3735000; o que não for um instante fica vazio. */
    public static Optional<Long> lerMs(String texto) {
        if (texto == null) {
            return Optional.empty();
        }
        Matcher m = INSTANTE.matcher(texto);
        if (!m.matches()) {
            return Optional.empty();
        }
        long horas = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        long minutos = Long.parseLong(m.group(2));
        long segundos = Long.parseLong(m.group(3));
        if (segundos > 59 || (m.group(1) != null && minutos > 59)) {
            return Optional.empty();
        }
        long fracao = m.group(4) == null ? 0 : Long.parseLong((m.group(4) + "00").substring(0, 3));
        return Optional.of(((horas * 60 + minutos) * 60 + segundos) * 1000 + fracao);
    }

    /** 135000 → "02:15" (os milissegundos não aparecem). */
    public static String texto(long instanteMs) {
        long segundos = Math.max(0, instanteMs) / 1000;
        return "%02d:%02d".formatted(segundos / 60, segundos % 60);
    }
}
