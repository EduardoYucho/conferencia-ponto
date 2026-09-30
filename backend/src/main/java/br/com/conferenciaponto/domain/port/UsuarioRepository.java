package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.Usuario;

import java.util.Optional;

public interface UsuarioRepository {

    Optional<Usuario> buscarPorLogin(String login);

    void salvar(Usuario usuario);
}
