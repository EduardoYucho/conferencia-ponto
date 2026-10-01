package br.com.conferenciaponto.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface LancamentoBancoJpaRepository extends JpaRepository<LancamentoBancoEntity, UUID> {

    List<LancamentoBancoEntity> findByDataBetweenOrderByDataAscCriadoEmAsc(LocalDate inicio, LocalDate fim);
}
