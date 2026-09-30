package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.ParametrosBancoHoras;
import br.com.conferenciaponto.application.evento.CicloAtualizadoEvento;
import br.com.conferenciaponto.application.view.CicloBancoView;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.StatusCiclo;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GerenciarCicloBancoUseCaseTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate INICIO = LocalDate.of(2026, 5, 25);

    private final RegistroJornadaRepositoryEmMemoria registros = new RegistroJornadaRepositoryEmMemoria();
    private final CicloBancoRepositoryEmMemoria ciclos = new CicloBancoRepositoryEmMemoria();
    private final NotificacaoRepositoryEmMemoria notificacoesRepo = new NotificacaoRepositoryEmMemoria();
    private final MotorCalculoJornadaService motor = new MotorCalculoJornadaService();
    private final List<Object> eventos = new ArrayList<>();
    private LocalDate hoje = LocalDate.of(2026, 9, 29);

    private Clock clock() {
        return Clock.fixed(ZonedDateTime.of(hoje, LocalTime.of(10, 0), SP).toInstant(), SP);
    }

    private GerenciarCicloBancoUseCase useCase() {
        return new GerenciarCicloBancoUseCase(ciclos, registros, new ParametrosBancoHoras(INICIO, 6), eventos::add, clock());
    }

    private VerificarPrazoCicloUseCase alertas() {
        return new VerificarPrazoCicloUseCase(useCase(), new NotificacoesUseCase(notificacoesRepo, eventos::add, clock()));
    }

    /** Dia útil fechado com o saldo desejado (entrada às 08:00 e saída ajustada). */
    private void dia(LocalDate data, int saldoSegundos) {
        RegistroJornada r = RegistroJornada.novo(data, TipoDia.UTIL);
        LocalTime saida = LocalTime.of(17, 48).plusSeconds(saldoSegundos);
        for (LocalTime t : List.of(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(13, 0), saida)) {
            r.incluirBatida(t, motor);
        }
        assertThat(r.getSaldoDiarioSegundos()).isEqualTo(saldoSegundos);
        registros.salvar(r);
    }

    @BeforeEach
    void cicloInicial() {
        CicloBanco aberto = useCase().garantirCicloAberto();
        assertThat(aberto.dataInicio()).isEqualTo(INICIO);
        assertThat(aberto.dataFimPrevista()).isEqualTo(LocalDate.of(2026, 11, 24));
        dia(LocalDate.of(2026, 5, 22), 7_200);  // ciclo anterior: não entra
        dia(LocalDate.of(2026, 5, 25), 600);
        dia(LocalDate.of(2026, 6, 10), -1_000);
        dia(LocalDate.of(2026, 9, 28), 1_800);
        dia(LocalDate.of(2026, 9, 29), 3_600);
    }

    @Test
    @DisplayName("Saldo do ciclo aberto soma só os dias desde o início do ciclo, mês a mês")
    void saldoDoCicloAberto() {
        CicloBancoView v = useCase().atual();

        assertThat(v.saldoSegundos()).isEqualTo(600 - 1_000 + 1_800 + 3_600);
        assertThat(v.diasAtePrevisao()).isEqualTo(56);
        assertThat(v.sugestaoFechamento()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(v.meses()).hasSize(7); // mai..nov
        assertThat(v.meses().get(1).saldoAnualAcumuladoSegundos()).isEqualTo(-400); // acumulado até junho
        assertThat(v.meses().get(6).saldoAnualAcumuladoSegundos()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("Fechar: congela o saldo exato até o último dia e recomeça do zero no dia seguinte")
    void fecharRecomecaContagem() {
        GerenciarCicloBancoUseCase.Fechamento f = useCase().fechar(null, "Zerado pelo RH", "eduardo");

        assertThat(f.fechado().ciclo().status()).isEqualTo(StatusCiclo.FECHADO);
        assertThat(f.fechado().ciclo().dataFim()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(f.fechado().saldoSegundos()).isEqualTo(600 - 1_000 + 1_800);
        assertThat(f.fechado().ciclo().fechadoPor()).isEqualTo("eduardo");
        assertThat(f.novo().ciclo().dataInicio()).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(f.novo().ciclo().dataFimPrevista()).isEqualTo(LocalDate.of(2027, 3, 28));
        assertThat(f.novo().saldoSegundos()).isEqualTo(3_600);
        assertThat(eventos).singleElement().isInstanceOf(CicloAtualizadoEvento.class);

        // o saldo do fechado não muda mais, mesmo que um dia antigo seja alterado depois
        dia(LocalDate.of(2026, 6, 11), 999);
        assertThat(useCase().listar()).extracting(CicloBancoView::saldoSegundos).containsExactly(3_600, 1_400);
    }

    @Test
    @DisplayName("Fechando depois da previsão, a sugestão é a própria previsão (os dias seguintes vão para o novo ciclo)")
    void fecharDepoisDaPrevisao() {
        hoje = LocalDate.of(2026, 11, 30);
        dia(LocalDate.of(2026, 11, 25), 900);

        GerenciarCicloBancoUseCase.Fechamento f = useCase().fechar(null, null, "eduardo");

        assertThat(f.fechado().ciclo().dataFim()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(f.fechado().saldoSegundos()).isEqualTo(600 - 1_000 + 1_800 + 3_600);
        assertThat(f.novo().ciclo().dataInicio()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(f.novo().saldoSegundos()).isEqualTo(900);
    }

    @Test
    @DisplayName("Fechamento antes do início ou no futuro é recusado; clique duplo não fecha o ciclo novo")
    void validacoesDoFechamento() {
        assertThatThrownBy(() -> useCase().fechar(LocalDate.of(2026, 5, 24), null, "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("começou em 25/05/2026");
        assertThatThrownBy(() -> useCase().fechar(LocalDate.of(2026, 9, 30), null, "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("depois de hoje");

        useCase().fechar(LocalDate.of(2026, 9, 28), null, "eduardo");
        assertThatThrownBy(() -> useCase().fechar(LocalDate.of(2026, 9, 28), null, "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("começou em 29/09/2026");
        assertThatThrownBy(() -> useCase().fechar(null, null, "eduardo"))
                .isInstanceOf(ConflitoException.class).hasMessageContaining("ainda não há dias para fechar");
    }

    @Test
    @DisplayName("Desfazer o fechamento devolve o ciclo anterior aberto, com a previsão original")
    void desfazer() {
        useCase().fechar(null, null, "eduardo");

        CicloBancoView reaberto = useCase().desfazerUltimoFechamento();

        assertThat(reaberto.ciclo().isAberto()).isTrue();
        assertThat(reaberto.ciclo().dataInicio()).isEqualTo(INICIO);
        assertThat(reaberto.ciclo().dataFimPrevista()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(reaberto.saldoSegundos()).isEqualTo(5_000);
        assertThat(ciclos.listar()).hasSize(1);
        assertThatThrownBy(() -> useCase().desfazerUltimoFechamento()).isInstanceOf(ConflitoException.class);
    }

    @Test
    @DisplayName("Corrigir o início: recalcula a previsão e não pode invadir o ciclo anterior")
    void corrigirPeriodo() {
        CicloBancoView v = useCase().corrigirPeriodo(LocalDate.of(2026, 5, 22), null);
        assertThat(v.ciclo().dataFimPrevista()).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(v.saldoSegundos()).isEqualTo(7_200 + 5_000);

        useCase().fechar(LocalDate.of(2026, 6, 30), null, "eduardo");
        assertThatThrownBy(() -> useCase().corrigirPeriodo(LocalDate.of(2026, 6, 30), null))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("a partir de 01/07/2026");
    }

    @Test
    @DisplayName("Avisos: 30 dias, 15 dias e previsão vencida — cada um uma única vez")
    void avisosDePrazo() {
        assertThat(alertas().executar(LocalDate.of(2026, 10, 24))).isEmpty(); // 31 dias

        assertThat(alertas().executar(LocalDate.of(2026, 10, 25))).hasValueSatisfying(n -> {
            assertThat(n.tipo()).isEqualTo(TipoNotificacao.CICLO_30_DIAS);
            assertThat(n.titulo()).isEqualTo("Faltam 30 dias para o fechamento do banco");
            assertThat(n.mensagem()).contains("24/11/2026").contains("+01:23:20");
        });
        assertThat(alertas().executar(LocalDate.of(2026, 10, 26))).isEmpty();          // já avisado
        assertThat(alertas().executar(LocalDate.of(2026, 11, 12))).hasValueSatisfying(n ->
                assertThat(n.tipo()).isEqualTo(TipoNotificacao.CICLO_15_DIAS));          // perdeu o dia 09: avisa no 12
        assertThat(alertas().executar(LocalDate.of(2026, 11, 25))).hasValueSatisfying(n ->
                assertThat(n.tipo()).isEqualTo(TipoNotificacao.CICLO_VENCIDO));
        assertThat(notificacoesRepo.contarNaoLidas()).isEqualTo(3);

        hoje = LocalDate.of(2026, 11, 26);
        useCase().fechar(null, null, "eduardo");
        assertThat(alertas().executar(LocalDate.of(2026, 11, 26))).isEmpty();           // ciclo novo, longe do prazo
        assertThat(notificacoesRepo.contarNaoLidas()).isZero();                         // avisos do ciclo fechado arquivados
    }
}
