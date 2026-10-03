package br.com.conferenciaponto.infrastructure.importacao;

import java.io.IOException;

/**
 * O arquivo da pasta não tem como virar um comprovante (grande demais, vazio há muito tempo): não adianta
 * tentar de novo a cada varredura. A mensagem é a que a pessoa lê na lista de comprovantes.
 */
public class ArquivoRecusadoException extends IOException {

    public ArquivoRecusadoException(String mensagem) {
        super(mensagem);
    }
}
