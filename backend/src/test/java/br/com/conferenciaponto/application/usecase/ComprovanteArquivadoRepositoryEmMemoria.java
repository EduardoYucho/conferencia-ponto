package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.port.ComprovanteArquivadoRepository;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

class ComprovanteArquivadoRepositoryEmMemoria implements ComprovanteArquivadoRepository {

    final Map<UUID, ComprovanteArquivado> salvos = new LinkedHashMap<>();

    @Override
    public void salvar(ComprovanteArquivado comprovante) {
        salvos.put(comprovante.id(), comprovante);
    }

    @Override
    public Optional<ComprovanteArquivado> buscarPorId(UUID id) {
        return Optional.ofNullable(salvos.get(id));
    }

    @Override
    public List<ComprovanteArquivado> listarPorRegistro(UUID registroJornadaId) {
        return listarPorRegistros(List.of(registroJornadaId));
    }

    @Override
    public List<ComprovanteArquivado> listarPorRegistros(Collection<UUID> ids) {
        return salvos.values().stream()
                .filter(c -> ids.contains(c.registroJornadaId()))
                .sorted(Comparator.comparing(ComprovanteArquivado::dataHoraBatida))
                .toList();
    }

    @Override
    public boolean existeHash(String hashSha256) {
        return salvos.values().stream().anyMatch(c -> c.hashSha256().equals(hashSha256));
    }

    @Override
    public long contarPorRegistro(UUID registroJornadaId) {
        return listarPorRegistro(registroJornadaId).size();
    }
}
