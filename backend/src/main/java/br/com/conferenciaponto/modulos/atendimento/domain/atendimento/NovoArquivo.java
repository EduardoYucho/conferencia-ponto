package br.com.conferenciaponto.modulos.atendimento.domain.atendimento;

import java.time.Instant;
import java.util.UUID;

/**
 * Um arquivo enviado pela pessoa (ligação, vídeo ou print), já gravado na pasta.
 *
 * @param momento a data de modificação do arquivo no computador da pessoa (null se o navegador não informou)
 */
public record NovoArquivo(UUID id, UUID atendimentoId, OrigemDoArquivo origem, int ordem, String nomeOriginal,
                          ConteudoGuardado conteudo, Instant momento, Instant criadoEm) {
}
