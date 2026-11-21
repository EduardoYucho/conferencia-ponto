package br.com.expedio.modulos.backup.domain;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Uma linha de {@code blocos.jsonl}: o pedaço {@code seq} entrou no arquivo, que passou a terminar em
 * {@code offsetFinal}. {@code fim} é onde a leitura no cliente estava ao fechar o bloco (para retomar);
 * {@code arquivos} e {@code naoLidos} são os que terminaram dentro dele (vão para o manifesto).
 */
public record Registro(long seq, long offsetFinal, long bruto, JsonNode fim, JsonNode arquivos, JsonNode naoLidos) {
}
