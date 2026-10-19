package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.ConferenciaMes;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** As conferências do mês de cada pessoa (as que valem e as reabertas, que ficam no histórico). */
public interface ConferenciaMesRepository {

    /** Grava uma conferência nova ou a reabertura dela. */
    void salvar(ConferenciaMes conferencia);

    /** A conferência que vale para o mês (não reaberta). */
    Optional<ConferenciaMes> vigente(UUID usuarioId, YearMonth mes);

    /** Os meses conferidos (que valem) a partir de um mês, em ordem. */
    List<ConferenciaMes> vigentesDesde(UUID usuarioId, YearMonth desde);

    /** Todas as conferências do mês, da mais recente para a mais antiga (inclusive as reabertas). */
    List<ConferenciaMes> historico(UUID usuarioId, YearMonth mes);
}
