package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.ConsolidacaoBancoHoras;
import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.view.MesJornadaView;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.application.view.ResumoSaldosView;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.port.AusenciaRepository;
import br.com.conferenciaponto.domain.port.CalendarioFeriados;
import br.com.conferenciaponto.domain.port.LancamentoBancoRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consultas: dia, mês e saldos consolidados (diário, mensal e anual acumulado). Os saldos do mês incluem os
 * lançamentos avulsos no banco de horas.
 */
@Service
@Transactional(readOnly = true)
public class ConsultarJornadaUseCase {

    private final RegistroJornadaRepository repository;
    private final RegrasJornada regras;
    private final AusenciaRepository ausencias;
    private final CalendarioFeriados feriados;
    private final LancamentoBancoRepository lancamentos;
    private final ConsolidacaoBancoHoras consolidacao;

    public ConsultarJornadaUseCase(RegistroJornadaRepository repository, RegrasJornada regras,
                                   AusenciaRepository ausencias, CalendarioFeriados feriados,
                                   LancamentoBancoRepository lancamentos, ConsolidacaoBancoHoras consolidacao) {
        this.repository = repository;
        this.regras = regras;
        this.ausencias = ausencias;
        this.feriados = feriados;
        this.lancamentos = lancamentos;
        this.consolidacao = consolidacao;
    }

    public RegistroJornadaView dia(UUID usuarioId, LocalDate data) {
        return repository.buscarPorData(usuarioId, data)
                .map(registro -> RegistroJornadaView.de(registro, regras.motor(registro)))
                .orElseThrow(() -> new RecursoNaoEncontradoException("REGISTRO_NAO_ENCONTRADO",
                        "Não há registro de jornada em %s.".formatted(data)));
    }

    public MesJornadaView mes(UUID usuarioId, YearMonth referencia) {
        LocalDate inicio = referencia.atDay(1);
        LocalDate fim = referencia.atEndOfMonth();
        List<RegistroJornadaView> dias = repository
                .listarPorPeriodo(usuarioId, inicio, fim)
                .stream()
                .map(registro -> RegistroJornadaView.de(registro, regras.motor(registro)))
                .toList();
        SaldoMensal resumo = consolidarAnoCompleto(usuarioId, referencia.getYear())
                .get(referencia.getMonthValue() - 1);
        return new MesJornadaView(referencia, dias, resumo, ausencias.listarNoPeriodo(usuarioId, inicio, fim),
                feriados.listar(inicio, fim), lancamentos.listarNoPeriodo(usuarioId, inicio, fim),
                expedientes(usuarioId, inicio, fim));
    }

    /** Expediente previsto de cada dia do período pelo horário do usuário (vigente em cada dia). */
    public List<MesJornadaView.Expediente> expedientes(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        List<MesJornadaView.Expediente> lista = new ArrayList<>();
        for (LocalDate d = inicio; !d.isAfter(fim); d = d.plusDays(1)) {
            LocalDate data = d;
            regras.horario(usuarioId, data).gradeDo(data).ifPresent(grade -> lista.add(
                    new MesJornadaView.Expediente(data, grade.cargaHorariaSegundos(), grade.periodos())));
        }
        return lista;
    }

    public ResumoSaldosView saldos(UUID usuarioId, YearMonth referencia) {
        List<SaldoMensal> meses = consolidarAnoCompleto(usuarioId, referencia.getYear());
        SaldoMensal doMes = meses.get(referencia.getMonthValue() - 1);
        return new ResumoSaldosView(referencia, doMes.saldoMensalSegundos(),
                doMes.saldoAnualAcumuladoSegundos(), meses);
    }

    /** Devolve os 12 meses; meses sem registro herdam o acumulado do mês anterior. */
    private List<SaldoMensal> consolidarAnoCompleto(UUID usuarioId, int ano) {
        Map<Integer, SaldoMensal> porMes = consolidacao.ano(usuarioId, ano).stream()
                .collect(Collectors.toMap(SaldoMensal::mes, Function.identity()));
        List<SaldoMensal> meses = new ArrayList<>(12);
        int acumulado = 0;
        for (int mes = 1; mes <= 12; mes++) {
            SaldoMensal saldo = porMes.getOrDefault(mes, SaldoMensal.vazio(ano, mes, acumulado));
            acumulado = saldo.saldoAnualAcumuladoSegundos();
            meses.add(saldo);
        }
        return meses;
    }
}
