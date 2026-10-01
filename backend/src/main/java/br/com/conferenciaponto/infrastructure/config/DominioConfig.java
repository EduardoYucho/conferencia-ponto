package br.com.conferenciaponto.infrastructure.config;

import br.com.conferenciaponto.application.ParametrosBancoHoras;
import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.port.CalendarioAusencias;
import br.com.conferenciaponto.domain.port.CalendarioFeriados;
import br.com.conferenciaponto.domain.port.HorarioTrabalhoRepository;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.ComparadorConciliacaoService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Registra os serviços de domínio como beans. O domínio não conhece o Spring:
 * a composição acontece somente aqui.
 */
@Configuration
public class DominioConfig {

    @Bean
    public Clock clock(PontoProperties properties) {
        return Clock.system(ZoneId.of(properties.fusoHorario()));
    }

    /** Horário padrão (da configuração) para quem ainda não cadastrou o próprio. */
    static GradeHoraria gradePadrao(PontoProperties properties) {
        PontoProperties.Grade g = properties.grade();
        return new GradeHoraria(
                LocalTime.parse(g.entrada1()),
                LocalTime.parse(g.saida1()),
                LocalTime.parse(g.entrada2()),
                LocalTime.parse(g.saida2()));
    }

    @Bean
    public RegrasJornada regrasJornada(HorarioTrabalhoRepository horarios, ClassificadorDiaService classificador,
                                       PontoProperties properties) {
        return new RegrasJornada(horarios, classificador, gradePadrao(properties), properties.toleranciaMinutos());
    }

    @Bean
    public ComparadorConciliacaoService comparadorConciliacaoService() {
        return new ComparadorConciliacaoService();
    }

    @Bean
    public ParametrosBancoHoras parametrosBancoHoras(BancoHorasProperties properties) {
        return new ParametrosBancoHoras(properties.inicioPrimeiroCiclo(), properties.duracaoMeses());
    }

    @Bean
    public ClassificadorDiaService classificadorDiaService(CalendarioFeriados calendarioFeriados,
                                                           CalendarioAusencias calendarioAusencias) {
        return new ClassificadorDiaService(calendarioFeriados, calendarioAusencias);
    }
}
