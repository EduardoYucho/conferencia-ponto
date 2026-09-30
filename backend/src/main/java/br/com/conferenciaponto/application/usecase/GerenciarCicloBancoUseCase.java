package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.ParametrosBancoHoras;
import br.com.conferenciaponto.application.TextoDuracao;
import br.com.conferenciaponto.application.evento.CicloAtualizadoEvento;
import br.com.conferenciaponto.application.view.CicloBancoView;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.model.StatusCiclo;
import br.com.conferenciaponto.domain.port.CicloBancoRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ciclo (semestral) do banco de horas: o saldo do painel é o do ciclo aberto. O botão "Fechar banco de
 * horas" congela o saldo exato do ciclo e recomeça a contagem do zero no dia seguinte ao último dia
 * incluído.
 */
@Service
public class GerenciarCicloBancoUseCase {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CicloBancoRepository ciclos;
    private final RegistroJornadaRepository registros;
    private final ParametrosBancoHoras parametros;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public GerenciarCicloBancoUseCase(CicloBancoRepository ciclos, RegistroJornadaRepository registros,
                                      ParametrosBancoHoras parametros, ApplicationEventPublisher eventos, Clock clock) {
        this.ciclos = ciclos;
        this.registros = registros;
        this.parametros = parametros;
        this.eventos = eventos;
        this.clock = clock;
    }

    /** Resultado do fechamento: o ciclo encerrado (com o saldo final) e o novo ciclo, zerado. */
    public record Fechamento(CicloBancoView fechado, CicloBancoView novo) {
    }

    /** Na subida: garante que existe um ciclo aberto (o primeiro começa em {@code inicioPrimeiroCiclo}). */
    @Transactional
    public CicloBanco garantirCicloAberto() {
        Optional<CicloBanco> aberto = ciclos.buscarAberto();
        if (aberto.isPresent()) {
            return aberto.get();
        }
        LocalDate inicio = ultimoFechado().map(c -> c.dataFim().plusDays(1)).orElse(parametros.inicioPrimeiroCiclo());
        CicloBanco novo = CicloBanco.abrir(inicio, parametros.duracaoMeses(), clock.instant());
        ciclos.salvar(novo);
        return novo;
    }

    @Transactional(readOnly = true)
    public CicloBancoView atual() {
        return visao(aberto());
    }

    @Transactional(readOnly = true)
    public List<CicloBancoView> listar() {
        return ciclos.listar().stream().map(this::visao).toList();
    }

    /**
     * Fecha o banco de horas.
     *
     * @param ultimoDia último dia incluído no saldo final; {@code null} = sugestão ({@link #sugerirUltimoDia})
     */
    @Transactional
    public Fechamento fechar(LocalDate ultimoDia, String observacao, String usuario) {
        CicloBanco aberto = aberto();
        LocalDate hoje = LocalDate.now(clock);
        if (ultimoDia == null && !aberto.dataInicio().isBefore(hoje)) {
            // protege contra clique duplo: o ciclo recém-aberto só fecha com a data informada
            throw new ConflitoException("CICLO_RECEM_ABERTO", "O ciclo atual começou em %s: ainda não há dias para fechar."
                    .formatted(DATA.format(aberto.dataInicio())));
        }
        LocalDate dia = ultimoDia != null ? ultimoDia : sugerirUltimoDia(aberto, hoje);
        if (dia.isBefore(aberto.dataInicio())) {
            throw new RegraNegocioException("CICLO_FECHAMENTO_ANTES_DO_INICIO",
                    "O ciclo atual começou em %s: o fechamento não pode ser antes disso."
                            .formatted(DATA.format(aberto.dataInicio())));
        }
        int saldo = apurar(aberto.dataInicio(), dia).saldo();
        CicloBanco fechado = aberto.fechar(dia, saldo, usuario, observacao, hoje, clock.instant());
        ciclos.salvar(fechado);
        CicloBanco novo = CicloBanco.abrir(dia.plusDays(1), parametros.duracaoMeses(), clock.instant());
        ciclos.salvar(novo);

        CicloBancoView novoView = visao(novo);
        eventos.publishEvent(new CicloAtualizadoEvento(novoView, "Banco de horas fechado em %s com saldo %s"
                .formatted(DATA.format(dia), TextoDuracao.saldo(saldo))));
        return new Fechamento(visao(fechado), novoView);
    }

    /** Desfaz o último fechamento (clicou por engano): o ciclo anterior volta a ser o aberto. */
    @Transactional
    public CicloBancoView desfazerUltimoFechamento() {
        CicloBanco aberto = aberto();
        CicloBanco anterior = ultimoFechado()
                .filter(c -> c.dataFim().plusDays(1).equals(aberto.dataInicio()))
                .orElseThrow(() -> new ConflitoException("SEM_FECHAMENTO_PARA_DESFAZER",
                        "Não há fechamento imediatamente anterior ao ciclo atual para desfazer."));
        ciclos.excluir(aberto.id());
        CicloBanco reaberto = anterior.reaberto();
        ciclos.salvar(reaberto);
        CicloBancoView view = visao(reaberto);
        eventos.publishEvent(new CicloAtualizadoEvento(view, "Fechamento de %s desfeito"
                .formatted(DATA.format(anterior.dataFim()))));
        return view;
    }

