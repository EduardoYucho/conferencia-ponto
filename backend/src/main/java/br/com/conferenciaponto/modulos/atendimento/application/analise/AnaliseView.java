package br.com.conferenciaponto.modulos.atendimento.application.analise;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A análise de um arquivo aberta na tela.
 *
 * @param atual           false quando é de outra versão do prompt ou de outro modelo (a atual ainda não foi feita)
 * @param resultado       o JSON da análise (já mascarado), no esquema da versão
 * @param quadros         no vídeo: o quadro de cada passo (na ordem dos passos; vazio nos outros tipos)
 * @param avisoDosQuadros no vídeo: por que há passos sem quadro (o ffmpeg não está instalado...), ou null
 */
public record AnaliseView(UUID arquivoId, String categoria, int versaoPrompt, String modelo, boolean atual,
                          Instant criadaEm, Integer tokensEntrada, Integer tokensSaida, Integer duracaoMs,
                          JsonNode resultado, List<Quadro> quadros, String avisoDosQuadros) {

    public AnaliseView(UUID arquivoId, String categoria, int versaoPrompt, String modelo, boolean atual,
                       Instant criadaEm, Integer tokensEntrada, Integer tokensSaida, Integer duracaoMs, JsonNode resultado) {
        this(arquivoId, categoria, versaoPrompt, modelo, atual, criadaEm, tokensEntrada, tokensSaida, duracaoMs, resultado,
                List.of(), null);
    }

    /**
     * O quadro de um passo do vídeo.
     *
     * @param passo      o número do passo (1, 2...)
     * @param instanteMs o instante do quadro (a imagem: GET .../quadros/{instanteMs})
     * @param instante   o mesmo, em MM:SS
     * @param disponivel o quadro já foi tirado
     */
    public record Quadro(int passo, long instanteMs, String instante, boolean disponivel) {
    }
}
