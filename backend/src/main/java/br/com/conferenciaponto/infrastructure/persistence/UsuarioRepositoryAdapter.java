package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.acesso.AjusteDeAcesso;
import br.com.conferenciaponto.domain.acesso.CatalogoDePermissoes;
import br.com.conferenciaponto.domain.acesso.PerfilDeAcesso;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.AcessosRepository;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * O usuário vem com o acesso que vale agora: os perfis dele e os ajustes, somados pelo catálogo de permissões.
 * Gravar o usuário não mexe no acesso (que é gravado à parte, pelo caso de uso dos acessos).
 */
@Repository
class UsuarioRepositoryAdapter implements UsuarioRepository {

    private final UsuarioJpaRepository usuarios;
    private final AcessosRepository acessos;
    private final CatalogoDePermissoes catalogo;

    UsuarioRepositoryAdapter(UsuarioJpaRepository usuarios, AcessosRepository acessos, CatalogoDePermissoes catalogo) {
        this.usuarios = usuarios;
        this.acessos = acessos;
        this.catalogo = catalogo;
    }

    @Override
    public Optional<Usuario> buscarPorLogin(String login) {
        return usuarios.findByLogin(login).map(e -> e.paraDominio(acessoDe(e.getId())));
    }

    @Override
    public Optional<Usuario> buscarPorId(UUID id) {
        return usuarios.findById(id).map(e -> e.paraDominio(acessoDe(e.getId())));
    }

    @Override
    public List<Usuario> listar() {
        Map<UUID, PerfilDeAcesso> perfis = perfisPorId();
        Map<UUID, Set<UUID>> perfisDeCada = acessos.perfisPorUsuario();
        Map<UUID, List<AjusteDeAcesso>> ajustesDeCada = acessos.ajustesPorUsuario();
        return usuarios.findAllByOrderByCriadoEmAsc().stream()
                .map(e -> e.paraDominio(acesso(perfis, perfisDeCada.getOrDefault(e.getId(), Set.of()),
                        ajustesDeCada.getOrDefault(e.getId(), List.of()))))
                .toList();
    }

    @Override
    public void salvar(Usuario usuario) {
        UsuarioEntity entity = usuarios.findById(usuario.id())
                .orElseGet(() -> new UsuarioEntity(usuario.id(), usuario.login()));
        entity.copiar(usuario);
        // flush imediato: horário, ciclo e perfis do usuário novo são gravados por JDBC logo em seguida (chave estrangeira)
        usuarios.saveAndFlush(entity);
    }

    private Acesso acessoDe(UUID usuarioId) {
        return catalogo.acesso(acessos.perfisCompletosDo(usuarioId), acessos.ajustesDo(usuarioId));
    }

    private Acesso acesso(Map<UUID, PerfilDeAcesso> perfis, Collection<UUID> ids, List<AjusteDeAcesso> ajustes) {
        List<PerfilDeAcesso> dele = ids.stream().map(perfis::get).filter(Objects::nonNull).toList();
        return catalogo.acesso(dele, ajustes);
    }

    private Map<UUID, PerfilDeAcesso> perfisPorId() {
        return acessos.perfis().stream().collect(Collectors.toMap(PerfilDeAcesso::id, Function.identity()));
    }
}
