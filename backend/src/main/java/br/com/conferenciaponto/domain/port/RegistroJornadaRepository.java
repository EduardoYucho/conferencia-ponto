package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.SaldoMensal;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Porta de saída: persistência dos registros de jornada. */
public interface RegistroJornadaRepository {

    Optional<RegistroJornada> buscarPorData(LocalDate data);

    /** Registros entre as datas (inclusive), ordenados por data. */
    List<RegistroJornada> listarPorPeriodo(LocalDate inicio, LocalDate fim);

    RegistroJornada salvar(RegistroJornada registro);

    void excluir(RegistroJornada registro);

    /** Consolidação mensal do ano, com saldo anual acumulado mês a mês. */
    List<SaldoMensal> consolidarAno(int ano);

    /**
     * Consolidação mês a mês dos dias entre as datas (inclusive), com o saldo acumulado desde o início
     * do período em {@code saldoAnualAcumuladoSegundos}. Usada no ciclo do banco de horas.
     */
    List<SaldoMensal> consolidarPeriodo(LocalDate inicio, LocalDate fim);
}
