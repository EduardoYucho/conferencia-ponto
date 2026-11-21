package br.com.expedio.domain.port;

import br.com.expedio.domain.model.RelatorioRhLido;

/** Porta de saída: extração do relatório de banco de horas do RH (PDF). */
public interface LeitorRelatorioRh {

    /**
     * @throws br.com.expedio.domain.exception.RegraNegocioException se o arquivo não for um
     *         relatório de banco de horas legível
     */
    RelatorioRhLido ler(byte[] pdf);
}
