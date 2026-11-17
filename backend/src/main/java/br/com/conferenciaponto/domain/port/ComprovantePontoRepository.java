package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.ComprovanteImportado;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída: auditoria dos comprovantes (PDF) processados. */
public interface ComprovantePontoRepository {

    boolean existeHash(String hashSha256);

    /** O registro deste PDF no ponto da pessoa (um por pessoa). */
    Optional<ComprovanteImportado> buscarPorHash(UUID usuarioId, String hashSha256);

    /** O PDF está no ponto de outra pessoa (importado, ou arquivado como prova de uma batida dela)? */
    boolean noPontoDeOutraPessoa(String hashSha256, UUID usuarioId);

    /** A importação foi desfeita: o registro fica, como DESFEITO, com o motivo. */
    void marcarDesfeito(UUID id, String mensagem);

    /** Já existe comprovante IMPORTADO com esta data/hora? */
    boolean existeImportado(UUID usuarioId, LocalDateTime dataHoraBatida);

    void salvar(ComprovanteImportado comprovante);

    /** Mais recentes primeiro. */
    List<ComprovanteImportado> recentes(UUID usuarioId, int limite);
}
