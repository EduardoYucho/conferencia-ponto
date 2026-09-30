package br.com.conferenciaponto.infrastructure.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

interface FeriadoJpaRepository extends JpaRepository<FeriadoEntity, LocalDate> {

    List<FeriadoEntity> findByDataBetweenOrderByData(LocalDate inicio, LocalDate fim);
}
