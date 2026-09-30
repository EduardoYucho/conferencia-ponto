package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.ComprovanteArquivado;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de saída: metadados dos PDFs arquivados (tb_comprovante). */
public interface ComprovanteArquivadoRepository {

    void salvar(ComprovanteArquivado comprovante);

    Optional<ComprovanteArquivado> buscarPorId(UUID id);

    List<ComprovanteArquivado> listarPorRegistro(UUID registroJornadaId);

    /** Carga em lote para telas mensais (evita N+1). */
    List<ComprovanteArquivado> listarPorRegistros(Collection<UUID> registroJornadaIds);

    boolean existeHash(String hashSha256);

    long contarPorRegistro(UUID registroJornadaId);
}
