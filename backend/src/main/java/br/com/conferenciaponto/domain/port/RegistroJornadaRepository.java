package br.com.conferenciaponto.domain.port;

import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.SaldoMensal;

import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import java.util.Optional;

/** Porta de saída: persistência dos registros de jornada (sempre de um usuário). */
public interface RegistroJornadaRepository {

    Optional<RegistroJornada> buscarPorData(UUID usuarioId, LocalDate data);

    Optional<RegistroJornada> buscarPorId(UUID id);

    /** Registros entre as datas (inclusive), ordenados por data. */
    List<RegistroJornada> listarPorPeriodo(UUID usuarioId, LocalDate inicio, LocalDate fim);

    /** Registros de todos os usuários numa data (ex.: feriado cadastrado vale para todos). */
    List<RegistroJornada> listarPorData(LocalDate data);

    /** Usuários com algum registro (recálculo na subida). */
    List<UUID> usuariosComRegistros();

    RegistroJornada salvar(RegistroJornada registro);

    void excluir(RegistroJornada registro);

    /** Consolidação mensal do ano, com saldo anual acumulado mês a mês. */
    List<SaldoMensal> consolidarAno(UUID usuarioId, int ano);

    /**
     * Consolidação mês a mês dos dias entre as datas (inclusive), com o saldo acumulado desde o início
     * do período em {@code saldoAnualAcumuladoSegundos}. Usada no ciclo do banco de horas.
     */
    List<SaldoMensal> consolidarPeriodo(UUID usuarioId, LocalDate inicio, LocalDate fim);
}
