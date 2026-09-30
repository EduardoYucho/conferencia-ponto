package br.com.conferenciaponto.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface ComprovanteJpaRepository extends JpaRepository<ComprovanteEntity, UUID> {

    List<ComprovanteEntity> findByRegistroJornadaIdOrderByDataHoraBatidaAsc(UUID registroJornadaId);

    List<ComprovanteEntity> findByRegistroJornadaIdInOrderByDataHoraBatidaAsc(Collection<UUID> registroJornadaIds);

    boolean existsByHashSha256(String hashSha256);

    long countByRegistroJornadaId(UUID registroJornadaId);
}
