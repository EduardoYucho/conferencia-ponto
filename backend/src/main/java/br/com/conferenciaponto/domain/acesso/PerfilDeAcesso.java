package br.com.conferenciaponto.domain.acesso;

import br.com.conferenciaponto.domain.model.Perfil;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Um perfil de acesso: um grupo de permissões com nome, criado e editado na tela. Quem tem o perfil pode o que
 * ele libera (somado aos outros perfis da pessoa).
 *
 * @param todas     tem todas as permissões, inclusive as que vierem depois (só o Administrador): não muda
 * @param sistema   o perfil que o sistema cria (não é excluído); null = criado na tela
 * @param permissoes os códigos que o perfil libera (com {@code todas}, fica vazio)
 */
public record PerfilDeAcesso(UUID id, String nome, String descricao, boolean todas, Perfil sistema,
                             Set<String> permissoes, Instant atualizadoEm, String atualizadoPor) {

    public PerfilDeAcesso {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(nome, "nome");
        permissoes = todas || permissoes == null ? Set.of() : Set.copyOf(new TreeSet<>(permissoes));
    }

    public static PerfilDeAcesso novo(String nome, String descricao, Set<String> permissoes, String quem, Instant quando) {
        return new PerfilDeAcesso(UUID.randomUUID(), nome, descricao, false, null, permissoes, quando, quem);
    }

    /** Criado pelo sistema (Administrador, Usuário ou Coordenador): pode ser editado, menos o Administrador, mas não excluído. */
    public boolean doSistema() {
        return sistema != null;
    }

    /** O Administrador: nem as permissões nem o nome mudam. */
    public boolean bloqueado() {
        return todas;
    }

    /** O que o perfil libera hoje, só com as permissões que existem no catálogo. */
    public Set<String> permissoesNo(CatalogoDePermissoes catalogo) {
        return todas ? catalogo.codigos() : catalogo.soAsConhecidas(permissoes);
    }

    public PerfilDeAcesso comDados(String novoNome, String novaDescricao, Set<String> novasPermissoes, String quem,
                                   Instant quando) {
        return new PerfilDeAcesso(id, novoNome, novaDescricao, todas, sistema, novasPermissoes, quando, quem);
    }
}
