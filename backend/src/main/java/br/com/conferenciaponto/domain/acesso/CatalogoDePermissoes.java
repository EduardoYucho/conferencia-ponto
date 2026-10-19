package br.com.conferenciaponto.domain.acesso;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Todas as permissões que existem (as do ponto e as dos módulos), em grupos, e a conta do que uma pessoa pode:
 * a soma dos perfis dela, mais o que foi liberado e menos o que foi retirado só para ela. Uma permissão cujo
 * requisito ficou de fora não vale (ex.: curar a base sem pesquisar). Códigos gravados que não existem mais (de
 * um módulo retirado) são ignorados.
 */
public final class CatalogoDePermissoes {

    /** Um grupo da tela, com as permissões na ordem em que aparecem. */
    public record Grupo(String nome, List<Permissao> permissoes) {
    }

    /**
     * De onde vem (ou por que não vale) uma permissão de uma pessoa.
     *
     * @param perfis   os perfis dela que dão a permissão
     * @param ajuste   true = liberada só para ela; false = retirada só dela; null = sem ajuste
     * @param faltando o requisito que ela não tem (a permissão não vale); null = nenhum
     */
    public record Origem(String permissao, boolean pode, List<String> perfis, Boolean ajuste, String faltando) {
    }

    private final List<Grupo> grupos;
    private final Map<String, Permissao> porCodigo;

    public CatalogoDePermissoes(Collection<? extends FonteDePermissoes> fontes) {
        List<FonteDePermissoes> ordenadas = new ArrayList<>(fontes);
        ordenadas.sort(Comparator.comparingInt(FonteDePermissoes::ordem).thenComparing(FonteDePermissoes::grupo));
        Map<String, Permissao> codigos = new LinkedHashMap<>();
        List<Grupo> lista = new ArrayList<>();
        for (FonteDePermissoes fonte : ordenadas) {
            List<Permissao> doGrupo = List.copyOf(fonte.permissoes());
            for (Permissao p : doGrupo) {
                if (codigos.putIfAbsent(p.codigo(), p) != null) {
                    throw new IllegalStateException("Permissão declarada duas vezes: " + p.codigo());
                }
            }
            lista.add(new Grupo(fonte.grupo(), doGrupo));
        }
        for (Permissao p : codigos.values()) {
            if (p.requer() != null && !codigos.containsKey(p.requer())) {
                throw new IllegalStateException("A permissão " + p.codigo() + " requer " + p.requer() + ", que não existe.");
            }
        }
        this.grupos = List.copyOf(lista);
        this.porCodigo = Collections.unmodifiableMap(codigos);
    }

    public List<Grupo> grupos() {
        return grupos;
    }

    /** Todos os códigos, na ordem da tela. */
    public Set<String> codigos() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(porCodigo.keySet()));
    }

    public boolean existe(String codigo) {
        return porCodigo.containsKey(codigo);
    }

    public Optional<Permissao> permissao(String codigo) {
        return Optional.ofNullable(porCodigo.get(codigo));
    }

    /** Só os códigos que existem, na ordem da tela. */
    public Set<String> soAsConhecidas(Collection<String> codigos) {
        Set<String> conhecidas = new LinkedHashSet<>();
        for (String codigo : porCodigo.keySet()) {
            if (codigos.contains(codigo)) {
                conhecidas.add(codigo);
            }
        }
        return Collections.unmodifiableSet(conhecidas);
    }

    /** O acesso da pessoa com estes perfis e estes ajustes: o nome dos perfis e o que vale. */
    public Acesso acesso(Collection<PerfilDeAcesso> perfis, Collection<AjusteDeAcesso> ajustes) {
        return new Acesso(perfis.stream().map(PerfilDeAcesso::nome).sorted(String.CASE_INSENSITIVE_ORDER).toList(),
                efetivas(perfis, ajustes));
    }

    /** O que a pessoa pode, com estes perfis e estes ajustes. */
    public Set<String> efetivas(Collection<PerfilDeAcesso> perfis, Collection<AjusteDeAcesso> ajustes) {
        return soAsConhecidas(comRequisitos(somadas(perfis, ajustes)));
    }

    /** Cada permissão do catálogo, com o que ela vale para a pessoa e por quê (para a tela de acesso). */
    public List<Origem> explicar(Collection<PerfilDeAcesso> perfis, Collection<AjusteDeAcesso> ajustes) {
        Set<String> somadas = somadas(perfis, ajustes);
        Set<String> valem = comRequisitos(somadas);
        Map<String, Boolean> porAjuste = new LinkedHashMap<>();
        ajustes.forEach(a -> porAjuste.put(a.permissao(), a.liberada()));
        List<Origem> origens = new ArrayList<>();
        for (Permissao p : porCodigo.values()) {
            List<String> deQuais = perfis.stream()
                    .filter(perfil -> perfil.permissoesNo(this).contains(p.codigo()))
                    .map(PerfilDeAcesso::nome)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            String faltando = somadas.contains(p.codigo()) && !valem.contains(p.codigo()) ? p.requer() : null;
            origens.add(new Origem(p.codigo(), valem.contains(p.codigo()), deQuais, porAjuste.get(p.codigo()), faltando));
        }
        return List.copyOf(origens);
    }

    private Set<String> somadas(Collection<PerfilDeAcesso> perfis, Collection<AjusteDeAcesso> ajustes) {
        Set<String> somadas = new LinkedHashSet<>();
        for (PerfilDeAcesso perfil : perfis) {
            somadas.addAll(perfil.permissoesNo(this));
        }
        for (AjusteDeAcesso ajuste : ajustes) {
            if (ajuste.liberada()) {
                somadas.add(ajuste.permissao());
            }
        }
        for (AjusteDeAcesso ajuste : ajustes) {
            if (!ajuste.liberada()) {
                somadas.remove(ajuste.permissao());
            }
        }
        return somadas;
    }

    /** Tira, até não sobrar nenhuma, as permissões cujo requisito ficou de fora (o requisito pode ter requisito). */
    private Set<String> comRequisitos(Set<String> codigos) {
        Set<String> valem = new LinkedHashSet<>(codigos);
        boolean tirou;
        do {
            tirou = valem.removeIf(c -> {
                Permissao p = porCodigo.get(c);
                return p != null && p.requer() != null && !valem.contains(p.requer());
            });
        } while (tirou);
        return valem;
    }

}
