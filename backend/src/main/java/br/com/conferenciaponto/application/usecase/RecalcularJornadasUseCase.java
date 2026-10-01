package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Refaz o cálculo dos dias gravados com as regras atuais (horário de cada usuário, tolerância, regra do RH).
 * Idempotente: só grava os dias cujo resultado mudou.
 */
@Service
public class RecalcularJornadasUseCase {

    private static final LocalDate INICIO = LocalDate.of(1900, 1, 1);
    private static final LocalDate FIM = LocalDate.of(2999, 12, 31);

    private final RegistroJornadaRepository registros;
    private final RegrasJornada regras;

    public RecalcularJornadasUseCase(RegistroJornadaRepository registros, RegrasJornada regras) {
        this.registros = registros;
        this.regras = regras;
    }

    /**
     * Recalcula todos os dias de todos os usuários (na inicialização).
     *
     * @return quantidade de dias cujo cálculo mudou
     */
    @Transactional
    public int executar() {
        int alterados = 0;
        for (UUID usuarioId : registros.usuariosComRegistros()) {
            for (RegistroJornada registro : registros.listarPorPeriodo(usuarioId, INICIO, FIM)) {
                if (registro.atualizarCalculo(regras.motor(registro))) {
                    registros.salvar(registro);
                    alterados++;
                }
            }
        }
        return alterados;
    }

    /**
     * O horário do usuário mudou a partir de {@code desde}: reclassifica (dia com ou sem expediente) e recalcula
     * os dias dali em diante.
     *
     * @return quantidade de dias cujo tipo ou cálculo mudou
     */
    @Transactional
    public int reavaliar(UUID usuarioId, LocalDate desde) {
        regras.invalidar(usuarioId);
        int alterados = 0;
        for (RegistroJornada registro : registros.listarPorPeriodo(usuarioId, desde, FIM)) {
            LocalDate data = registro.getDataReferencia();
            if (registro.reavaliar(regras.classificar(usuarioId, data), regras.motor(usuarioId, data))) {
                registros.salvar(registro);
                alterados++;
            }
        }
        return alterados;
    }
}
