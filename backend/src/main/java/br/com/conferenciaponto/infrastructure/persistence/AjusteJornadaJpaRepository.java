package br.com.conferenciaponto.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface AjusteJornadaJpaRepository extends JpaRepository<AjusteJornadaEntity, UUID> {

    List<AjusteJornadaEntity> findByUsuarioIdAndDataReferenciaOrderByAjustadoEmDesc(UUID usuarioId,
                                                                                   LocalDate dataReferencia);

    List<AjusteJornadaEntity> findByUsuarioIdAndDataReferenciaBetweenOrderByAjustadoEmDesc(UUID usuarioId,
                                                                                          LocalDate inicio,
                                                                                          LocalDate fim);
}
