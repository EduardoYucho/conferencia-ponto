package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.util.UUID;

/** Uma imagem citada no texto redigido, já numerada na ordem em que aparece. */
public record NovaImagem(UUID id, int numero, UUID arquivoId, String caminho, String legenda) {
}
