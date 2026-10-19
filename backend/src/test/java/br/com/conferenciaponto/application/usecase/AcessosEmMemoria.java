package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.acesso.AjusteDeAcesso;
import br.com.conferenciaponto.domain.acesso.CatalogoDePermissoes;
import br.com.conferenciaponto.domain.acesso.FonteDePermissoes;
import br.com.conferenciaponto.domain.acesso.MudancaDeAcesso;
import br.com.conferenciaponto.domain.acesso.PerfilDeAcesso;
import br.com.conferenciaponto.domain.acesso.Permissao;
import br.com.conferenciaponto.domain.acesso.PermissoesDaAdministracao;
import br.com.conferenciaponto.domain.acesso.PermissoesDoPonto;
import br.com.conferenciaponto.domain.model.Perfil;
import br.com.conferenciaponto.domain.port.AcessosRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Os acessos em memória, já com os três perfis do sistema, como a V16 cria. */
class AcessosEmMemoria implements AcessosRepository {

    /** Um módulo de mentira: duas permissões, a segunda com requisito (como pesquisar e curar a base). */
    static final FonteDePermissoes MODULO = new FonteDePermissoes() {
        @Override
        public String grupo() {
            return "Módulo de teste";
        }

        @Override
        public int ordem() {
            return 20;
        }

        @Override
        public List<Permissao> permissoes() {
            return List.of(Permissao.de("teste.ler", "Ler no módulo", null),
                    Permissao.de("teste.editar", "Editar no módulo", null).requer("teste.ler"));
        }
    };

    static CatalogoDePermissoes catalogo() {
        return new CatalogoDePermissoes(List.of(new PermissoesDoPonto(), new PermissoesDaAdministracao(), MODULO));
    }

    private final Map<UUID, PerfilDeAcesso> perfis = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> perfisDe = new HashMap<>();
    private final Map<UUID, List<AjusteDeAcesso>> ajustesDe = new HashMap<>();
    final List<MudancaDeAcesso> historico = new ArrayList<>();

    AcessosEmMemoria() {
        for (Perfil p : Perfil.values()) {
            UUID id = UUID.randomUUID();
            perfis.put(id, new PerfilDeAcesso(id, p.nomeInicial(), null, p.todas(), p,
                    p.todas() ? Set.of() : p.permissoesIniciais(), Instant.EPOCH, "sistema"));
        }
    }

    UUID idDo(Perfil perfil) {
        return perfilDoSistema(perfil).orElseThrow().id();
    }

    @Override
    public List<PerfilDeAcesso> perfis() {
        return perfis.values().stream().sorted(Comparator.comparing(PerfilDeAcesso::nome, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    @Override
    public Optional<PerfilDeAcesso> perfil(UUID id) {
        return Optional.ofNullable(perfis.get(id));
    }

    @Override
    public Optional<PerfilDeAcesso> perfilDoSistema(Perfil perfil) {
        return perfis.values().stream().filter(p -> p.sistema() == perfil).findFirst();
    }

    @Override
    public void salvarPerfil(PerfilDeAcesso perfil) {
        perfis.put(perfil.id(), perfil);
    }

    @Override
    public void excluirPerfil(UUID id) {
        perfis.remove(id);
        perfisDe.values().forEach(s -> s.remove(id));
    }

    @Override
    public Map<UUID, Set<UUID>> perfisPorUsuario() {
        Map<UUID, Set<UUID>> copia = new HashMap<>();
        perfisDe.forEach((u, s) -> {
            if (!s.isEmpty()) {
                copia.put(u, new LinkedHashSet<>(s));
            }
        });
        return copia;
    }

    @Override
    public Set<UUID> perfisDo(UUID usuarioId) {
        return new LinkedHashSet<>(perfisDe.getOrDefault(usuarioId, Set.of()));
    }

    @Override
    public List<PerfilDeAcesso> perfisCompletosDo(UUID usuarioId) {
        return perfisDo(usuarioId).stream().map(perfis::get).filter(p -> p != null).toList();
    }

    @Override
    public void definirPerfis(UUID usuarioId, Collection<UUID> ids, String quem, Instant quando) {
        perfisDe.put(usuarioId, new LinkedHashSet<>(ids));
    }

    @Override
    public Map<UUID, List<AjusteDeAcesso>> ajustesPorUsuario() {
        Map<UUID, List<AjusteDeAcesso>> copia = new HashMap<>();
        ajustesDe.forEach((u, a) -> {
            if (!a.isEmpty()) {
                copia.put(u, List.copyOf(a));
            }
        });
        return copia;
    }

    @Override
    public List<AjusteDeAcesso> ajustesDo(UUID usuarioId) {
        return List.copyOf(ajustesDe.getOrDefault(usuarioId, List.of()));
    }

    @Override
    public void definirAjustes(UUID usuarioId, Collection<AjusteDeAcesso> ajustes, String quem, Instant quando) {
        ajustesDe.put(usuarioId, List.copyOf(ajustes));
    }

    @Override
    public void registrar(MudancaDeAcesso mudanca) {
        historico.add(mudanca);
    }

    @Override
    public List<MudancaDeAcesso> historico(UUID usuarioId, int limite) {
        List<MudancaDeAcesso> lista = new ArrayList<>(historico);
        java.util.Collections.reverse(lista);
        return lista.stream().filter(m -> usuarioId == null || usuarioId.equals(m.usuarioId())).limit(limite).toList();
    }
}
