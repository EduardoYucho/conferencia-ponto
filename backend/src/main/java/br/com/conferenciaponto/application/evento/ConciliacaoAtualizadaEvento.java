package br.com.conferenciaponto.application.evento;

/** Algo mudou na conciliação com o RH (relatório conferido, divergência resolvida...). */
public record ConciliacaoAtualizadaEvento(String descricao, int pendentes) {
}
