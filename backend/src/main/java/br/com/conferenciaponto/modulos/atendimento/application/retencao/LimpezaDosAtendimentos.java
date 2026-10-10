package br.com.conferenciaponto.modulos.atendimento.application.retencao;

import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Arquivos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.Atendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.atendimento.PastaDosAtendimentos;
import br.com.conferenciaponto.modulos.atendimento.domain.fila.Tarefas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Retenção (uma vez por dia, às 03:30): os arquivos dos atendimentos (PDF, anexos, ligações, vídeo e prints) saem
 * do disco depois de {@code atendimento.retencao.dias-arquivos} (30); o atendimento inteiro, com os textos, depois
 * de {@code dias-textos} (180). Um atendimento que não pôde ser limpo (arquivo aberto) fica para o dia seguinte.
 */
@Component
public class LimpezaDosAtendimentos {

    private static final Logger log = LoggerFactory.getLogger(LimpezaDosAtendimentos.class);

    /** O que a limpeza fez. */
    public record Resultado(int arquivosApagados, int atendimentosApagados, int falhas) {
    }

    private final Atendimentos atendimentos;
    private final Arquivos arquivos;
    private final Tarefas tarefas;
    private final PastaDosAtendimentos pasta;
    private final Clock clock;

    public LimpezaDosAtendimentos(Atendimentos atendimentos, Arquivos arquivos, Tarefas tarefas, PastaDosAtendimentos pasta,
                                  Clock clock) {
        this.atendimentos = atendimentos;
        this.arquivos = arquivos;
        this.tarefas = tarefas;
        this.pasta = pasta;
        this.clock = clock;
    }

    @Scheduled(cron = "${atendimento.retencao.cron:0 30 3 * * *}", zone = "${ponto.fuso-horario:America/Sao_Paulo}")
    public void agendada() {
        try {
            Resultado r = limpar();
            if (r.arquivosApagados() + r.atendimentosApagados() + r.falhas() > 0) {
                log.info("Retenção dos atendimentos: arquivos de {} apagados, {} atendimento(s) apagados, {} falha(s)",
                        r.arquivosApagados(), r.atendimentosApagados(), r.falhas());
            }
        } catch (RuntimeException e) {
            log.error("A retenção dos atendimentos falhou (tenta de novo amanhã): {}", e.toString());
        }
    }

    public Resultado limpar() {
        Instant agora = clock.instant();
        int falhas = 0;
        int inteiros = 0;
        for (UUID id : atendimentos.comTextosVencidos(agora)) {
            try {
                tarefas.cancelarDoAtendimento(id, agora);
                pasta.apagar(id);
                atendimentos.apagarDeVez(id);
                inteiros++;
            } catch (RuntimeException e) {
                falhas++;
                log.warn("Retenção: o atendimento {} não pôde ser apagado agora: {}", id, e.toString());
            }
        }
        int soArquivos = 0;
        for (UUID id : atendimentos.comArquivosVencidos(agora)) {
            try {
                tarefas.cancelarDoAtendimento(id, agora);
                pasta.apagarArquivosDoAtendimento(id);
                arquivos.marcarTodosRemovidos(id);
                atendimentos.marcarArquivosApagados(id, agora);
                soArquivos++;
            } catch (RuntimeException e) {
                falhas++;
                log.warn("Retenção: os arquivos do atendimento {} não puderam ser apagados agora: {}", id, e.toString());
            }
        }
        return new Resultado(soArquivos, inteiros, falhas);
    }
}
