package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.HorarioTrabalhoRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Quem passa a ter o próprio ponto (um usuário novo, ou alguém que ganhou um perfil com ponto.proprio) ganha o
 * horário padrão, que vale para todo o histórico, e o ciclo do banco de horas aberto. Quem já tem não muda.
 */
@Component
public class PreparacaoDoTitular {

    private final HorarioTrabalhoRepository horarios;
    private final RegrasJornada regras;
    private final GerenciarCicloBancoUseCase ciclos;
    private final Clock clock;

    public PreparacaoDoTitular(HorarioTrabalhoRepository horarios, RegrasJornada regras, GerenciarCicloBancoUseCase ciclos,
                               Clock clock) {
        this.horarios = horarios;
        this.regras = regras;
        this.ciclos = ciclos;
        this.clock = clock;
    }

    /** @param quem login de quem fez a mudança (vai para o horário gravado) */
    public void preparar(Usuario usuario, String quem) {
        if (!usuario.isTitular() || !usuario.ativo()) {
            return;
        }
        if (horarios.listarPorUsuario(usuario.id()).isEmpty()) {
            horarios.salvar(regras.padraoParaGravar(usuario.id(), quem, clock.instant()));
            regras.invalidar(usuario.id());
        }
        ciclos.garantirCicloAberto(usuario.id());
    }
}
