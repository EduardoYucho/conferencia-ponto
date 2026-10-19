package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.Usuario;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Usuário na tela de administração.
 *
 * @param perfis          o nome dos perfis da pessoa
 * @param permissoes      o que vale para ela agora (para a tela saber se dá para desativar ou trocar a senha)
 * @param situacaoMonitor situação do monitor da pasta (ATIVO, INDISPONIVEL, SEM_PASTA...)
 */
public record UsuarioAdminResponse(UUID id, String login, String nome, List<String> perfis, List<String> permissoes,
                                   boolean ativo, boolean titular, String pastaComprovantes, boolean trocarSenha,
                                   Instant ultimoLoginEm, String situacaoMonitor, String mensagemMonitor) {

    public static UsuarioAdminResponse de(Usuario u, MonitoramentoResponse monitor) {
        return new UsuarioAdminResponse(u.id(), u.login(), u.nome(), u.acesso().perfis(), u.acesso().ordenadas(),
                u.ativo(), u.isTitular(), u.pastaComprovantes(), u.trocarSenha(), u.ultimoLoginEm(),
                monitor == null ? null : monitor.situacao(), monitor == null ? null : monitor.mensagem());
    }
}
