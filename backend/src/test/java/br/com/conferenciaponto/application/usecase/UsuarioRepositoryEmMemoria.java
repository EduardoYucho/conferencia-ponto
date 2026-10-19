package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.acesso.CatalogoDePermissoes;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class UsuarioRepositoryEmMemoria implements UsuarioRepository {

    private final Map<UUID, Usuario> usuarios = new LinkedHashMap<>();
    /** Ligado aos acessos em memória, o acesso vem deles (como no banco); sem eles, vale o que foi gravado. */
    private AcessosEmMemoria acessos;
    private CatalogoDePermissoes catalogo;

    /** O acesso de cada usuário passa a vir dos perfis e ajustes gravados em {@code acessos}. */
    UsuarioRepositoryEmMemoria ligadoA(AcessosEmMemoria acessos, CatalogoDePermissoes catalogo) {
        this.acessos = acessos;
        this.catalogo = catalogo;
        return this;
    }

    private Usuario comAcesso(Usuario u) {
        return acessos == null ? u : u.comAcesso(catalogo.acesso(acessos.perfisCompletosDo(u.id()), acessos.ajustesDo(u.id())));
    }

    /** Com o titular padrão dos testes ({@link Fixtures#USUARIO}, login "eduardo", ADMIN). */
    static UsuarioRepositoryEmMemoria comTitular() {
        UsuarioRepositoryEmMemoria repo = new UsuarioRepositoryEmMemoria();
        repo.salvar(new Usuario(Fixtures.USUARIO, "eduardo", "Eduardo", "h:senha-eduardo", true,
                Acesso.administrador(), null));
        return repo;
    }

    @Override
    public Optional<Usuario> buscarPorLogin(String login) {
        return usuarios.values().stream().filter(u -> u.login().equals(login)).findFirst().map(this::comAcesso);
    }

    @Override
    public Optional<Usuario> buscarPorId(UUID id) {
        return Optional.ofNullable(usuarios.get(id)).map(this::comAcesso);
    }

    @Override
    public List<Usuario> listar() {
        return usuarios.values().stream().map(this::comAcesso).toList();
    }

    @Override
    public void salvar(Usuario usuario) {
        usuarios.put(usuario.id(), usuario);
    }
}
