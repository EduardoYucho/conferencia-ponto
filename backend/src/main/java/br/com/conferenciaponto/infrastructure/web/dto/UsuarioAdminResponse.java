package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;

import java.time.Instant;
import java.util.UUID;

/**
 * Usuário na tela de administração.
 *
 * @param perfil          ROLE_ADMIN, ROLE_USER ou ROLE_VIEWER
 * @param situacaoMonitor situação do monitor da pasta (ATIVO, INDISPONIVEL, SEM_PASTA...)
 */
public record UsuarioAdminResponse(UUID id, String login, String nome, String perfil, boolean ativo, boolean titular,
                                   String pastaComprovantes, boolean trocarSenha, Instant ultimoLoginEm,
                                   String situacaoMonitor, String mensagemMonitor) {

    public static UsuarioAdminResponse de(Usuario u, MonitoramentoResponse monitor) {
        return new UsuarioAdminResponse(u.id(), u.login(), u.nome(), perfilPrincipal(u).name(), u.ativo(),
                u.isTitular(), u.pastaComprovantes(), u.trocarSenha(), u.ultimoLoginEm(),
                monitor == null ? null : monitor.situacao(), monitor == null ? null : monitor.mensagem());
    }

    static Perfil perfilPrincipal(Usuario u) {
        if (u.perfis().contains(Perfil.ROLE_ADMIN)) {
            return Perfil.ROLE_ADMIN;
        }
        return u.perfis().contains(Perfil.ROLE_USER) ? Perfil.ROLE_USER : Perfil.ROLE_VIEWER;
    }
}
