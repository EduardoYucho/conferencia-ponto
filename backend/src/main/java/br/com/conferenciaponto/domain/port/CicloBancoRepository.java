package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.CicloBanco;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CicloBancoRepository {

    Optional<CicloBanco> buscarAberto();

    /** Todos os ciclos, do mais recente para o mais antigo. */
    List<CicloBanco> listar();

    void salvar(CicloBanco ciclo);

    void excluir(UUID id);
}
