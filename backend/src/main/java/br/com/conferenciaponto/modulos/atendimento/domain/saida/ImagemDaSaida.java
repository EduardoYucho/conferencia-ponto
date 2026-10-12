package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.util.UUID;

/**
 * Uma imagem de uma saída: "Imagem N" no texto e o arquivo {@code Imagem_NN} para anexar ao chamado.
 *
 * @param numero    a ordem no texto (as que saíram do texto ficam no fim, depois das incluídas)
 * @param arquivoId o arquivo do atendimento de onde ela vem (null se o arquivo foi tirado)
 * @param caminho   relativo à pasta dos atendimentos
 * @param incluida  está no texto
 * @param instanteVideoMs o instante do quadro, quando a imagem vem de um vídeo (null nas outras)
 * @param marcacoes as áreas marcadas (JSON), guardadas quando a marcação foi ligada pela primeira vez
 * @param comMarcacao a imagem que vale é a cópia com as marcações em vermelho
 */
public record ImagemDaSaida(UUID id, UUID saidaId, int numero, UUID arquivoId, String caminho, String legenda,
                            boolean incluida, Long instanteVideoMs, String marcacoes, boolean comMarcacao) {

    public ImagemDaSaida(UUID id, UUID saidaId, int numero, UUID arquivoId, String caminho, String legenda,
                         boolean incluida) {
        this(id, saidaId, numero, arquivoId, caminho, legenda, incluida, null, null, false);
    }

    /** Um quadro de vídeo (não tem as áreas da análise de imagem). */
    public boolean doVideo() {
        return instanteVideoMs != null;
    }
}
