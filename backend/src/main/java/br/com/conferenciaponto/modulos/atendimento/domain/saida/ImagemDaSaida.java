package br.com.conferenciaponto.modulos.atendimento.domain.saida;

import java.util.UUID;

/**
 * Uma imagem de uma saída: "Imagem N" no texto e o arquivo {@code Imagem_NN} para anexar ao chamado.
 *
 * @param numero    a ordem no texto (as que saíram do texto ficam no fim, depois das incluídas)
 * @param arquivoId o arquivo do atendimento de onde ela vem (null se o arquivo foi tirado)
 * @param caminho   relativo à pasta dos atendimentos
 * @param incluida  está no texto
 */
public record ImagemDaSaida(UUID id, UUID saidaId, int numero, UUID arquivoId, String caminho, String legenda,
                            boolean incluida) {
}
