package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.util.UUID;

/**
 * Uma imagem citada no texto redigido, já numerada na ordem em que aparece.
 *
 * @param instanteVideoMs no quadro de um vídeo, o instante (null nas outras imagens)
 */
public record NovaImagem(UUID id, int numero, UUID arquivoId, String caminho, String legenda, Long instanteVideoMs) {

    public NovaImagem(UUID id, int numero, UUID arquivoId, String caminho, String legenda) {
        this(id, numero, arquivoId, caminho, legenda, null);
    }
}
