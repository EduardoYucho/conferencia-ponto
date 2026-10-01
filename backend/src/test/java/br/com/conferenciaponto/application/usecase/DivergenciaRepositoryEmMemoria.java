package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.port.DivergenciaRepository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class DivergenciaRepositoryEmMemoria implements DivergenciaRepository {

    private final Map<UUID, Divergencia> divergencias = new LinkedHashMap<>();

    @Override
    public void salvar(Divergencia d) {
        boolean outraNaData = divergencias.values().stream().anyMatch(x -> x.usuarioId().equals(d.usuarioId())
                && x.data().equals(d.data()) && !x.id().equals(d.id()));
        if (outraNaData) {
            throw new IllegalStateException("uk_divergencia_data");
        }
        divergencias.put(d.id(), d);
    }

    @Override
    public Optional<Divergencia> buscarPorId(UUID id) {
        return Optional.ofNullable(divergencias.get(id));
    }

    @Override
    public List<Divergencia> listar(UUID usuarioId, StatusDivergencia status, LocalDate inicio, LocalDate fim) {
        return divergencias.values().stream()
                .filter(d -> d.usuarioId().equals(usuarioId))
                .filter(d -> status == null || d.status() == status)
                .filter(d -> inicio == null || !d.data().isBefore(inicio))
                .filter(d -> fim == null || !d.data().isAfter(fim))
                .sorted(Comparator.comparing(Divergencia::data)).toList();
    }

    @Override
    public void excluir(UUID id) {
        divergencias.remove(id);
    }

    Optional<Divergencia> naData(LocalDate data) {
        return divergencias.values().stream().filter(d -> d.data().equals(data)).findFirst();
    }
}
