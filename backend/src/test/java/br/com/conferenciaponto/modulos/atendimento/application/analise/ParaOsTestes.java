package br.com.conferenciaponto.modulos.atendimento.application.analise;

import br.com.conferenciaponto.modulos.atendimento.application.video.QuadrosDoVideo;
import br.com.conferenciaponto.modulos.atendimento.domain.analise.Analises;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.ChavesGemini;
import br.com.conferenciaponto.modulos.atendimento.domain.chave.Cofre;
import br.com.conferenciaponto.modulos.atendimento.domain.ia.ModeloDeIA;
import br.com.conferenciaponto.modulos.atendimento.infrastructure.video.ExtratorQuadrosFfmpeg;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * A tarefa "analisar" montada para os testes de outros pacotes (sem esperar de verdade entre as consultas). Sem
 * informar os quadros, é como um servidor sem o ffmpeg.
 */
public final class ParaOsTestes {

    private ParaOsTestes() {
    }

    public static AnalisarArquivo analisar(Analises analises, ChavesGemini chaves, Cofre cofre, ModeloDeIA modelo,
                                           PastaDosAtendimentos pasta, ConfiguracaoDaAnalise configuracao, Clock clock) {
        return analisar(analises, chaves, cofre, modelo, pasta, configuracao, clock, semFfmpeg(pasta));
    }

    public static AnalisarArquivo analisar(Analises analises, ChavesGemini chaves, Cofre cofre, ModeloDeIA modelo,
                                           PastaDosAtendimentos pasta, ConfiguracaoDaAnalise configuracao, Clock clock,
                                           QuadrosDoVideo quadros) {
        return new AnalisarArquivo(analises, chaves, cofre, modelo, pasta, configuracao, new ObjectMapper(), clock,
                Duration.ofSeconds(5), Duration.ofMillis(1), tempo -> { }, quadros);
    }

    /** Os quadros num servidor sem o ffmpeg. */
    public static QuadrosDoVideo semFfmpeg(PastaDosAtendimentos pasta) {
        return new QuadrosDoVideo(pasta, new ExtratorQuadrosFfmpeg(java.util.List.of(), Duration.ofSeconds(5)));
    }
}
