package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.port.HorarioTrabalhoRepository;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;

import java.util.UUID;

/** Dados comuns dos testes: o usuário dono dos dados e as regras de cálculo com o horário padrão. */
final class Fixtures {

    /** Titular dos dados nos testes. */
    static final UUID USUARIO = UUID.fromString("00000000-0000-0000-0000-00000000000e");
    /** Outro titular (para conferir o isolamento entre usuários). */
    static final UUID OUTRO = UUID.fromString("00000000-0000-0000-0000-00000000000f");

    private Fixtures() {
    }

    static RegrasJornada regras(ClassificadorDiaService classificador) {
        return regras(classificador, new HorarioTrabalhoRepositoryEmMemoria());
    }

    static RegrasJornada regras(ClassificadorDiaService classificador, HorarioTrabalhoRepository horarios) {
        return new RegrasJornada(horarios, classificador, GradeHoraria.PADRAO, 5);
    }
}
