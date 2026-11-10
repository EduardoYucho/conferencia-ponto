package br.com.conferenciaponto.modulos.fiscal.application.coleta;

import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor;

import java.time.Clock;

/** Acesso, para os testes de outros pacotes, ao que é do pacote da coleta. */
public final class LerAgoraDeTeste {

    private LerAgoraDeTeste() {
    }

    public static LerAgora criar(ColetarSituacao coleta, RegistroDoMonitor registro, Clock clock) {
        return new LerAgora(coleta, registro, clock);
    }

    public static void parar(ColetarSituacao coleta) {
        coleta.parar();
    }
}
