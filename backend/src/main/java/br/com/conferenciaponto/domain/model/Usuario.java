package br.com.conferenciaponto.domain.model;

import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.acesso.PermissoesDoPonto;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Usuário do portal. {@code senhaHash} nunca sai da aplicação (não vai para a API nem para o token).
 *
 * <p>Quem tem o próprio ponto ({@code ponto.proprio}) é <b>titular</b>: tem as próprias batidas, horário, banco
 * de horas e pasta de comprovantes. O que a pessoa pode fazer ({@code acesso}) é a soma dos perfis dela com os
 * ajustes só dela; vem do banco a cada requisição e não é gravado junto com o usuário.
 *
 * @param acesso            os perfis e as permissões que valem agora (ver CatalogoDePermissoes)
 * @param pastaComprovantes pasta monitorada com os PDFs deste usuário (nula = sem monitoramento)
 * @param trocarSenha       a senha é provisória: precisa ser trocada no próximo acesso
 */
public record Usuario(
        UUID id,
        String login,
        String nome,
        String senhaHash,
        boolean ativo,
        Acesso acesso,
        Instant ultimoLoginEm,
        String pastaComprovantes,
        boolean trocarSenha) {

    public Usuario {
        Objects.requireNonNull(id, "id");
        login = normalizarLogin(login);
        Objects.requireNonNull(senhaHash, "senhaHash");
        acesso = acesso == null ? Acesso.NENHUM : acesso;
        pastaComprovantes = pastaComprovantes == null || pastaComprovantes.isBlank() ? null : pastaComprovantes.strip();
    }

    public Usuario(UUID id, String login, String nome, String senhaHash, boolean ativo, Acesso acesso,
                   Instant ultimoLoginEm) {
        this(id, login, nome, senhaHash, ativo, acesso, ultimoLoginEm, null, false);
    }

    public static Usuario novo(String login, String nome, String senhaHash, Acesso acesso) {
        return new Usuario(UUID.randomUUID(), login, nome, senhaHash, true, acesso, null, null, false);
    }

    public static String normalizarLogin(String login) {
        if (login == null || login.isBlank()) {
            throw new IllegalArgumentException("Login obrigatório");
        }
        return login.trim().toLowerCase(Locale.ROOT);
    }

    /** Tem esta permissão agora (ex.: {@code PermissoesDoPonto.LANCAR}). */
    public boolean pode(String permissao) {
        return acesso.pode(permissao);
    }

    /** Tem os próprios dados de ponto. */
    public boolean isTitular() {
        return pode(PermissoesDoPonto.PROPRIO);
    }

    /** Lança e corrige o próprio ponto. */
    public boolean podeEscrever() {
        return isTitular() && pode(PermissoesDoPonto.LANCAR);
    }

    /** Consulta os dados de qualquer titular. */
    public boolean podeVerTodos() {
        return pode(PermissoesDoPonto.EQUIPE);
    }

    /** Nova senha definida pelo próprio usuário (encerra a senha provisória). */
    public Usuario comSenha(String novoHash) {
        return new Usuario(id, login, nome, novoHash, ativo, acesso, ultimoLoginEm, pastaComprovantes, false);
    }

    /** Senha provisória definida pelo administrador: precisa ser trocada no próximo acesso. */
    public Usuario comSenhaProvisoria(String novoHash) {
        return new Usuario(id, login, nome, novoHash, ativo, acesso, ultimoLoginEm, pastaComprovantes, true);
    }

    public Usuario registrarLogin(Instant quando) {
        return new Usuario(id, login, nome, senhaHash, ativo, acesso, quando, pastaComprovantes, trocarSenha);
    }

    public Usuario comPasta(String pasta) {
        return new Usuario(id, login, nome, senhaHash, ativo, acesso, ultimoLoginEm, pasta, trocarSenha);
    }

    public Usuario comDados(String novoNome, boolean ativoAgora) {
        return new Usuario(id, login, novoNome, senhaHash, ativoAgora, acesso, ultimoLoginEm, pastaComprovantes,
                trocarSenha);
    }

    /** O mesmo usuário com outro acesso (relido do banco depois de uma mudança de perfis ou ajustes). */
    public Usuario comAcesso(Acesso novo) {
        return new Usuario(id, login, nome, senhaHash, ativo, novo, ultimoLoginEm, pastaComprovantes, trocarSenha);
    }
}
