package br.com.conferenciaponto.infrastructure.config;

import br.com.conferenciaponto.application.usecase.RecalcularJornadasUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Ao subir, garante que todos os dias gravados estão calculados com a regra atual. Roda antes do
 * monitor de PDFs (que começa em ApplicationReadyEvent) e custa milissegundos quando nada mudou.
 */
@Component
@Order(10)
class RecalculoJornadasNaInicializacao implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecalculoJornadasNaInicializacao.class);

    private final RecalcularJornadasUseCase recalcular;

    RecalculoJornadasNaInicializacao(RecalcularJornadasUseCase recalcular) {
        this.recalcular = recalcular;
    }

    @Override
    public void run(ApplicationArguments args) {
        int alterados = recalcular.executar();
        if (alterados > 0) {
            log.info("{} dia(s) recalculado(s) com a regra atual de cálculo", alterados);
        }
    }
}
