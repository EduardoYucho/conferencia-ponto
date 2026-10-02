package br.com.conferenciaponto.infrastructure.google;

import br.com.conferenciaponto.application.evento.CalendarioAlteradoEvento;
import br.com.conferenciaponto.application.evento.CicloAtualizadoEvento;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.LancamentoBancoAlteradoEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.application.usecase.GerenciarPlanilhaUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarPlanilhaUseCase.Resultado;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SincronizadorPlanilhasTest {

    private static final UUID EDUARDO = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    private static final UUID MARIA = UUID.fromString("00000000-0000-0000-0000-00000000000f");

    private final GerenciarPlanilhaUseCase planilhas = mock(GerenciarPlanilhaUseCase.class);
    private final SincronizadorPlanilhas sincronizador =
            new SincronizadorPlanilhas(planilhas, Duration.ofMillis(120), Duration.ofMillis(150));

    @AfterEach
    void encerrar() {
        sincronizador.encerrar();
    }

    private static JornadaAtualizadaEvento jornada(UUID usuario, LocalDate data) {
        return new JornadaAtualizadaEvento(usuario, data, OrigemAtualizacao.COMPROVANTE_PDF, null, "batida importada");
    }

    @Test
    @DisplayName("Várias mudanças seguidas viram uma gravação só, com os meses que mudaram")
    void juntaMudancas() {
        when(planilhas.sincronizar(any(), any())).thenReturn(Resultado.GRAVADA);

        sincronizador.aoAtualizarJornada(jornada(EDUARDO, LocalDate.of(2026, 10, 1)));
        sincronizador.aoAtualizarJornada(jornada(EDUARDO, LocalDate.of(2026, 10, 2)));
        sincronizador.aoAlterarBanco(new LancamentoBancoAlteradoEvento(EDUARDO, LocalDate.of(2026, 9, 26), "Folga"));
        sincronizador.aoAtualizarCiclo(new CicloAtualizadoEvento(EDUARDO, null, "período corrigido"));

        verify(planilhas, timeout(3_000)).sincronizar(EDUARDO, Set.of(YearMonth.of(2026, 10), YearMonth.of(2026, 9)));
        verify(planilhas, after(400).times(1)).sincronizar(any(), any());
    }

    @Test
    @DisplayName("Feriado vale para todos: regrava os meses do período nas planilhas de todo mundo")
    void feriadoParaTodos() {
        when(planilhas.vinculados()).thenReturn(List.of(EDUARDO, MARIA));
        when(planilhas.sincronizar(any(), any())).thenReturn(Resultado.GRAVADA);

        sincronizador.aoAlterarCalendario(new CalendarioAlteradoEvento(null, LocalDate.of(2026, 6, 4), LocalDate.of(2026, 6, 4)));

        verify(planilhas, timeout(3_000)).sincronizar(EDUARDO, Set.of(YearMonth.of(2026, 6)));
        verify(planilhas, timeout(3_000)).sincronizar(MARIA, Set.of(YearMonth.of(2026, 6)));
    }

    @Test
    @DisplayName("Horário alterado para um período longo, subida do sistema e virada do dia regravam todas as abas")
    void tudo() {
        when(planilhas.vinculados()).thenReturn(List.of(MARIA));
        when(planilhas.sincronizar(any(), any())).thenReturn(Resultado.GRAVADA);

        sincronizador.aoAlterarCalendario(new CalendarioAlteradoEvento(EDUARDO, LocalDate.of(2020, 1, 1), LocalDate.of(2026, 10, 2)));
        sincronizador.aoSubir();

        verify(planilhas, timeout(3_000)).sincronizar(eq(EDUARDO), isNull());
        verify(planilhas, timeout(3_000)).sincronizar(eq(MARIA), isNull());
    }

    @Test
    @DisplayName("Falha passageira é tentada de novo sozinha; falha que depende de alguém, não")
    void tentaDeNovo() {
        when(planilhas.sincronizar(eq(EDUARDO), any())).thenReturn(Resultado.TENTAR_DE_NOVO, Resultado.TENTAR_DE_NOVO, Resultado.GRAVADA);
        when(planilhas.sincronizar(eq(MARIA), any())).thenReturn(Resultado.FALHOU);

        sincronizador.agendar(EDUARDO, Set.of(YearMonth.of(2026, 10)));
        sincronizador.agendar(MARIA, Set.of(YearMonth.of(2026, 10)));

        verify(planilhas, timeout(5_000).times(3)).sincronizar(eq(EDUARDO), any());
        verify(planilhas, timeout(5_000).times(2)).sincronizar(eq(EDUARDO), isNull()); // as novas tentativas regravam tudo
        verify(planilhas, after(600).times(1)).sincronizar(eq(MARIA), any());
        verify(planilhas, never()).sincronizar(isNull(), any());
        verify(planilhas, times(3)).sincronizar(eq(EDUARDO), any());
    }
}
