package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.ConferenciaAlteradaEvento;
import br.com.conferenciaponto.application.view.ConferenciaDoMesView;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.DominioException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.ConferenciaMes;
import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.Intervalo;
import br.com.conferenciaponto.domain.model.TipoAusencia;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import br.com.conferenciaponto.domain.service.LeiDaJornada;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static br.com.conferenciaponto.application.usecase.Fixtures.USUARIO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Conferência do mês: só mês que terminou, uma vez, com os números da hora; trava as mudanças à mão até reabrir. */
class ConferenciaDoMesTest {

    private static final YearMonth SETEMBRO = YearMonth.of(2026, 9);
    private static final LocalDate DIA_15 = LocalDate.of(2026, 9, 15);
    private static final LocalDate SABADO_19 = LocalDate.of(2026, 9, 19);

    private final CenarioDaCarga c = new CenarioDaCarga();

    @BeforeEach
    void setembro() {
        c.dia(DIA_15, "08:00", "12:00", "13:00", "17:48");
        c.dia(LocalDate.of(2026, 9, 16), "08:00", "12:00", "13:00", "18:00");
    }

    private void travado(ThrowingCallable acao) {
        assertThatThrownBy(acao).isInstanceOf(ConflitoException.class)
                .satisfies(e -> assertThat(((DominioException) e).getCodigo()).isEqualTo("MES_CONFERIDO"))
                .hasMessageContaining("Setembro de 2026 foi conferido por carla");
    }

    @Test
    @DisplayName("Conferir guarda quem, quando e os números do mês; avisa a pessoa no sino; não confere duas vezes")
    void conferir() {
        ConferenciaMes conferido = c.conferir.conferir(USUARIO, SETEMBRO, "Tudo certo", "carla", true);

        assertThat(conferido.conferidoPor()).isEqualTo("carla");
        assertThat(conferido.saldoMesSegundos()).isEqualTo(720);
        assertThat(conferido.diasRegistrados()).isEqualTo(2);
        assertThat(c.notificacoesRepo.listarRecentes(USUARIO, 10)).singleElement().satisfies(n -> {
            assertThat(n.tipo()).isEqualTo(TipoNotificacao.MES_CONFERIDO);
            assertThat(n.titulo()).isEqualTo("Setembro de 2026 conferido por carla");
            assertThat(n.link()).isEqualTo("/banco-de-horas?ano=2026&mes=9");
        });
        assertThat(c.eventos).hasAtLeastOneElementOfType(ConferenciaAlteradaEvento.class);

        ConferenciaDoMesView situacao = c.conferir.situacao(USUARIO, SETEMBRO);
        assertThat(situacao.vigente()).isNotNull();
        assertThat(situacao.mudou()).isFalse();
        assertThat(situacao.terminou()).isTrue();

        assertThatThrownBy(() -> c.conferir.conferir(USUARIO, SETEMBRO, null, "carla", true))
                .isInstanceOf(ConflitoException.class).hasMessageContaining("já foi conferido");
        assertThatThrownBy(() -> c.conferir.conferir(USUARIO, YearMonth.of(2026, 10), null, "carla", true))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("ainda não terminou")
                .hasMessageContaining("01/11/2026");
    }

    @Test
    @DisplayName("Mês conferido: ajuste, lançamento, batida, exclusão, lançamento no banco, folga e horário ficam travados")
    void trava() {
        c.conferir.conferir(USUARIO, SETEMBRO, null, "carla", true);
        var t = c.trava;
        var ajustar = new AjustarBatidasUseCase(c.registros, c.arquivos, c.ajustes, c.regras, t, c.eventos::add, c.clock);
        var manual = new LancarRegistroManualUseCase(c.registros, c.ajustes, c.arquivos, c.regras, t, c.eventos::add, c.clock);
        var batida = new RegistrarBatidaUseCase(c.registros, c.regras, t, c.eventos::add, c.clock);
        var excluir = new ExcluirRegistroUseCase(c.registros, c.arquivos, c.ajustes, t, c.eventos::add, c.clock);
        var banco = new GerenciarLancamentosBancoUseCase(c.lancamentos, c.ciclos, t, c.eventos::add, c.clock);
        var ausencias = new GerenciarAusenciasUseCase(c.ausencias, c.registros, c.regras, t, c.eventos::add, c.clock);
        var horarios = new GerenciarHorariosUseCase(c.horarios, c.ciclos, c.regras,
                new RecalcularJornadasUseCase(c.registros, c.regras), t, LeiDaJornada.vigente(), c.eventos::add, c.clock);

        travado(() -> ajustar.executar(USUARIO, DIA_15, List.of(LocalTime.of(8, 0), LocalTime.of(17, 0)),
                "Corrigido pelo RH", "eduardo"));
        travado(() -> manual.executar(USUARIO, SABADO_19, List.of(new Intervalo(LocalTime.of(8, 0), LocalTime.of(12, 0))),
                "Compensação", "eduardo"));
        travado(() -> batida.executar(USUARIO, LocalDate.of(2026, 9, 17), LocalTime.of(8, 0)));
        travado(() -> excluir.executar(USUARIO, DIA_15, "eduardo"));
        travado(() -> banco.lancar(USUARIO, DIA_15, -3_600, "Folga", "eduardo"));
        travado(() -> ausencias.cadastrar(USUARIO, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 2), TipoAusencia.FERIAS,
                null, "eduardo"));
        assertThatThrownBy(() -> horarios.salvar(USUARIO, LocalDate.of(2026, 9, 20), 5, CenarioDaCarga.oitoHoras(), null,
                "eduardo")).isInstanceOf(ConflitoException.class).hasMessageContaining("a partir de 01/10/2026");

