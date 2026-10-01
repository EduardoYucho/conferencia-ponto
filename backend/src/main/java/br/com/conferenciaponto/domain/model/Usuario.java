package br.com.conferenciaponto.domain.model;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Usuário do portal. {@code senhaHash} nunca sai da aplicação (não vai para a API nem para o token).
 *
 * <p>Quem tem perfil de escrita (ADMIN/USER) é <b>titular</b>: tem as próprias batidas, horário, banco de
 * horas e pasta de comprovantes. O perfil VIEWER (coordenação) só consulta os dados dos titulares.
 *
 * @param pastaComprovantes pasta monitorada com os PDFs deste usuário (nula = sem monitoramento)
 * @param trocarSenha       a senha é provisória: precisa ser trocada no próximo acesso
 */
public record Usuario(
        UUID id,
        String login,
        String nome,
        String senhaHash,
        boolean ativo,
        Set<Perfil> perfis,
        Instant ultimoLoginEm,
        String pastaComprovantes,
        boolean trocarSenha) {

    public Usuario {
        Objects.requireNonNull(id, "id");
        login = normalizarLogin(login);
        Objects.requireNonNull(senhaHash, "senhaHash");
        perfis = perfis == null || perfis.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(perfis));
        pastaComprovantes = pastaComprovantes == null || pastaComprovantes.isBlank() ? null : pastaComprovantes.strip();
    }

    public Usuario(UUID id, String login, String nome, String senhaHash, boolean ativo, Set<Perfil> perfis,
                   Instant ultimoLoginEm) {
        this(id, login, nome, senhaHash, ativo, perfis, ultimoLoginEm, null, false);
    }

    public static Usuario novo(String login, String nome, String senhaHash, Set<Perfil> perfis) {
        return new Usuario(UUID.randomUUID(), login, nome, senhaHash, true, perfis, null, null, false);
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

    /** Tem os próprios dados de ponto (ADMIN ou USER). */
    public boolean isTitular() {
        return podeEscrever();
    }

    /** Pode consultar os dados de qualquer titular (ADMIN ou coordenação). */
    public boolean podeVerTodos() {
        return perfis.contains(Perfil.ROLE_ADMIN) || perfis.contains(Perfil.ROLE_VIEWER);
    }

    public boolean isAdmin() {
        return perfis.contains(Perfil.ROLE_ADMIN);
    }

    /** Nova senha definida pelo próprio usuário (encerra a senha provisória). */
    public Usuario comSenha(String novoHash) {
        return new Usuario(id, login, nome, novoHash, ativo, perfis, ultimoLoginEm, pastaComprovantes, false);
    }

    /** Senha provisória definida pelo administrador: precisa ser trocada no próximo acesso. */
    public Usuario comSenhaProvisoria(String novoHash) {
        return new Usuario(id, login, nome, novoHash, ativo, perfis, ultimoLoginEm, pastaComprovantes, true);
    }

    public Usuario registrarLogin(Instant quando) {
        return new Usuario(id, login, nome, senhaHash, ativo, perfis, quando, pastaComprovantes, trocarSenha);
    }

    public Usuario comPasta(String pasta) {
        return new Usuario(id, login, nome, senhaHash, ativo, perfis, ultimoLoginEm, pasta, trocarSenha);
    }

    public Usuario comDados(String novoNome, Set<Perfil> novosPerfis, boolean ativoAgora) {
        return new Usuario(id, login, novoNome, senhaHash, ativoAgora, novosPerfis, ultimoLoginEm, pastaComprovantes,
                trocarSenha);
    }
}
