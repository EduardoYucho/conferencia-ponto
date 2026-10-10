package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;

/**
 * Um arquivo que acabou de chegar à pasta do atendimento (baixado ou enviado).
 *
 * @param caminho relativo à pasta dos atendimentos (ex.: {@code <id>/arquivos/001_foto.jpeg})
 * @param suportado o tipo, conferido pelos primeiros bytes, é um dos que o gerador analisa
 */
public record ConteudoGuardado(long tamanho, String sha256, String caminho, CategoriaDoArquivo categoria,
                               String tipoDeMidia, boolean suportado) {
}
