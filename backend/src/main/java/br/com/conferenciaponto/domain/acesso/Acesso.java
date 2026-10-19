package br.com.conferenciaponto.domain.acesso;

import br.com.conferenciaponto.domain.model.Perfil;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * O que uma pessoa pode agora: o nome dos perfis dela e as permissões que valem (já com os ajustes e os
 * requisitos). Vem do banco a cada requisição, então uma mudança vale na hora.
 */
public record Acesso(List<String> perfis, Set<String> permissoes) {

    public static final Acesso NENHUM = new Acesso(List.of(), Set.of());

    public Acesso {
        perfis = perfis == null ? List.of() : List.copyOf(perfis);
        permissoes = permissoes == null ? Set.of() : Set.copyOf(new TreeSet<>(permissoes));
    }

    public boolean pode(String permissao) {
        return permissoes.contains(permissao);
    }

    /** As permissões, em ordem alfabética (para a API e o log). */
    public List<String> ordenadas() {
        return List.copyOf(new TreeSet<>(permissoes));
    }

    /**
     * O acesso com estes perfis do sistema, como eles vêm de fábrica (o Administrador com todas as permissões do
     * ponto e da administração). Para os usuários iniciais e os testes; o acesso de verdade vem do banco.
     */
    public static Acesso de(Perfil... perfis) {
        Set<String> codigos = new LinkedHashSet<>();
        Arrays.stream(perfis).forEach(p -> codigos.addAll(p.permissoesIniciais()));
        return new Acesso(Arrays.stream(perfis).map(Perfil::nomeInicial).sorted(String.CASE_INSENSITIVE_ORDER).toList(),
                codigos);
    }

    public static Acesso administrador() {
        return de(Perfil.ROLE_ADMIN);
    }

    public static Acesso usuario() {
        return de(Perfil.ROLE_USER);
    }

    public static Acesso coordenador() {
        return de(Perfil.ROLE_VIEWER);
    }

    /** O mesmo acesso com mais estas permissões. */
    public Acesso com(String... mais) {
        Set<String> todas = new LinkedHashSet<>(permissoes);
        todas.addAll(Arrays.asList(mais));
        return new Acesso(perfis, todas);
    }

    /** O mesmo acesso sem estas permissões. */
    public Acesso sem(String... menos) {
        Set<String> restantes = new LinkedHashSet<>(permissoes);
        Arrays.asList(menos).forEach(restantes::remove);
        return new Acesso(perfis, restantes);
    }
}
