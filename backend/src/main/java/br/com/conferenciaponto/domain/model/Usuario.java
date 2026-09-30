package br.com.conferenciaponto.domain.model;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Usuário do portal. {@code senhaHash} nunca sai da aplicação (não vai para a API nem para o token).
 */
public record Usuario(
        UUID id,
        String login,
        String nome,
        String senhaHash,
        boolean ativo,
        Set<Perfil> perfis,
        Instant ultimoLoginEm) {

    public Usuario {
        Objects.requireNonNull(id, "id");
        login = normalizarLogin(login);
        Objects.requireNonNull(senhaHash, "senhaHash");
        perfis = perfis == null || perfis.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(perfis));
    }

    public static Usuario novo(String login, String nome, String senhaHash, Set<Perfil> perfis) {
        return new Usuario(UUID.randomUUID(), login, nome, senhaHash, true, perfis, null);
    }

    public static String normalizarLogin(String login) {
        if (login == null || login.isBlank()) {
            throw new IllegalArgumentException("Login obrigatório");
        }
        return login.trim().toLowerCase(Locale.ROOT);
    }

    public boolean podeEscrever() {
        return perfis.stream().anyMatch(Perfil::podeEscrever);
    }

    public Usuario comSenha(String novoHash) {
        return new Usuario(id, login, nome, novoHash, ativo, perfis, ultimoLoginEm);
    }

    public Usuario registrarLogin(Instant quando) {
        return new Usuario(id, login, nome, senhaHash, ativo, perfis, quando);
    }
}