        // outubro continua aberto
        manual.executar(USUARIO, LocalDate.of(2026, 10, 17), List.of(new Intervalo(LocalTime.of(8, 0), LocalTime.of(12, 0))),
                "Compensação da carga semanal", "eduardo");
        horarios.salvar(USUARIO, LocalDate.of(2026, 10, 1), 5, CenarioDaCarga.oitoHoras(), 41 * 3600, "eduardo");
        assertThat(c.registros.buscarPorData(USUARIO, DIA_15)).isPresent();
    }

    @Test
    @DisplayName("Pendências do mês (dia sem saída, dia útil sem registro, diferença do RH): aparecem antes e só conferem com confirmação")
    void pendenciasDoMes() {
        for (LocalDate d = SETEMBRO.atDay(1); !d.isAfter(SETEMBRO.atEndOfMonth()); d = d.plusDays(1)) {
            boolean util = d.getDayOfWeek().getValue() <= 5;
            if (util && d.getDayOfMonth() != 8 && d.getDayOfMonth() != 9 && !d.equals(DIA_15) && d.getDayOfMonth() != 16
                    && d.getDayOfMonth() < 28) {
                c.dia(d, "08:00", "12:00", "13:00", "17:48");
            }
        }
        c.dia(LocalDate.of(2026, 9, 8), "08:00", "12:00", "13:00"); // faltou a saída; 09/09 ficou sem nada
        c.ausencias.salvar(Ausencia.nova(USUARIO, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 30), TipoAusencia.FERIAS,
                null, "eduardo", Instant.EPOCH)); // férias não são pendência
        c.divergencias.salvar(Divergencia.nova(USUARIO, DIA_15, UUID.randomUUID(), new Divergencia.Achado(
                TipoDivergencia.HORARIO_DIFERENTE, "Saída diferente", true, null, List.of(), List.of(), null, TipoDia.UTIL,
                null, null), Instant.EPOCH));

        assertThat(c.conferir.situacao(USUARIO, SETEMBRO).pendencias().itens()).containsExactly(
                "1 dia com entrada sem saída (08/09): fora do saldo",
                "1 dia útil sem nenhum registro (09/09): fora do saldo",
                "1 diferença com o relatório do RH sem decisão (15/09)");
        assertThatThrownBy(() -> c.conferir.conferir(USUARIO, SETEMBRO, null, "carla", false))
                .isInstanceOf(RegraNegocioException.class)
                .satisfies(e -> assertThat(((DominioException) e).getCodigo()).isEqualTo("MES_COM_PENDENCIAS"))
                .hasMessage("Setembro de 2026 tem pendências: 1 dia com entrada sem saída (08/09): fora do saldo; "
                        + "1 dia útil sem nenhum registro (09/09): fora do saldo e 1 diferença com o relatório do RH sem "
                        + "decisão (15/09). Resolva antes ou confirme \"conferir mesmo assim\".");
        assertThat(c.conferencias.historico(USUARIO, SETEMBRO)).isEmpty();

        c.conferir.conferir(USUARIO, SETEMBRO, "RH confirmou a saída do dia 8", "carla", true);
        assertThat(c.conferir.situacao(USUARIO, SETEMBRO).pendencias().vazia()).as("conferido: não pede mais").isTrue();
        // o mês corrente não tem o que conferir ainda; hoje (21/10) não conta como dia sem registro
        assertThat(c.pendencias.de(USUARIO, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 31)).vazia()).isTrue();
    }

    @Test
    @DisplayName("Fechar o banco com pendências no período: mostra antes e só fecha com confirmação")
    void pendenciasDoFechamento() {
        LocalDate ultimo = LocalDate.of(2026, 9, 30);
        c.ciclo.garantirCicloAberto(USUARIO);
        for (LocalDate d = LocalDate.of(2026, 5, 25); !d.isAfter(ultimo); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5 && !d.equals(DIA_15) && !d.equals(LocalDate.of(2026, 9, 16))
                    && !d.equals(LocalDate.of(2026, 6, 4))) {
                c.dia(d, "08:00", "12:00", "13:00", "17:48");
            }
        }
        assertThat(c.ciclo.pendenciasDoFechamento(USUARIO, ultimo).itens())
                .containsExactly("1 dia útil sem nenhum registro (04/06): fora do saldo");
        assertThatThrownBy(() -> c.ciclo.fechar(USUARIO, ultimo, null, "carla", false))
                .isInstanceOf(RegraNegocioException.class)
                .satisfies(e -> assertThat(((DominioException) e).getCodigo()).isEqualTo("CICLO_COM_PENDENCIAS"))
                .hasMessageContaining("O período de 25/05/2026 a 30/09/2026 tem pendências: 1 dia útil sem nenhum registro "
                        + "(04/06): fora do saldo.")
                .hasMessageContaining("fechar mesmo assim");
        assertThat(c.ciclos.buscarAberto(USUARIO)).get().satisfies(ciclo ->
                assertThat(ciclo.dataInicio()).isEqualTo(LocalDate.of(2026, 5, 25)));

        // até antes do dia que falta, nada a avisar
        assertThat(c.ciclo.pendenciasDoFechamento(USUARIO, LocalDate.of(2026, 6, 3)).vazia()).isTrue();
        // quem começou a usar o sistema no meio do ciclo: os dias de antes não são pendência
        var outra = java.util.UUID.randomUUID();
        c.horarios.salvar(c.regras.padraoParaGravar(outra, "teste", Instant.EPOCH));
        LocalDate comecou = LocalDate.of(2026, 9, 29);
        var r = br.com.conferenciaponto.domain.model.RegistroJornada.novo(outra, comecou, c.regras.classificar(outra, comecou));
        for (String b : List.of("08:00", "12:00", "13:00", "17:48")) {
            r.incluirBatida(LocalTime.parse(b), c.regras.motor(outra, comecou));
        }
        c.registros.salvar(r);
        assertThat(c.pendencias.de(outra, LocalDate.of(2026, 5, 25), ultimo).semRegistro())
                .containsExactly(LocalDate.of(2026, 9, 30));
        assertThat(c.pendencias.de(java.util.UUID.randomUUID(), LocalDate.of(2026, 5, 25), ultimo).vazia())
                .as("ninguém sem registro nenhum tem pendência").isTrue();
        assertThat(c.ciclo.fechar(USUARIO, ultimo, "Zerado pelo RH", "carla", true).fechado().ciclo().dataFim())
                .isEqualTo(ultimo);
    }

    @Test
    @DisplayName("Reabrir exige o motivo, fica no histórico e destrava; números que mudaram depois aparecem")
    void reabrir() {
        c.conferir.conferir(USUARIO, SETEMBRO, null, "carla", true);
        c.dia(LocalDate.of(2026, 9, 17), "08:00", "12:00", "13:00", "17:48"); // um PDF que chegou depois

        assertThat(c.conferir.situacao(USUARIO, SETEMBRO).mudou()).isTrue();
        assertThatThrownBy(() -> c.conferir.reabrir(USUARIO, SETEMBRO, " ", "carla"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("por que");

        ConferenciaMes reaberta = c.conferir.reabrir(USUARIO, SETEMBRO, "Faltou o atestado do dia 14", "carla");
        assertThat(reaberta.vale()).isFalse();
        assertThat(reaberta.reabertoPor()).isEqualTo("carla");

        ConferenciaDoMesView situacao = c.conferir.situacao(USUARIO, SETEMBRO);
        assertThat(situacao.vigente()).isNull();
        assertThat(situacao.historico()).hasSize(1);
        c.trava.exigirAberto(USUARIO, DIA_15);
        assertThatThrownBy(() -> c.conferir.reabrir(USUARIO, SETEMBRO, "De novo, sem estar conferido", "carla"))
                .isInstanceOf(RecursoNaoEncontradoException.class);

        ConferenciaMes nova = c.conferir.conferir(USUARIO, SETEMBRO, null, "carla", true);
        assertThat(nova.diasRegistrados()).isEqualTo(3);
        assertThat(c.conferir.situacao(USUARIO, SETEMBRO).historico()).hasSize(2);
        assertThat(c.notificacoesRepo.listarRecentes(USUARIO, 10)).extracting(n -> n.titulo())
                .contains("Setembro de 2026 reaberto por carla");
    }
}