    /** Corrige o início (e a previsão de término) do ciclo aberto. */
    @Transactional
    public CicloBancoView corrigirPeriodo(LocalDate inicio, LocalDate fimPrevisto) {
        CicloBanco aberto = aberto();
        Optional<CicloBanco> anterior = ultimoFechado();
        if (anterior.isPresent() && !inicio.isAfter(anterior.get().dataFim())) {
            throw new RegraNegocioException("CICLO_SOBREPOE_ANTERIOR",
                    "O ciclo anterior foi fechado em %s: o atual deve começar a partir de %s."
                            .formatted(DATA.format(anterior.get().dataFim()),
                                    DATA.format(anterior.get().dataFim().plusDays(1))));
        }
        LocalDate fim = fimPrevisto != null ? fimPrevisto : inicio.plusMonths(parametros.duracaoMeses()).minusDays(1);
        CicloBanco corrigido = aberto.comPeriodo(inicio, fim);
        ciclos.salvar(corrigido);
        CicloBancoView view = visao(corrigido);
        eventos.publishEvent(new CicloAtualizadoEvento(view, "Período do ciclo corrigido: %s a %s"
                .formatted(DATA.format(inicio), DATA.format(fim))));
        return view;
    }

    /**
     * Último dia sugerido para o fechamento: a previsão, se já passou (os dias seguintes vão para o novo
     * ciclo, como no RH); senão, ontem — o dia de hoje ainda está em andamento.
     */
    public LocalDate sugerirUltimoDia(CicloBanco ciclo, LocalDate hoje) {
        if (hoje.isAfter(ciclo.dataFimPrevista())) {
            return ciclo.dataFimPrevista();
        }
        LocalDate ontem = hoje.minusDays(1);
        return ontem.isBefore(ciclo.dataInicio()) ? hoje : ontem;
    }

    private CicloBanco aberto() {
        return ciclos.buscarAberto().orElseThrow(() -> new RecursoNaoEncontradoException("CICLO_NAO_ENCONTRADO",
                "Não há ciclo do banco de horas aberto."));
    }

    private Optional<CicloBanco> ultimoFechado() {
        return ciclos.listar().stream()
                .filter(c -> c.status() == StatusCiclo.FECHADO)
                .max(Comparator.comparing(CicloBanco::dataFim));
    }

    private record Apuracao(int saldo, int diasRegistrados, int diasEmAberto, List<SaldoMensal> meses) {
    }

    private Apuracao apurar(LocalDate inicio, LocalDate fim) {
        List<SaldoMensal> meses = registros.consolidarPeriodo(inicio, fim);
        int saldo = meses.stream().mapToInt(SaldoMensal::saldoMensalSegundos).sum();
        int registrados = meses.stream().mapToInt(SaldoMensal::diasRegistrados).sum();
        int emAberto = meses.stream().mapToInt(SaldoMensal::diasEmAberto).sum();
        return new Apuracao(saldo, registrados, emAberto, meses);
    }

    private CicloBancoView visao(CicloBanco ciclo) {
        LocalDate hoje = LocalDate.now(clock);
        Apuracao a = apurar(ciclo.dataInicio(), ciclo.ultimoDiaDoSaldo());
        int saldo = ciclo.isAberto() ? a.saldo() : ciclo.saldoFinalSegundos();
        LocalDate ultimoMes = ciclo.isAberto()
                ? (hoje.isAfter(ciclo.dataFimPrevista()) ? hoje : ciclo.dataFimPrevista())
                : ciclo.dataFim();
        return new CicloBancoView(ciclo, saldo, a.diasRegistrados(), a.diasEmAberto(),
                ciclo.isAberto() ? ciclo.diasAtePrevisao(hoje) : null,
                ciclo.isAberto() ? sugerirUltimoDia(ciclo, hoje) : null,
                mesesCompletos(ciclo.dataInicio(), ultimoMes, a.meses()));
    }

    /** Todos os meses do ciclo (sem registro = zero), com o acumulado desde o início do ciclo. */
    private static List<SaldoMensal> mesesCompletos(LocalDate inicio, LocalDate fim, List<SaldoMensal> comDados) {
        Map<YearMonth, SaldoMensal> porMes = comDados.stream()
                .collect(Collectors.toMap(s -> YearMonth.of(s.ano(), s.mes()), Function.identity()));
        List<SaldoMensal> meses = new ArrayList<>();
        int acumulado = 0;
        for (YearMonth m = YearMonth.from(inicio); !m.isAfter(YearMonth.from(fim)); m = m.plusMonths(1)) {
            SaldoMensal s = porMes.get(m);
            acumulado += s == null ? 0 : s.saldoMensalSegundos();
            meses.add(s == null ? SaldoMensal.vazio(m.getYear(), m.getMonthValue(), acumulado)
                    : new SaldoMensal(s.ano(), s.mes(), s.diasRegistrados(), s.diasEmAberto(), s.segundosTrabalhados(),
                    s.segundosPrevistos(), s.saldoMensalSegundos(), acumulado));
        }
        return meses;
    }
}
