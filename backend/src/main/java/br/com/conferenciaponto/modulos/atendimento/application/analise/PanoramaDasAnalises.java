package br.com.conferenciaponto.modulos.atendimento.application.analise;

import java.util.Map;
import java.util.UUID;

/**
 * As análises de um atendimento de uma vez: a situação de cada arquivo analisável, quantas ainda faltam (para a
 * barra de progresso) e os tokens gastos.
 *
 * @param esperadas      arquivos que terão análise (os prontos e os que ainda vão ser baixados)
 * @param resolvidas     prontas ou que falharam de vez
 * @param comFalha       alguma análise falhou de vez
 * @param semChaveGemini há o que analisar e a pessoa não cadastrou a chave
 */
public record PanoramaDasAnalises(Map<UUID, EstadoDaAnalise> estados, int esperadas, int resolvidas, boolean comFalha,
                                  long tokensEntrada, long tokensSaida, boolean semChaveGemini) {

    public static final PanoramaDasAnalises VAZIO = new PanoramaDasAnalises(Map.of(), 0, 0, false, 0, 0, false);

    public PanoramaDasAnalises {
        estados = Map.copyOf(estados);
    }

    public EstadoDaAnalise de(UUID arquivoId) {
        return estados.get(arquivoId);
    }
}
