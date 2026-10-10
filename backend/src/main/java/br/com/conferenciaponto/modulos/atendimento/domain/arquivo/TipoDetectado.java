package br.com.conferenciaponto.modulos.atendimento.domain.arquivo;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;

/**
 * O tipo de um arquivo pelos primeiros bytes (a extensão do nome pode mentir).
 *
 * @param suportado um dos tipos que o gerador aceita e analisa (veja {@link TipoPeloConteudo})
 * @param descricao para a mensagem quando não é suportado (ex.: "documento do Word")
 */
public record TipoDetectado(CategoriaDoArquivo categoria, String tipoDeMidia, boolean suportado, String descricao) {
}
