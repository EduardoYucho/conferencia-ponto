package br.com.conferenciaponto.modulos;

import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Usuários do ponto em memória, para os testes dos módulos. */
public class UsuariosEmMemoria implements UsuarioRepository {

    private final Map<UUID, Usuario> porId = new LinkedHashMap<>();

    public Usuario novo(String login, String nome, Perfil perfil) {
        Usuario usuario = new Usuario(UUID.randomUUID(), login, nome, "hash", true, Set.of(perfil), null);
        salvar(usuario);
        return usuario;
    }

    public Usuario desativado(String login, String nome) {
        Usuario usuario = new Usuario(UUID.randomUUID(), login, nome, "hash", false, Set.of(Perfil.ROLE_USER), null);
        salvar(usuario);
        return usuario;
    }

    @Override
    public Optional<Usuario> buscarPorLogin(String login) {
        return porId.values().stream().filter(u -> u.login().equals(login)).findFirst();
    }

    @Override
    public Optional<Usuario> buscarPorId(UUID id) {
        return Optional.ofNullable(porId.get(id));
    }

    @Override
    public List<Usuario> listar() {
        return new ArrayList<>(porId.values());
    }

    @Override
    public void salvar(Usuario usuario) {
        porId.put(usuario.id(), usuario);
    }
}
