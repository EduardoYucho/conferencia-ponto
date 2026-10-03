package br.com.conferenciaponto.infrastructure.agendamento;

import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;
import br.com.conferenciaponto.application.evento.CicloAtualizadoEvento;
import br.com.conferenciaponto.application.usecase.GerenciarCicloBancoUseCase;
import br.com.conferenciaponto.application.usecase.VerificarPrazoCicloUseCase;
import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Ciclo do banco de horas:
 * <ul>
 *   <li>na subida, garante um ciclo aberto para cada usuário e confere os prazos (o PC pode estar desligado à
 *       01:00);</li>
 *   <li>todo dia às 01:00 ({@code ponto.banco-horas.cron-alertas}) confere de novo;</li>
 *   <li>ao fechar/corrigir o ciclo, confere na mesma transação (a nova previsão pode já estar perto).</li>
 * </ul>
 */
@Component
@Order(20)
class PrazoCicloBancoAgendado implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PrazoCicloBancoAgendado.class);
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final GerenciarCicloBancoUseCase ciclos;
    private final VerificarPrazoCicloUseCase verificarPrazo;
    private final UsuarioRepository usuarios;
    private final Clock clock;

    PrazoCicloBancoAgendado(GerenciarCicloBancoUseCase ciclos, VerificarPrazoCicloUseCase verificarPrazo,
                            UsuarioRepository usuarios, Clock clock) {
        this.ciclos = ciclos;
        this.verificarPrazo = verificarPrazo;
        this.usuarios = usuarios;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            for (Usuario u : titulares()) {
                try {
                    CicloBanco aberto = ciclos.garantirCicloAberto(u.id());
                    log.info("Banco de horas de {}: ciclo aberto desde {} (fechamento previsto em {})", u.login(),
                            DATA.format(aberto.dataInicio()), DATA.format(aberto.dataFimPrevista()));
                } catch (RuntimeException e) {
                    log.error("Não foi possível abrir o ciclo do banco de horas de {}", u.login(), e);
                }
            }
            verificar();
        } catch (RuntimeException e) {
            log.error("Conferência dos ciclos do banco de horas na subida falhou", e);
        }
    }

    @Scheduled(cron = "${ponto.banco-horas.cron-alertas:0 0 1 * * *}", zone = "${ponto.fuso-horario:America/Sao_Paulo}")
    public void verificarDiariamente() {
        verificar();
    }

    /** Depois de gravado: uma falha no aviso não desfaz o fechamento ou a correção do ciclo. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void aoAtualizarCiclo(CicloAtualizadoEvento evento) {
        try {
            verificarPrazo.executar(evento.usuarioId(), LocalDate.now(clock));
        } catch (RuntimeException e) {
            log.warn("Falha ao atualizar o aviso de prazo do banco de horas", e);
        }
    }

    private void verificar() {
        for (Usuario u : titulares()) {
            try {
                verificarPrazo.executar(u.id(), LocalDate.now(clock))
                        .ifPresent(n -> log.info("Aviso do banco de horas de {}: {}", u.login(), n.titulo()));
            } catch (RuntimeException e) {
                log.warn("Falha ao conferir o prazo do banco de horas de {}", u.login(), e);
            }
        }
    }

    private List<Usuario> titulares() {
        return usuarios.listar().stream().filter(u -> u.ativo() && u.isTitular()).toList();
    }
}
