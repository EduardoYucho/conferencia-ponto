package br.com.conferenciaponto.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

interface NotificacaoJpaRepository extends JpaRepository<NotificacaoEntity, UUID> {

    boolean existsByChave(String chave);

    List<NotificacaoEntity> findAllByOrderByCriadaEmDesc(Pageable pagina);

    long countByLidaEmIsNull();

    @Modifying
    @Query("UPDATE NotificacaoEntity n SET n.lidaEm = :quando WHERE n.id = :id AND n.lidaEm IS NULL")
    int marcarLida(@Param("id") UUID id, @Param("quando") OffsetDateTime quando);

    @Modifying
    @Query("UPDATE NotificacaoEntity n SET n.lidaEm = :quando WHERE n.lidaEm IS NULL")
    int marcarTodasLidas(@Param("quando") OffsetDateTime quando);

    @Modifying
    @Query("""
            UPDATE NotificacaoEntity n SET n.lidaEm = :quando
             WHERE n.lidaEm IS NULL AND n.tipo IN ('CICLO_30_DIAS', 'CICLO_15_DIAS', 'CICLO_VENCIDO') AND n.chave NOT LIKE :padrao
            """)
    int marcarLidosAvisosDeCicloExceto(@Param("padrao") String padrao, @Param("quando") OffsetDateTime quando);
}
