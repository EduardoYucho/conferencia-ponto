package br.com.conferenciaponto.infrastructure.agendamento;

import br.com.conferenciaponto.application.evento.CicloAtualizadoEvento;
import br.com.conferenciaponto.application.usecase.GerenciarCicloBancoUseCase;
import br.com.conferenciaponto.application.usecase.VerificarPrazoCicloUseCase;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Fechar, desfazer ou corrigir o ciclo atualiza os avisos de prazo depois de gravado. O evento chega com a transação
 * do fechamento já confirmada: quem grava ali precisa de uma transação nova, senão o arquivamento dos avisos do ciclo
 * antigo não vai para o banco e o sino continua com "Fechamento pendente".
 */
class PrazoCicloBancoAgendadoTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    @Test
    @DisplayName("Depois de gravado, o aviso de prazo é conferido numa transação nova (REQUIRES_NEW)")
    void transacaoNovaDepoisDeGravado() throws Exception {
        VerificarPrazoCicloUseCase verificar = mock(VerificarPrazoCicloUseCase.class);
        Clock clock = Clock.fixed(LocalDate.of(2026, 11, 25).atTime(LocalTime.NOON).atZone(SP).toInstant(), SP);
        var agendado = new PrazoCicloBancoAgendado(mock(GerenciarCicloBancoUseCase.class), verificar,
                mock(UsuarioRepository.class), clock);
        UUID pessoa = UUID.randomUUID();

        agendado.aoAtualizarCiclo(new CicloAtualizadoEvento(pessoa, null, "Banco de horas fechado"));

        verify(verificar).executarAposGravar(pessoa, LocalDate.of(2026, 11, 25));
        verify(verificar, never()).executar(any(), any());

        var ouvinte = PrazoCicloBancoAgendado.class.getMethod("aoAtualizarCiclo", CicloAtualizadoEvento.class)
                .getAnnotation(TransactionalEventListener.class);
        assertThat(ouvinte.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        var transacao = VerificarPrazoCicloUseCase.class.getMethod("executarAposGravar", UUID.class, LocalDate.class)
                .getAnnotation(Transactional.class);
        assertThat(transacao.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
