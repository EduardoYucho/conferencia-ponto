package br.com.conferenciaponto.modulos.atendimento.application.acesso;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Uma pessoa na tela de acessos: quem é, o perfil no ponto e se pode usar o gerador. */
public record AcessoAoGeradorView(UUID usuarioId, String login, String nome, List<String> perfis, boolean gerador,
                                  String concedidoPor, Instant atualizadoEm) {
}
