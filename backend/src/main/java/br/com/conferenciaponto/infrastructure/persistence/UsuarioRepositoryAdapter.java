package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.Set;

@Repository
class UsuarioRepositoryAdapter implements UsuarioRepository {

    private final UsuarioJpaRepository usuarios;
    private final RoleJpaRepository roles;

    UsuarioRepositoryAdapter(UsuarioJpaRepository usuarios, RoleJpaRepository roles) {
        this.usuarios = usuarios;
        this.roles = roles;
    }

    @Override
    public Optional<Usuario> buscarPorLogin(String login) {
        return usuarios.findByLogin(login).map(UsuarioEntity::paraDominio);
    }

    @Override
    public void salvar(Usuario usuario) {
        UsuarioEntity entity = usuarios.findById(usuario.id())
                .orElseGet(() -> new UsuarioEntity(usuario.id(), usuario.login()));
        Set<RoleEntity> perfis = roles.findByNomeIn(usuario.perfis());
        if (perfis.size() != usuario.perfis().size()) {
            throw new IllegalStateException("Perfis inexistentes em tb_role: " + usuario.perfis());
        }
        entity.copiar(usuario, perfis);
        usuarios.save(entity);
    }
}
