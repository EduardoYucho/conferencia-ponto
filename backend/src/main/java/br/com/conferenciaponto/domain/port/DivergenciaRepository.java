package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.StatusDivergencia;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DivergenciaRepository {

    void salvar(Divergencia divergencia);

    Optional<Divergencia> buscarPorId(UUID id);

    /** Por data; {@code status}, {@code inicio} ou {@code fim} nulos = sem filtro. */
    List<Divergencia> listar(StatusDivergencia status, LocalDate inicio, LocalDate fim);

    void excluir(UUID id);
}
