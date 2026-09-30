package br.com.conferenciaponto.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CicloBancoJpaRepository extends JpaRepository<CicloBancoEntity, UUID> {

    Optional<CicloBancoEntity> findFirstByStatus(String status);

    List<CicloBancoEntity> findAllByOrderByDataInicioDesc();
}
