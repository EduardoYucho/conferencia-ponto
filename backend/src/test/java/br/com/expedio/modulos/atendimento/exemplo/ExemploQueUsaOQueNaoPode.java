package br.com.expedio.modulos.atendimento.exemplo;

import br.com.expedio.domain.service.MotorCalculoJornadaService;

/** Só para o RegraDosModulosPegaViolacaoTest: um "módulo" usando o motor de cálculo do ponto (proibido). */
public class ExemploQueUsaOQueNaoPode {

    public Object usar(MotorCalculoJornadaService motor) {
        return motor;
    }
}
