package br.com.conferenciaponto.modulos.atendimento.domain.conversa;

import java.time.Instant;

/**
 * O cabeçalho do PDF do Digisac. O telefone do contato não é guardado.
 *
 * @param assunto e resumo: o que o atendente preencheu no Digisac (null quando ficou "-")
 */
public record Cabecalho(String chamado, String contato, Instant inicio, Instant fim, String assunto, String resumo) {

    public Cabecalho comTextos(String assunto, String resumo) {
        return new Cabecalho(chamado, contato, inicio, fim, assunto, resumo);
    }

    @Override
    public String toString() {
        return "Cabecalho[chamado=" + chamado + ", inicio=" + inicio + ", fim=" + fim + "]";
    }
}
