package br.com.conferenciaponto.infrastructure.agendamento;

import br.com.conferenciaponto.application.evento.CicloAtualizadoEvento;
import br.com.conferenciaponto.application.usecase.GerenciarCicloBancoUseCase;
import br.com.conferenciaponto.application.usecase.VerificarPrazoCicloUseCase;
import br.com.conferenciaponto.domain.model.CicloBanco;
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

/**
 * Ciclo do banco de horas:
 * <ul>
 *   <li>na subida, garante um ciclo aberto e confere os prazos (o PC pode estar desligado à 01:00);</li>
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
    private final Clock clock;

    PrazoCicloBancoAgendado(GerenciarCicloBancoUseCase ciclos, VerificarPrazoCicloUseCase verificarPrazo, Clock clock) {
        this.ciclos = ciclos;
        this.verificarPrazo = verificarPrazo;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        CicloBanco aberto = ciclos.garantirCicloAberto();
        log.info("Banco de horas: ciclo aberto desde {} (fechamento previsto em {})",
                DATA.format(aberto.dataInicio()), DATA.format(aberto.dataFimPrevista()));
        verificar();
    }

    @Scheduled(cron = "${ponto.banco-horas.cron-alertas:0 0 1 * * *}", zone = "${ponto.fuso-horario:America/Sao_Paulo}")
    public void verificarDiariamente() {
        verificar();
    }

    @EventListener
    public void aoAtualizarCiclo(CicloAtualizadoEvento evento) {
        verificarPrazo.executar(LocalDate.now(clock));
    }

    private void verificar() {
        try {
            verificarPrazo.executar(LocalDate.now(clock))
                    .ifPresent(n -> log.info("Aviso do banco de horas: {}", n.titulo()));
        } catch (RuntimeException e) {
            log.warn("Falha ao conferir o prazo do banco de horas: {}", e.getMessage());
        }
    }
}
