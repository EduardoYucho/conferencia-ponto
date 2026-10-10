package br.com.conferenciaponto.modulos.atendimento.domain.analise;

import br.com.conferenciaponto.modulos.atendimento.domain.conversa.CategoriaDoArquivo;

import java.time.Instant;
import java.util.UUID;

/**
 * Um arquivo do atendimento com o que a análise precisa: o conteúdo em disco, o tipo e, se já foi enviado ao
 * Gemini, o arquivo remoto (para não enviar de novo depois de uma queda).
 *
 * @param caminho        relativo à pasta dos atendimentos (null enquanto não há arquivo)
 * @param arquivoRemoto  o nome no Gemini (files/...), ou null
 */
public record ArquivoParaAnalisar(UUID id, UUID atendimentoId, UUID usuarioId, String origem, int ordem, String nome,
                                  CategoriaDoArquivo categoria, String tipoDeMidia, Long tamanho, String sha256,
                                  String caminho, String situacao, String arquivoRemoto, Instant arquivoRemotoExpiraEm) {

    /** Baixado ou enviado, com o conteúdo em disco. */
    public boolean pronto() {
        return "pronto".equals(situacao) && caminho != null;
    }
}
