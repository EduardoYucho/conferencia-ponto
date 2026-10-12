package br.com.conferenciaponto.modulos.atendimento.domain.video;

/** O quadro não saiu: o ffmpeg não está instalado ({@link #ausente()}) ou não conseguiu tirar a imagem. */
public class FalhaNoQuadro extends RuntimeException {

    public static final String FFMPEG_AUSENTE = "FFMPEG_AUSENTE";
    public static final String FFMPEG_FALHOU = "FFMPEG_FALHOU";

    private final String codigo;

    public FalhaNoQuadro(String codigo, String mensagem) {
        super(mensagem);
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }

    public boolean ausente() {
        return FFMPEG_AUSENTE.equals(codigo);
    }
}
