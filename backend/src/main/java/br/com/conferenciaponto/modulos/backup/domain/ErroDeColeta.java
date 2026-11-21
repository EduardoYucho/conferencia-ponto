package br.com.conferenciaponto.modulos.backup.domain;

/** Falha que vira resposta HTTP com uma frase para a pessoa ({@code mensagem}) e um código para a página. */
public class ErroDeColeta extends RuntimeException {

    private final int status;
    private final String codigo;

    public ErroDeColeta(int status, String codigo, String mensagem) {
        super(mensagem);
        this.status = status;
        this.codigo = codigo;
    }

    public int status() {
        return status;
    }

    public String codigo() {
        return codigo;
    }
}
