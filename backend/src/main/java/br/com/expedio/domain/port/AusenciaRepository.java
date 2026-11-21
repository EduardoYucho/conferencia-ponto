package br.com.expedio.domain.port;

import br.com.expedio.domain.model.Ausencia;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AusenciaRepository {

    void salvar(Ausencia ausencia);

    void excluir(UUID id);

    Optional<Ausencia> buscarPorId(UUID id);

    /** Ausências que tocam o período (inclusive), por data de início. */
    List<Ausencia> listarNoPeriodo(UUID usuarioId, LocalDate inicio, LocalDate fim);
}
