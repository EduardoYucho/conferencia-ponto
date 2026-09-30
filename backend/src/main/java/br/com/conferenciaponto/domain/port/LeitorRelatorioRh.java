package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.RelatorioRhLido;

/** Porta de saída: extração do relatório de banco de horas do RH (PDF). */
public interface LeitorRelatorioRh {

    /**
     * @throws br.com.conferenciaponto.domain.exception.RegraNegocioException se o arquivo não for um
     *         relatório de banco de horas legível
     */
    RelatorioRhLido ler(byte[] pdf);
}
