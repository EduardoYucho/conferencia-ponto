package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class UsuarioRepositoryEmMemoria implements UsuarioRepository {

    private final Map<UUID, Usuario> usuarios = new LinkedHashMap<>();

    /** Com o titular padrão dos testes ({@link Fixtures#USUARIO}, login "eduardo", ADMIN). */
    static UsuarioRepositoryEmMemoria comTitular() {
        UsuarioRepositoryEmMemoria repo = new UsuarioRepositoryEmMemoria();
        repo.salvar(new Usuario(Fixtures.USUARIO, "eduardo", "Eduardo", "h:senha-eduardo", true,
                Set.of(Perfil.ROLE_ADMIN), null));
        return repo;
    }

    @Override
    public Optional<Usuario> buscarPorLogin(String login) {
        return usuarios.values().stream().filter(u -> u.login().equals(login)).findFirst();
    }

    @Override
    public Optional<Usuario> buscarPorId(UUID id) {
        return Optional.ofNullable(usuarios.get(id));
    }

    @Override
    public List<Usuario> listar() {
        return List.copyOf(usuarios.values());
    }

    @Override
    public void salvar(Usuario usuario) {
        usuarios.put(usuario.id(), usuario);
    }
}
