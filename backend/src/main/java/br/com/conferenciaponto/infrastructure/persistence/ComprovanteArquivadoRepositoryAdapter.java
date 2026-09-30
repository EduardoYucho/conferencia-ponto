package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.port.ComprovanteArquivadoRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class ComprovanteArquivadoRepositoryAdapter implements ComprovanteArquivadoRepository {

    private final ComprovanteJpaRepository jpa;

    ComprovanteArquivadoRepositoryAdapter(ComprovanteJpaRepository jpa) {
        this.jpa = jpa;
    }

    /** Inclusão ou atualização do tipo de batida (demais campos são imutáveis). */
    @Override
    public void salvar(ComprovanteArquivado comprovante) {
        jpa.findById(comprovante.id()).ifPresentOrElse(
                existente -> existente.setTipoBatida(comprovante.tipoBatida()),
                () -> jpa.save(ComprovanteEntity.de(comprovante)));
    }

    @Override
    public Optional<ComprovanteArquivado> buscarPorId(UUID id) {
        return jpa.findById(id).map(ComprovanteEntity::paraDominio);
    }

    @Override
    public List<ComprovanteArquivado> listarPorRegistro(UUID registroJornadaId) {
        return jpa.findByRegistroJornadaIdOrderByDataHoraBatidaAsc(registroJornadaId).stream()
                .map(ComprovanteEntity::paraDominio)
                .toList();
    }

    @Override
    public List<ComprovanteArquivado> listarPorRegistros(Collection<UUID> registroJornadaIds) {
        if (registroJornadaIds.isEmpty()) {
            return List.of();
        }
        return jpa.findByRegistroJornadaIdInOrderByDataHoraBatidaAsc(registroJornadaIds).stream()
                .map(ComprovanteEntity::paraDominio)
                .toList();
    }

    @Override
    public boolean existeHash(String hashSha256) {
        return jpa.existsByHashSha256(hashSha256);
    }

    @Override
    public long contarPorRegistro(UUID registroJornadaId) {
        return jpa.countByRegistroJornadaId(registroJornadaId);
    }
}
