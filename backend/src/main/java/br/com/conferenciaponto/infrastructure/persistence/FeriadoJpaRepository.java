package br.com.conferenciaponto.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

interface FeriadoJpaRepository extends JpaRepository<FeriadoEntity, LocalDate> {

    List<FeriadoEntity> findByDataBetweenOrderByData(LocalDate inicio, LocalDate fim);
}
