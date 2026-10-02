package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.VinculoPlanilha;
import br.com.conferenciaponto.domain.port.VinculoPlanilhaRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class VinculoPlanilhaRepositoryEmMemoria implements VinculoPlanilhaRepository {

    private final Map<UUID, VinculoPlanilha> vinculos = new LinkedHashMap<>();

    @Override
    public Optional<VinculoPlanilha> buscar(UUID usuarioId) {
        return Optional.ofNullable(vinculos.get(usuarioId));
    }

    @Override
    public Optional<VinculoPlanilha> buscarPorPlanilha(String planilhaId) {
        return vinculos.values().stream().filter(v -> v.planilhaId().equals(planilhaId)).findFirst();
    }

    @Override
    public List<VinculoPlanilha> listar() {
        return List.copyOf(vinculos.values());
    }

    @Override
    public void salvar(VinculoPlanilha vinculo) {
        vinculos.put(vinculo.usuarioId(), vinculo);
    }

    @Override
    public void excluir(UUID usuarioId) {
        vinculos.remove(usuarioId);
    }
}
