package br.com.conferenciaponto.modulos.conhecimento.application.acesso;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Uma pessoa na tela de acessos: quem é, o perfil no ponto e o que pode fazer na base. */
public record AcessoABaseView(UUID usuarioId, String login, String nome, List<String> perfis, boolean pesquisar,
                              boolean curar, String concedidoPor, Instant atualizadoEm) {
}
