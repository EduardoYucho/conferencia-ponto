package br.com.conferenciaponto.modulos.conhecimento.application.registro;

import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.UsuariosEmMemoria;
import br.com.conferenciaponto.modulos.conhecimento.application.acesso.GerenciarAcessosABase;
import br.com.conferenciaponto.modulos.conhecimento.domain.acesso.AcessoABase;
import br.com.conferenciaponto.modulos.conhecimento.domain.acesso.AcessosABase;
import br.com.conferenciaponto.modulos.conhecimento.infrastructure.persistence.RepositoriosDaBase;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/** Pessoas de verdade no banco (tb_usuario) e no repositório em memória, com o acesso à base que o teste pede. */
final class ParaOsTestesDaBase {

    final UsuariosEmMemoria usuarios = new UsuariosEmMemoria();
    final AcessosABase acessos = RepositoriosDaBase.acessos();
    final GerenciarAcessosABase gerenciarAcessos;

    ParaOsTestesDaBase(Clock clock) {
        gerenciarAcessos = new GerenciarAcessosABase(acessos, usuarios, clock);
    }

    /** Uma pessoa que pesquisa (e cura, se pedido). */
    Usuario pessoa(String login, boolean pesquisar, boolean curar) {
        Usuario usuario = new Usuario(PostgresDeTeste.novoUsuario(true), login, login, "hash", true, Set.of(Perfil.ROLE_USER),
                null, null, false);
        usuarios.salvar(usuario);
        if (pesquisar || curar) {
            acessos.salvar(new AcessoABase(usuario.id(), pesquisar, curar, "admin", Instant.now()));
        }
        return usuario;
    }
}
