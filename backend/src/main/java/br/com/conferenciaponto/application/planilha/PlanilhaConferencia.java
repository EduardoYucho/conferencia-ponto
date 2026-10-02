package br.com.conferenciaponto.application.planilha;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Planilha de conferência de uma pessoa: a aba de resumo (banco de horas, total de cada mês, horário de
 * trabalho) e uma aba por mês, do mais recente para o mais antigo. É o mesmo conteúdo no arquivo Excel e na
 * planilha do Google.
 *
 * @param nomePessoa de quem são os dados
 * @param loginPessoa login (vai no nome do arquivo)
 */
public record PlanilhaConferencia(String nomePessoa, String loginPessoa, List<Aba> abas, Instant geradaEm) {

    public PlanilhaConferencia {
        abas = List.copyOf(abas);
    }

    public Optional<Aba> aba(int id) {
        return abas.stream().filter(a -> a.id() == id).findFirst();
    }
}
