package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.ConsolidacaoBancoHoras;
import br.com.conferenciaponto.domain.port.CalendarioFeriados;
import br.com.conferenciaponto.domain.port.AusenciaRepository;
import br.com.conferenciaponto.domain.port.LancamentoBancoRepository;
import br.com.conferenciaponto.application.view.MesJornadaView;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.application.view.ResumoSaldosView;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final MotorCalculoJornadaService motor;
    private final AusenciaRepository ausencias;
    private final CalendarioFeriados feriados;
    private final LancamentoBancoRepository lancamentos;
    private final ConsolidacaoBancoHoras consolidacao;

    public ConsultarJornadaUseCase(RegistroJornadaRepository repository, MotorCalculoJornadaService motor,
                                   AusenciaRepository ausencias, CalendarioFeriados feriados,
                                   LancamentoBancoRepository lancamentos, ConsolidacaoBancoHoras consolidacao) {
        this.repository = repository;
        this.motor = motor;
        this.ausencias = ausencias;
        this.feriados = feriados;
        this.lancamentos = lancamentos;
        this.consolidacao = consolidacao;
    }

    public RegistroJornadaView dia(LocalDate data) {
        return repository.buscarPorData(data)
                .map(registro -> RegistroJornadaView.de(registro, motor))
                .orElseThrow(() -> new RecursoNaoEncontradoException("REGISTRO_NAO_ENCONTRADO",
                        "Não há registro de jornada em %s.".formatted(data)));
    }

    public MesJornadaView mes(YearMonth referencia) {
        List<RegistroJornadaView> dias = repository
                .listarPorPeriodo(referencia.atDay(1), referencia.atEndOfMonth())
                .stream()
                .map(registro -> RegistroJornadaView.de(registro, motor))
                .toList();
        SaldoMensal resumo = consolidarAnoCompleto(referencia.getYear()).get(referencia.getMonthValue() - 1);
        LocalDate inicio = referencia.atDay(1);
        LocalDate fim = referencia.atEndOfMonth();
        return new MesJornadaView(referencia, dias, resumo, ausencias.listarNoPeriodo(inicio, fim),
                feriados.listar(inicio, fim), lancamentos.listarNoPeriodo(inicio, fim));
    }

    public ResumoSaldosView saldos(YearMonth referencia) {
        List<SaldoMensal> meses = consolidarAnoCompleto(referencia.getYear());
        SaldoMensal doMes = meses.get(referencia.getMonthValue() - 1);
        return new ResumoSaldosView(referencia, doMes.saldoMensalSegundos(),
                doMes.saldoAnualAcumuladoSegundos(), meses);
    }

    /** Devolve os 12 meses; meses sem registro herdam o acumulado do mês anterior. */
    private List<SaldoMensal> consolidarAnoCompleto(int ano) {
        Map<Integer, SaldoMensal> porMes = consolidacao.ano(ano).stream()
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
