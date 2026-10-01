package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.Usuario;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UsuarioRepository {

    Optional<Usuario> buscarPorLogin(String login);

    Optional<Usuario> buscarPorId(UUID id);

    /** Todos, por nome. */
    /** Todos, em ordem de cadastro. */
    List<Usuario> listar();

    void salvar(Usuario usuario);
}
