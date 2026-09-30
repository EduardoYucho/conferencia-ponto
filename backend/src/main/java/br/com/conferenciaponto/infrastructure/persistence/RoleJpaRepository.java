package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.Perfil;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Set;

interface RoleJpaRepository extends JpaRepository<RoleEntity, Short> {

    Set<RoleEntity> findByNomeIn(Collection<Perfil> nomes);
}
