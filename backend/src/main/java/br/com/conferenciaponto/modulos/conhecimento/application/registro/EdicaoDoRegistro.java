package br.com.conferenciaponto.modulos.conhecimento.application.registro;

/** O que a pessoa mudou no registro (todos os campos de uma vez, como estão no formulário). */
public record EdicaoDoRegistro(String titulo, String tipo, String cliente, String contato, String modulo, String telaNome,
                               String telaCodigo, String sintoma, String mensagemErro, String causa, String resolucao) {
}
