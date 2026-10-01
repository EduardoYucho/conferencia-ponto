package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.port.AusenciaRepository;
import br.com.conferenciaponto.domain.port.CalendarioAusencias;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class AusenciaRepositoryEmMemoria implements AusenciaRepository, CalendarioAusencias {

    private final Map<UUID, Ausencia> ausencias = new LinkedHashMap<>();

    @Override
    public void salvar(Ausencia ausencia) {
        ausencias.put(ausencia.id(), ausencia);
    }

    @Override
    public void excluir(UUID id) {
        ausencias.remove(id);
    }

    @Override
    public Optional<Ausencia> buscarPorId(UUID id) {
        return Optional.ofNullable(ausencias.get(id));
    }

    @Override
    public List<Ausencia> listarNoPeriodo(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        return ausencias.values().stream().filter(a -> a.usuarioId().equals(usuarioId) && a.sobrepoe(inicio, fim))
                .sorted(Comparator.comparing(Ausencia::dataInicio)).toList();
    }

    @Override
    public boolean isAusencia(UUID usuarioId, LocalDate data) {
        return ausencias.values().stream().anyMatch(a -> a.usuarioId().equals(usuarioId) && a.contem(data));
    }

    List<Ausencia> todas() {
        return ausencias.values().stream().sorted(Comparator.comparing(Ausencia::dataInicio)).toList();
    }
}
