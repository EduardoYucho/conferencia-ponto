package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.port.HorarioTrabalhoRepository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class HorarioTrabalhoRepositoryEmMemoria implements HorarioTrabalhoRepository {

    private final Map<UUID, HorarioTrabalho> horarios = new LinkedHashMap<>();

    @Override
    public void salvar(HorarioTrabalho horario) {
        boolean mesmaData = horarios.values().stream().anyMatch(h -> !h.id().equals(horario.id())
                && h.usuarioId().equals(horario.usuarioId()) && h.vigenteDesde().equals(horario.vigenteDesde()));
        if (mesmaData) {
            throw new IllegalStateException("uk_horario_trabalho_vigencia");
        }
        horarios.put(horario.id(), horario);
    }

    @Override
    public Optional<HorarioTrabalho> buscarPorId(UUID id) {
        return Optional.ofNullable(horarios.get(id));
    }

    @Override
    public List<HorarioTrabalho> listarPorUsuario(UUID usuarioId) {
        return horarios.values().stream().filter(h -> h.usuarioId().equals(usuarioId))
                .sorted(Comparator.comparing(HorarioTrabalho::vigenteDesde)).toList();
    }

    @Override
    public void excluir(UUID id) {
        horarios.remove(id);
    }
}
