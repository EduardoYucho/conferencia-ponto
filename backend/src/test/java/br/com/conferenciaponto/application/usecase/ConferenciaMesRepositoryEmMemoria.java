package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.ConferenciaMes;
import br.com.conferenciaponto.domain.port.ConferenciaMesRepository;

import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class ConferenciaMesRepositoryEmMemoria implements ConferenciaMesRepository {

    final Map<UUID, ConferenciaMes> salvas = new LinkedHashMap<>();

    @Override
    public void salvar(ConferenciaMes conferencia) {
        salvas.put(conferencia.id(), conferencia);
    }

    @Override
    public Optional<ConferenciaMes> vigente(UUID usuarioId, YearMonth mes) {
        return salvas.values().stream().filter(c -> c.usuarioId().equals(usuarioId) && c.mes().equals(mes) && c.vale())
                .findFirst();
    }

    @Override
    public List<ConferenciaMes> vigentesDesde(UUID usuarioId, YearMonth desde) {
        return salvas.values().stream().filter(c -> c.usuarioId().equals(usuarioId) && !c.mes().isBefore(desde) && c.vale())
                .sorted(Comparator.comparing(ConferenciaMes::mes)).toList();
    }

    @Override
    public List<ConferenciaMes> historico(UUID usuarioId, YearMonth mes) {
        return salvas.values().stream().filter(c -> c.usuarioId().equals(usuarioId) && c.mes().equals(mes))
                .sorted(Comparator.comparing(ConferenciaMes::conferidoEm).reversed()).toList();
    }
}
