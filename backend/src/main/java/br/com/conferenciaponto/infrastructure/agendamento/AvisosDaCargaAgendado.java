package br.com.conferenciaponto.infrastructure.agendamento;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.usecase.VerificarCompensacaoUseCase;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Os avisos do sino sobre a carga semanal e as folgas ({@link VerificarCompensacaoUseCase}):
 * <ul>
 *   <li>na subida e todo dia às 01:00 (o PC pode estar desligado de madrugada);</li>
 *   <li>quando um dia sem expediente dos últimos 14 dias é lançado (o fim de semana pode ter tirado a folga).</li>
 * </ul>
 * Uma falha aqui só vai para o log: o aviso não pode desfazer o lançamento.
 */
@Component
@Order(30)
class AvisosDaCargaAgendado implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AvisosDaCargaAgendado.class);
    private static final int DIAS_OLHADOS = 14;

    private final VerificarCompensacaoUseCase verificar;
    private final UsuarioRepository usuarios;
    private final RegrasJornada regras;
    private final Clock clock;

    AvisosDaCargaAgendado(VerificarCompensacaoUseCase verificar, UsuarioRepository usuarios, RegrasJornada regras,
                          Clock clock) {
        this.verificar = verificar;
        this.usuarios = usuarios;
        this.regras = regras;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        verificarTodos();
    }

    @Scheduled(cron = "${ponto.banco-horas.cron-alertas:0 0 1 * * *}", zone = "${ponto.fuso-horario:America/Sao_Paulo}")
    public void verificarDiariamente() {
        verificarTodos();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAtualizarJornada(JornadaAtualizadaEvento evento) {
        LocalDate hoje = LocalDate.now(clock);
        LocalDate data = evento.data();
        if (data == null || data.isAfter(hoje) || data.isBefore(hoje.minusDays(DIAS_OLHADOS))) {
            return;
        }
        try {
            if (!regras.horario(evento.usuarioId(), data).temExpediente(data)) {
                verificar.folgasAgora(evento.usuarioId(), hoje);
            }
        } catch (RuntimeException e) {
            log.warn("Falha ao conferir as folgas depois do lançamento de {}", data, e);
        }
    }

    private void verificarTodos() {
        LocalDate hoje = LocalDate.now(clock);
        for (Usuario u : usuarios.listar().stream().filter(u -> u.ativo() && u.isTitular()).toList()) {
            try {
                verificar.executar(u.id(), hoje)
                        .forEach(n -> log.info("Aviso da carga semanal de {}: {}", u.login(), n.titulo()));
            } catch (RuntimeException e) {
                log.warn("Falha ao conferir a carga semanal e as folgas de {}", u.login(), e);
            }
        }
    }
}
