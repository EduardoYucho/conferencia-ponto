package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Refaz o cálculo dos dias gravados com as regras atuais do motor (ex.: depois da mudança para a
 * regra do RH, com segundos). Idempotente: só grava os dias cujo resultado mudou.
 */
@Service
public class RecalcularJornadasUseCase {

    private static final LocalDate INICIO = LocalDate.of(1900, 1, 1);
    private static final LocalDate FIM = LocalDate.of(2999, 12, 31);

    private final RegistroJornadaRepository registros;
    private final MotorCalculoJornadaService motor;

    public RecalcularJornadasUseCase(RegistroJornadaRepository registros, MotorCalculoJornadaService motor) {
        this.registros = registros;
        this.motor = motor;
    }

    /** @return quantidade de dias cujo cálculo mudou */
    @Transactional
    public int executar() {
        return executar(INICIO, FIM);
    }

    @Transactional
    public int executar(LocalDate inicio, LocalDate fim) {
        int alterados = 0;
        for (RegistroJornada registro : registros.listarPorPeriodo(inicio, fim)) {
            if (registro.atualizarCalculo(motor)) {
                registros.salvar(registro);
                alterados++;
            }
        }
        return alterados;
    }
}
