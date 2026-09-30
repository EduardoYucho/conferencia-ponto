package br.com.conferenciaponto.infrastructure.persistence;

import br.com.conferenciaponto.domain.model.Batidas;
import br.com.conferenciaponto.domain.model.RegistroJornada;

import java.util.Set;

/** Conversão entre o agregado de domínio e a entidade JPA. */
final class RegistroJornadaMapper {

    private RegistroJornadaMapper() {
    }

    static RegistroJornada paraDominio(RegistroJornadaEntity entity) {
        Batidas batidas = new Batidas(entity.getEntrada1(), entity.getSaida1(),
                entity.getEntrada2(), entity.getSaida2(), entity.getEntrada3(), entity.getSaida3());
        return RegistroJornada.restaurar(
                entity.getId(),
                entity.getDataReferencia(),
                entity.getTipoDia(),
                batidas,
                entity.isRegistroManual(),
                Set.copyOf(HorariosTexto.ler(entity.getHorariosAjustados())),
                entity.getJornadaPrevistaSegundos(),
                entity.getSegundosTrabalhados(),
                entity.getSaldoDiarioSegundos());
    }

    static void copiar(RegistroJornada origem, RegistroJornadaEntity destino) {
        Batidas batidas = origem.getBatidas();
        destino.setEntrada1(batidas.entrada1());
        destino.setSaida1(batidas.saida1());
        destino.setEntrada2(batidas.entrada2());
        destino.setSaida2(batidas.saida2());
        destino.setEntrada3(batidas.entrada3());
        destino.setSaida3(batidas.saida3());
        destino.setTipoDia(origem.getTipoDia());
        destino.setJornadaPrevistaSegundos(origem.getJornadaPrevistaSegundos());
        destino.setSegundosTrabalhados(origem.getSegundosTrabalhados());
        destino.setSaldoDiarioSegundos(origem.getSaldoDiarioSegundos());
        destino.setRegistroManual(origem.isRegistroManual());
        destino.setHorariosAjustados(HorariosTexto.escreverOuNulo(origem.getHorariosAjustados()));
    }
}
