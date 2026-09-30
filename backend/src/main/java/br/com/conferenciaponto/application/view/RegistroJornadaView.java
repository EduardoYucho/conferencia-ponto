package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.MarcacaoApurada;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.StatusJornada;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Modelo de leitura de um dia. Tempo trabalhado e saldo (em segundos) vêm do registro persistido
 * (fonte oficial do banco de horas); o detalhamento por batida vem do motor.
 */
public record RegistroJornadaView(
        UUID id,
        LocalDate data,
        TipoDia tipoDia,
        boolean registroManual,
        List<LocalTime> horariosAjustados,
        StatusJornada status,
        List<MarcacaoApurada> marcacoes,
        int jornadaPrevistaSegundos,
        int segundosTrabalhados,
        Integer saldoDiarioSegundos) {

    public static RegistroJornadaView de(RegistroJornada registro, MotorCalculoJornadaService motor) {
        return new RegistroJornadaView(
                registro.getId(),
                registro.getDataReferencia(),
                registro.getTipoDia(),
                registro.isRegistroManual(),
                List.copyOf(registro.getHorariosAjustados()),
                registro.getStatus(),
                registro.apurar(motor).marcacoes(),
                registro.getJornadaPrevistaSegundos(),
                registro.getSegundosTrabalhados(),
                registro.getSaldoDiarioSegundos());
    }
}
