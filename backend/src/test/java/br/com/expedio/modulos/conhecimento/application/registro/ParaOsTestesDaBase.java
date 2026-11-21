package br.com.expedio.modulos.conhecimento.application.registro;

import br.com.expedio.domain.acesso.Acesso;
import br.com.expedio.domain.model.Perfil;
import br.com.expedio.domain.model.Usuario;
import br.com.expedio.modulos.PostgresDeTeste;
import br.com.expedio.modulos.UsuariosEmMemoria;
import br.com.expedio.modulos.conhecimento.application.acesso.PermissoesDaBase;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/** Pessoas de verdade no banco (tb_usuario) e no repositório em memória, com o acesso à base que o teste pede. */
final class ParaOsTestesDaBase {

    final UsuariosEmMemoria usuarios = new UsuariosEmMemoria();

    ParaOsTestesDaBase(Clock clock) {
        // o relógio fica para os testes que montam os casos de uso com ele
    }

    /** Uma pessoa que pesquisa (e cura, se pedido). */
    Usuario pessoa(String login, boolean pesquisar, boolean curar) {
        Acesso acesso = Acesso.usuario();
        if (pesquisar || curar) {
            acesso = acesso.com(PermissoesDaBase.PESQUISAR);
        }
        if (curar) {
            acesso = acesso.com(PermissoesDaBase.CURAR);
        }
        Usuario usuario = new Usuario(PostgresDeTeste.novoUsuario(true), login, login, "hash", true, acesso,
                null, null, false);
        usuarios.salvar(usuario);
        return usuario;
    }
}
