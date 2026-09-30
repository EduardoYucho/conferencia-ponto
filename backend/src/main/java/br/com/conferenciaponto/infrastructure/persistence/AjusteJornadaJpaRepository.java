package br.com.conferenciaponto.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface AjusteJornadaJpaRepository extends JpaRepository<AjusteJornadaEntity, UUID> {

    List<AjusteJornadaEntity> findByDataReferenciaOrderByAjustadoEmDesc(LocalDate dataReferencia);

    List<AjusteJornadaEntity> findByDataReferenciaBetweenOrderByAjustadoEmDesc(LocalDate inicio, LocalDate fim);
}
