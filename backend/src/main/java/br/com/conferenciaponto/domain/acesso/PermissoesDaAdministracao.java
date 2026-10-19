package br.com.conferenciaponto.domain.acesso;

import java.util.List;
import java.util.Set;

/** As permissões da administração do sistema. */
public final class PermissoesDaAdministracao implements FonteDePermissoes {

    /** Cria, renomeia, desativa e dá senha provisória. */
    public static final String USUARIOS = "usuarios.gerenciar";
    /** Perfis e o acesso de cada pessoa. */
    public static final String ACESSOS = "acessos.gerenciar";
    /** A conta de serviço do Google (planilhas). */
    public static final String INTEGRACOES = "integracoes.gerenciar";
    /** Os logs do sistema. */
    public static final String LOGS = "logs.ver";

    public static final Set<String> CODIGOS = Set.of(USUARIOS, ACESSOS, INTEGRACOES, LOGS);

    @Override
    public String grupo() {
        return "Administração";
    }

    @Override
    public int ordem() {
        return 90;
    }

    @Override
    public List<Permissao> permissoes() {
        return List.of(
                Permissao.de(USUARIOS, "Cadastrar usuários",
                        "Cria, renomeia, desativa e dá senha provisória a quem não tem mais acesso do que você."),
                Permissao.de(ACESSOS, "Perfis e acessos",
                        "Cria e edita os perfis e dá perfis e ajustes às pessoas. Com esta, a pessoa consegue dar "
                                + "a si mesma qualquer outra: só para quem administra o sistema."),
                Permissao.de(INTEGRACOES, "Integração com o Google",
                        "A conta de serviço que grava as planilhas do Google."),
                Permissao.de(LOGS, "Logs do sistema", "Acompanha e baixa os logs do sistema."));
    }
}
