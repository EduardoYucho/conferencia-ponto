package br.com.conferenciaponto.modulos.atendimento.domain.arquivo;

/**
 * O download não terminou. A mensagem é para a pessoa ler; nunca leva a URL (que dá acesso ao arquivo).
 *
 * @see #temporaria() rede, servidor ocupado: vale tentar de novo (e continuar de onde parou)
 */
public class FalhaNoDownload extends RuntimeException {

    private final String codigo;
    private final boolean temporaria;
    private final int status;

    public FalhaNoDownload(String codigo, String mensagem, boolean temporaria, int status) {
        super(mensagem);
        this.codigo = codigo;
        this.temporaria = temporaria;
        this.status = status;
    }

    public static FalhaNoDownload temporaria(String codigo, String mensagem) {
        return new FalhaNoDownload(codigo, mensagem, true, 0);
    }

    public static FalhaNoDownload definitiva(String codigo, String mensagem) {
        return new FalhaNoDownload(codigo, mensagem, false, 0);
    }

    public String codigo() {
        return codigo;
    }

    public boolean temporaria() {
        return temporaria;
    }

    /** O status HTTP da recusa (0 quando não houve resposta). */
    public int status() {
        return status;
    }
}
