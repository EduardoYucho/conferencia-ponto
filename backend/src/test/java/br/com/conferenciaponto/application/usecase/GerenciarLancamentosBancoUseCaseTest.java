package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.LancamentoBancoAlteradoEvento;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GerenciarLancamentosBancoUseCaseTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-01T13:00:00Z"), SP);
    private final LancamentoBancoRepositoryEmMemoria repositorio = new LancamentoBancoRepositoryEmMemoria();
    private final CicloBancoRepositoryEmMemoria ciclos = new CicloBancoRepositoryEmMemoria();
    private final List<Object> eventos = new ArrayList<>();
    private final GerenciarLancamentosBancoUseCase useCase =
            new GerenciarLancamentosBancoUseCase(repositorio, ciclos, eventos::add, clock);

    @BeforeEach
    void cicloAberto() {
        ciclos.salvar(CicloBanco.abrir(LocalDate.of(2026, 5, 25), 6, clock.instant()));
    }

    @Test
    @DisplayName("Abater horas: grava negativo, com motivo e usuário, e avisa a tela")
    void abaterHoras() {
        LancamentoBanco l = useCase.lancar(LocalDate.of(2026, 9, 26), -(4 * 3600 + 30 * 60), "  Compensação de horas ", "eduardo");

        assertThat(l.segundos()).isEqualTo(-16_200);
        assertThat(l.isDebito()).isTrue();
        assertThat(l.descricao()).isEqualTo("Compensação de horas");
        assertThat(l.criadoPor()).isEqualTo("eduardo");
        assertThat(useCase.listar(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).containsExactly(l);
        assertThat(eventos).singleElement().isInstanceOfSatisfying(LancamentoBancoAlteradoEvento.class, e -> {
            assertThat(e.data()).isEqualTo(LocalDate.of(2026, 9, 26));
            assertThat(e.descricao()).contains("Abatido 04:30:00", "26/09/2026", "Compensação de horas");
        });
    }

    @Test
    @DisplayName("Sem motivo, zerado, acima de 300 h, antes do ciclo aberto ou a mais de 1 ano: recusado")
    void validacoes() {
        LocalDate dia = LocalDate.of(2026, 9, 26);
        assertThatThrownBy(() -> useCase.lancar(dia, -3600, "  ", "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("motivo");
        assertThatThrownBy(() -> useCase.lancar(dia, 0, "x", "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("quantidade de horas");
        assertThatThrownBy(() -> useCase.lancar(dia, -(300 * 3600 + 1), "x", "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("300 horas");
        assertThatThrownBy(() -> useCase.lancar(LocalDate.of(2026, 5, 24), -3600, "x", "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("25/05/2026");
        assertThatThrownBy(() -> useCase.lancar(LocalDate.of(2027, 10, 2), -3600, "x", "eduardo"))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("um ano");
        assertThat(repositorio.listarNoPeriodo(LocalDate.MIN, LocalDate.MAX)).isEmpty();
        assertThat(eventos).isEmpty();
    }

    @Test
    @DisplayName("Remover: some do saldo; inexistente = 404")
    void remover() {
        LancamentoBanco l = useCase.lancar(LocalDate.of(2026, 10, 2), 3600, "Correção do RH", "eduardo");

        useCase.excluir(l.id());

        assertThat(repositorio.buscarPorId(l.id())).isEmpty();
        assertThat(eventos).hasSize(2);
        assertThatThrownBy(() -> useCase.excluir(UUID.randomUUID())).isInstanceOf(RecursoNaoEncontradoException.class);
    }
}
