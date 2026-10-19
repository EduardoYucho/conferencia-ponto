package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.acesso.AjusteDeAcesso;
import br.com.conferenciaponto.domain.acesso.MudancaDeAcesso;
import br.com.conferenciaponto.domain.acesso.PerfilDeAcesso;
import br.com.conferenciaponto.domain.model.Perfil;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Os perfis de acesso, os perfis e os ajustes de cada pessoa e o histórico das mudanças. */
public interface AcessosRepository {

    /** Todos os perfis, por nome. */
    List<PerfilDeAcesso> perfis();

    Optional<PerfilDeAcesso> perfil(UUID id);

    /** O perfil que o sistema cria (Administrador, Usuário ou Coordenador). */
    Optional<PerfilDeAcesso> perfilDoSistema(Perfil perfil);

    /** Cria ou altera o perfil (nome, descrição e permissões). */
    void salvarPerfil(PerfilDeAcesso perfil);

    /** Exclui o perfil, que sai de quem o tinha. */
    void excluirPerfil(UUID id);

    /** Os perfis de cada pessoa (só de quem tem algum). */
    Map<UUID, Set<UUID>> perfisPorUsuario();

    Set<UUID> perfisDo(UUID usuarioId);

    /** Os perfis da pessoa, com as permissões de cada um (uma consulta só: vale a cada requisição). */
    List<PerfilDeAcesso> perfisCompletosDo(UUID usuarioId);

    /** Troca os perfis da pessoa por estes. */
    void definirPerfis(UUID usuarioId, Collection<UUID> perfis, String quem, Instant quando);

    /** Os ajustes de cada pessoa (só de quem tem algum). */
    Map<UUID, List<AjusteDeAcesso>> ajustesPorUsuario();

    List<AjusteDeAcesso> ajustesDo(UUID usuarioId);

    /** Troca os ajustes da pessoa por estes. */
    void definirAjustes(UUID usuarioId, Collection<AjusteDeAcesso> ajustes, String quem, Instant quando);

    void registrar(MudancaDeAcesso mudanca);

    /**
     * As últimas mudanças, da mais nova para a mais antiga.
     *
     * @param usuarioId só as desta pessoa (null = todas)
     */
    List<MudancaDeAcesso> historico(UUID usuarioId, int limite);
}
