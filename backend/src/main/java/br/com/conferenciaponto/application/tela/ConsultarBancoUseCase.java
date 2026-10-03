package br.com.conferenciaponto.application.tela;

import br.com.conferenciaponto.application.tela.BancoView.Anterior;
import br.com.conferenciaponto.application.tela.BancoView.Lancamento;
import br.com.conferenciaponto.application.tela.BancoView.Mes;
import br.com.conferenciaponto.application.usecase.GerenciarCicloBancoUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarLancamentosBancoUseCase;
import br.com.conferenciaponto.application.view.CicloBancoView;
import br.com.conferenciaponto.domain.exception.DominioException;
import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.StatusCiclo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** A tela "Banco de horas", com as frases e os totais já prontos (o navegador só mostra). */
@Service
public class ConsultarBancoUseCase {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final GerenciarCicloBancoUseCase ciclos;
    private final GerenciarLancamentosBancoUseCase lancamentos;
    private final Clock clock;

    public ConsultarBancoUseCase(GerenciarCicloBancoUseCase ciclos, GerenciarLancamentosBancoUseCase lancamentos,
                                 Clock clock) {
        this.ciclos = ciclos;
        this.lancamentos = lancamentos;
        this.clock = clock;
    }

    /**
     * @param podeEditar  quem está olhando é o dono dos dados e registra ponto
     * @param outraPessoa primeiro nome de quem é o banco, quando não é de quem está olhando
     */
    @Transactional
    public BancoView agora(UUID usuarioId, boolean podeEditar, String outraPessoa) {
        LocalDate hoje = LocalDate.now(clock);
        CicloBancoView aberto;
        try {
            aberto = ciclos.atual(usuarioId);
        } catch (DominioException semCiclo) {
            aberto = null;
        }
        List<Anterior> anteriores = ciclos.listar(usuarioId).stream()
                .filter(v -> v.ciclo().status() == StatusCiclo.FECHADO)
                .sorted(Comparator.comparing((CicloBancoView v) -> v.ciclo().dataInicio()).reversed())
                .map(v -> anterior(v.ciclo(), v.saldoSegundos()))
                .toList();
        if (aberto == null) {
            return new BancoView(null, null, List.of(), List.of(), anteriores, false, false, explicacao(outraPessoa));
        }
        CicloBanco ciclo = aberto.ciclo();
        boolean variosAnos = aberto.meses().stream().map(m -> m.ano()).distinct().count() > 1;
        YearMonth mesDeHoje = YearMonth.from(hoje);
        List<Mes> meses = aberto.meses().stream()
                .map(m -> new Mes(m.ano(), m.mes(),
                        capitalizar(variosAnos ? Horas.mesAno(YearMonth.of(m.ano(), m.mes())) : Horas.mes(m.mes())),
                        m.saldoMensalSegundos(), Horas.saldoComSentido(m.saldoMensalSegundos()),
                        m.saldoAnualAcumuladoSegundos(), YearMonth.of(m.ano(), m.mes()).equals(mesDeHoje),
                        m.diasEmAberto()))
                .toList();
        LocalDate fim = ciclo.dataFimPrevista().isAfter(hoje) ? ciclo.dataFimPrevista().plusYears(1) : hoje.plusYears(1);
        List<Lancamento> lancados = lancamentos.listar(usuarioId, ciclo.dataInicio(), fim).stream()
                .sorted(Comparator.comparing(LancamentoBanco::data).thenComparing(LancamentoBanco::criadoEm).reversed())
                .map(l -> new Lancamento(l, Horas.dia(l.data()),
                        (l.isDebito() ? "Usou %s do banco" : "Somou %s ao banco").formatted(Horas.duracao(l.segundos()))))
                .toList();
        boolean podeDesfazer = !anteriores.isEmpty()
                && anteriores.get(0).ciclo().dataFim().plusDays(1).equals(ciclo.dataInicio());
        return new BancoView(TextosDoBanco.resumo(aberto, outraPessoa), aberto, meses, lancados, anteriores, podeEditar,
                podeEditar && podeDesfazer, explicacao(outraPessoa));
    }

    private static Anterior anterior(CicloBanco ciclo, int saldo) {
        return new Anterior(ciclo, DATA.format(ciclo.dataInicio()) + " a " + DATA.format(ciclo.dataFim()),
                "fechou com " + (saldo == 0 ? "o saldo zerado" : Horas.saldoComSentido(saldo)));
    }

    private static String explicacao(String outraPessoa) {
        return "O banco de horas soma o saldo de todos os dias fechados desde o último fechamento, mais as horas "
                + "usadas ou somadas à mão. Dias com batida faltando ficam de fora até serem corrigidos. Quando o RH "
                + "fecha o banco, o saldo é guardado e a contagem recomeça do zero no dia seguinte.";
    }

    private static String capitalizar(String texto) {
        return Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
    }
}
