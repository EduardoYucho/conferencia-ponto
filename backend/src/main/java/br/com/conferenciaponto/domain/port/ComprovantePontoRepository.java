package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.ComprovanteImportado;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Porta de saída: auditoria dos comprovantes (PDF) processados. */
public interface ComprovantePontoRepository {

    boolean existeHash(String hashSha256);

    Optional<ComprovanteImportado> buscarPorHash(String hashSha256);

    /** Já existe comprovante IMPORTADO com esta data/hora? */
    boolean existeImportado(LocalDateTime dataHoraBatida);

    void salvar(ComprovanteImportado comprovante);

    /** Mais recentes primeiro. */
    List<ComprovanteImportado> recentes(int limite);
}
