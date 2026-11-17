package br.com.conferenciaponto.infrastructure.importacao;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.usecase.NotificacoesUseCase;
import br.com.conferenciaponto.domain.acesso.Acesso;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.Notificacao;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import br.com.conferenciaponto.infrastructure.importacao.EstadoMonitor.Situacao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvisoDaPastaInacessivelTest {

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate TERCA = LocalDate.of(2026, 11, 17);

    private final GerenciadorMonitoresPdf monitores = mock(GerenciadorMonitoresPdf.class);
    private final RegrasJornada regras = mock(RegrasJornada.class);
    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final NotificacoesUseCase notificacoes = mock(NotificacoesUseCase.class);
    private final Usuario helena = new Usuario(UUID.randomUUID(), "helena", "Helena", "h", true, Acesso.usuario(), null);
    private final Usuario admin = new Usuario(UUID.randomUUID(), "eduardo", "Eduardo", "h", true, Acesso.administrador(), null);

    @BeforeEach
    void preparar() {
        when(usuarios.buscarPorId(helena.id())).thenReturn(Optional.of(helena));
        when(usuarios.listar()).thenReturn(List.of(helena, admin));
        when(regras.classificar(any(), any())).thenReturn(TipoDia.UTIL);
        when(regras.horario(any(), any())).thenReturn(HorarioTrabalho.semanal(helena.id(), HorarioTrabalho.DESDE_SEMPRE,
                GradeHoraria.PADRAO, 5, "teste", Instant.EPOCH)); // 08:00-12:00 e 13:00-17:48
        when(notificacoes.notificar(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(mock(Notificacao.class)));
    }

    private AvisoDaPastaInacessivel aviso(int hora, int minuto) {
        Instant agora = TERCA.atTime(hora, minuto).atZone(FUSO).toInstant();
        return new AvisoDaPastaInacessivel(monitores, regras, usuarios, notificacoes, Clock.fixed(agora, FUSO));
    }

    private void foraDoArDesde(LocalDateTime desde) {
        when(monitores.estados()).thenReturn(List.of(new EstadoMonitor(helena.id(), Situacao.INDISPONIVEL, "pasta",
                "pasta não encontrada", desde.atZone(FUSO).toInstant(), null)));
    }

    @Test
    void umaHoraForaDoArNoExpedienteAvisaADonaEQuemVeOsLogs() {
        foraDoArDesde(TERCA.atTime(9, 0));

        assertThat(aviso(10, 1).conferirAgora()).isEqualTo(2);

        String chave = "PASTA_INACESSIVEL:" + helena.id() + ":2026-11-17";
        verify(notificacoes).notificar(eq(helena.id()), eq(TipoNotificacao.PASTA), eq(chave),
                eq("Pasta dos comprovantes inacessível"),
                contains("A pasta dos seus comprovantes está inacessível desde 09:00 (pasta não encontrada)."), eq("/"));
        verify(notificacoes).notificar(eq(admin.id()), eq(TipoNotificacao.PASTA), eq(chave), anyString(),
                contains("A pasta dos comprovantes de Helena está inacessível desde 09:00"), eq("/"));
    }

    @Test
    void aQuedaDaNoiteSoContaAPartirDoComecoDoExpediente() {
        foraDoArDesde(TERCA.minusDays(1).atTime(18, 30)); // o outro computador desligou ontem à noite

        assertThat(aviso(8, 59).conferirAgora()).as("ainda não passou 1 h de expediente").isZero();
        assertThat(aviso(9, 0).conferirAgora()).isEqualTo(2);
        verify(notificacoes).notificar(eq(helena.id()), any(), anyString(), anyString(),
                contains("inacessível desde 16/11 às 18:30"), anyString());
    }

    @Test
    void foraDoExpedienteOuEmDiaSemExpedienteNaoAvisa() {
        foraDoArDesde(TERCA.atTime(9, 0));
        assertThat(aviso(19, 0).conferirAgora()).as("depois da última saída").isZero();
        assertThat(aviso(7, 59).conferirAgora()).as("antes da primeira entrada").isZero();

        when(regras.classificar(any(), any())).thenReturn(TipoDia.AUSENCIA); // férias, atestado...
        assertThat(aviso(11, 0).conferirAgora()).isZero();
        verify(notificacoes, never()).notificar(any(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aPastaQueVoltouOuQueCaiuHaPoucoNaoAvisa() {
        when(monitores.estados()).thenReturn(List.of(new EstadoMonitor(helena.id(), Situacao.ATIVO, "pasta", null,
                TERCA.atTime(9, 0).atZone(FUSO).toInstant(), null)));
        assertThat(aviso(11, 0).conferirAgora()).isZero();

        foraDoArDesde(TERCA.atTime(10, 30));
        assertThat(aviso(11, 0).conferirAgora()).isZero();
    }

    @Test
    void oAvisoQueJaSaiuHojeNaoContaDeNovoENadaEscapa() {
        foraDoArDesde(TERCA.atTime(9, 0));
        when(notificacoes.notificar(any(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty()); // a chave do dia já existe
        assertThat(aviso(11, 0).conferirAgora()).isZero();

        when(regras.classificar(any(), any())).thenThrow(new IllegalStateException("banco fora"));
        assertThat(aviso(11, 0).conferirAgora()).isZero();
        when(monitores.estados()).thenThrow(new IllegalStateException("inesperado"));
        aviso(11, 0).conferir(); // não lança
    }
}
