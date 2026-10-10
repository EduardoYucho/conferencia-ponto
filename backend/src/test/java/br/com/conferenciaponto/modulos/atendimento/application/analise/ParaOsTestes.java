package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.Cofre;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.ModeloDeIA;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;

/** A tarefa "analisar" montada para os testes de outros pacotes (sem esperar de verdade entre as consultas). */
public final class ParaOsTestes {

    private ParaOsTestes() {
    }

    public static AnalisarArquivo analisar(Analises analises, ChavesGemini chaves, Cofre cofre, ModeloDeIA modelo,
                                           PastaDosAtendimentos pasta, ConfiguracaoDaAnalise configuracao, Clock clock) {
        return new AnalisarArquivo(analises, chaves, cofre, modelo, pasta, configuracao, new ObjectMapper(), clock,
                Duration.ofSeconds(5), Duration.ofMillis(1), tempo -> { });
    }
}
