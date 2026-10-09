package br.com.conferenciaponto.modulos.atendimento.domain.chave;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** As chaves do Gemini guardadas (tabela atendimento.chave_gemini): no máximo uma por usuário. */
public interface ChavesGemini {

    Optional<ChaveGemini> buscar(UUID usuarioId);

    /** Cria ou substitui a chave do usuário. */
    void salvar(ChaveGemini chave);

    void atualizarSituacao(UUID usuarioId, SituacaoDaChave situacao, Instant testadaEm);

    void apagar(UUID usuarioId);
}
