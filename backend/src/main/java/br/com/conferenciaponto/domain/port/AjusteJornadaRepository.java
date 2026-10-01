package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.AjusteJornada;

import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

/** Histórico dos ajustes manuais de batidas. */
public interface AjusteJornadaRepository {

    void salvar(AjusteJornada ajuste);

    /** Ajustes de uma data, do mais recente para o mais antigo. */
    List<AjusteJornada> listarPorData(UUID usuarioId, LocalDate data);

    /** Ajustes de um período (inclusive), do mais recente para o mais antigo. */
    List<AjusteJornada> listarPorPeriodo(UUID usuarioId, LocalDate inicio, LocalDate fim);
}
