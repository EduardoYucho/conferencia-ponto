package br.com.conferenciaponto.application.evento;

import java.util.UUID;

import java.time.LocalDate;

/** Um lançamento avulso no banco de horas foi criado ou removido (o saldo do mês e do ciclo mudou). */
public record LancamentoBancoAlteradoEvento(UUID usuarioId, LocalDate data, String descricao) {
}
