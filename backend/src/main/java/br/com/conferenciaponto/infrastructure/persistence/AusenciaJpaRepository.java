package br.com.conferenciaponto.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface AusenciaJpaRepository extends JpaRepository<AusenciaEntity, UUID> {

    @Query("select a from AusenciaEntity a where a.dataInicio <= :fim and a.dataFim >= :inicio order by a.dataInicio")
    List<AusenciaEntity> noPeriodo(@Param("inicio") LocalDate inicio, @Param("fim") LocalDate fim);

    boolean existsByDataInicioLessThanEqualAndDataFimGreaterThanEqual(LocalDate data1, LocalDate data2);
}
