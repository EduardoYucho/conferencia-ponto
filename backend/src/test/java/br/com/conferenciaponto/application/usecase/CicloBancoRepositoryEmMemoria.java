package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.port.CicloBancoRepository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class CicloBancoRepositoryEmMemoria implements CicloBancoRepository {

    private final Map<UUID, CicloBanco> ciclos = new LinkedHashMap<>();

    @Override
    public Optional<CicloBanco> buscarAberto() {
        return ciclos.values().stream().filter(CicloBanco::isAberto).findFirst();
    }

    @Override
    public List<CicloBanco> listar() {
        return ciclos.values().stream().sorted(Comparator.comparing(CicloBanco::dataInicio).reversed()).toList();
    }

    @Override
    public void salvar(CicloBanco ciclo) {
        if (ciclo.isAberto() && buscarAberto().filter(c -> !c.id().equals(ciclo.id())).isPresent()) {
            throw new IllegalStateException("já existe um ciclo aberto (índice único)");
        }
        ciclos.put(ciclo.id(), ciclo);
    }

    @Override
    public void excluir(UUID id) {
        ciclos.remove(id);
    }
}
