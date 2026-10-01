package br.com.conferenciaponto.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface UsuarioJpaRepository extends JpaRepository<UsuarioEntity, UUID> {

    Optional<UsuarioEntity> findByLogin(String login);

    List<UsuarioEntity> findAllByOrderByCriadoEmAsc();
}
