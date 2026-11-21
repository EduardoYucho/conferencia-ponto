package br.com.expedio.domain.port;

import br.com.expedio.domain.model.CicloBanco;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CicloBancoRepository {

    Optional<CicloBanco> buscarAberto(UUID usuarioId);

    /** Todos os ciclos, do mais recente para o mais antigo. */
    List<CicloBanco> listar(UUID usuarioId);

    void salvar(CicloBanco ciclo);

    void excluir(UUID id);
}
