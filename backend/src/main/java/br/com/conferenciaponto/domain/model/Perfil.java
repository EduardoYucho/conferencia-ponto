package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.acesso.PermissoesDaAdministracao;
import br.com.conferenciaponto.domain.acesso.PermissoesDoPonto;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Os perfis que o sistema cria (migração V16) e que a configuração dos usuários iniciais usa
 * ({@code ponto.seguranca.usuarios-iniciais[].perfis}). Os nomes vêm dos perfis antigos (ROLE_*), para a
 * configuração continuar valendo. Os perfis de verdade são {@link br.com.conferenciaponto.domain.acesso.PerfilDeAcesso}:
 * Usuário e Coordenador podem ser editados na tela, e perfis novos podem ser criados.
 */
public enum Perfil {
    /** Administrador: tudo liberado, inclusive o que vier nas próximas versões. */
    ROLE_ADMIN("Administrador", true, Set.of()),
    /** Usuário: registra e confere o próprio ponto. */
    ROLE_USER("Usuário", false, Set.of(PermissoesDoPonto.PROPRIO, PermissoesDoPonto.LANCAR)),
    /** Coordenador: consulta o ponto de todos, sem alterar. */
    ROLE_VIEWER("Coordenador", false, Set.of(PermissoesDoPonto.EQUIPE));

    private final String nomeInicial;
    private final boolean todas;
    private final Set<String> permissoes;

    Perfil(String nomeInicial, boolean todas, Set<String> permissoes) {
        this.nomeInicial = nomeInicial;
        this.todas = todas;
        this.permissoes = permissoes;
    }

    /** O nome com que o perfil é criado (pode ser trocado na tela, menos o do Administrador). */
    public String nomeInicial() {
        return nomeInicial;
    }

    /** Tem todas as permissões (só o Administrador). */
    public boolean todas() {
        return todas;
    }

    /** As permissões com que o perfil é criado (no Administrador: todas as do ponto e da administração). */
    public Set<String> permissoesIniciais() {
        if (!todas) {
            return permissoes;
        }
        Set<String> todasDoPonto = new LinkedHashSet<>(PermissoesDoPonto.CODIGOS);
        todasDoPonto.addAll(PermissoesDaAdministracao.CODIGOS);
        return Set.copyOf(todasDoPonto);
    }
}
