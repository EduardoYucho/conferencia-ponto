package br.com.conferenciaponto.modulos.atendimento.application.analise;

import java.time.Instant;
import java.util.UUID;

/**
 * A análise vista pelo processamento: pedir as análises que faltam, esquecer as de um arquivo que mudou e o
 * panorama para a tela. {@link #NENHUMA} é o processamento sem análise (só os downloads).
 */
public interface EtapaDeAnalise {

    EtapaDeAnalise NENHUMA = new EtapaDeAnalise() {
        @Override
        public int pedirParaOAtendimento(UUID atendimentoId, Instant agora) {
            return 0;
        }

        @Override
        public boolean pedir(UUID atendimentoId, UUID arquivoId, Instant agora) {
            return false;
        }

        @Override
        public void esquecer(UUID arquivoId) {
        }

        @Override
        public PanoramaDasAnalises panorama(UUID atendimentoId) {
            return PanoramaDasAnalises.VAZIO;
        }
    };

    /**
     * Põe na fila a análise dos arquivos prontos que ainda não têm (as que falharam por falta de chave voltam; as
     * outras falhas só com "tentar de novo").
     *
     * @return quantas foram pedidas
     */
    int pedirParaOAtendimento(UUID atendimentoId, Instant agora);

    /** Põe na fila a análise de um arquivo (se ele é analisável, está pronto e ainda não tem). */
    boolean pedir(UUID atendimentoId, UUID arquivoId, Instant agora);

    /** O conteúdo do arquivo mudou ou ele saiu: as análises dele deixam de valer. */
    void esquecer(UUID arquivoId);

    PanoramaDasAnalises panorama(UUID atendimentoId);
}
