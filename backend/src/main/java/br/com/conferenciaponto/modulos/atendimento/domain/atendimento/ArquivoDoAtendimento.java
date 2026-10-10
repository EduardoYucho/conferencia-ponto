package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;

import java.time.Instant;
import java.util.UUID;

/**
 * Um arquivo do atendimento como está no banco (sem a URL de download, que só o download usa).
 *
 * @param origem anexo_conversa, ligacao, video ou print_extra
 */
public record ArquivoDoAtendimento(UUID id, String origem, int ordem, String nome, CategoriaDoArquivo categoria,
                                   String situacao, Instant urlValidaAte, Integer mensagemOrdem, Instant momento) {
}
