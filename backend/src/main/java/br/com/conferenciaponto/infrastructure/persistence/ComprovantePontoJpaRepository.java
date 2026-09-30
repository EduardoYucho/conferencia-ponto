package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.StatusImportacao;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ComprovantePontoJpaRepository extends JpaRepository<ComprovantePontoEntity, UUID> {

    boolean existsByHashSha256(String hashSha256);

    Optional<ComprovantePontoEntity> findByHashSha256(String hashSha256);

    boolean existsByDataHoraBatidaAndStatus(LocalDateTime dataHoraBatida, StatusImportacao status);

    List<ComprovantePontoEntity> findAllByOrderByProcessadoEmDesc(Pageable pageable);
}
