package br.com.conferenciaponto.infrastructure.web;

import br.com.conferenciaponto.application.tela.ConsultarBancoUseCase;
import br.com.conferenciaponto.application.tela.ConsultarInicioUseCase;
import br.com.conferenciaponto.application.tela.ConsultarMeuPontoUseCase;
import br.com.conferenciaponto.application.tela.MontadorDeDias;
import br.com.conferenciaponto.infrastructure.web.acesso.Titular;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.infrastructure.web.dto.BancoResponse;
import br.com.conferenciaponto.infrastructure.web.dto.InicioResponse;
import br.com.conferenciaponto.infrastructure.web.dto.MeuPontoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * O que as telas principais mostram, já calculado e escrito (a tela não decide nada, só exibe):
 * <pre>
 * GET /api/v1/inicio             o dia de hoje, pendências, saldos e últimos dias
 * GET /api/v1/ponto?ano=&mes=    o mês dia a dia, com situação, frase e ações de cada dia, e os totais
 * GET /api/v1/banco              o banco de horas: saldo do ciclo, meses, horas usadas ou somadas e fechamentos
 * </pre>
 * Como nas outras leituras, administrador e coordenação consultam outra pessoa com {@code ?usuario=login};
 * nesse caso nenhuma ação vem liberada.
 */
@RestController
@RequestMapping("/api/v1")
public class TelasController {

    private final ConsultarInicioUseCase inicio;
    private final ConsultarMeuPontoUseCase meuPonto;
    private final ConsultarBancoUseCase banco;
    private final Clock clock;

    public TelasController(ConsultarInicioUseCase inicio, ConsultarMeuPontoUseCase meuPonto,
                           ConsultarBancoUseCase banco, Clock clock) {
        this.inicio = inicio;
        this.meuPonto = meuPonto;
        this.banco = banco;
        this.clock = clock;
    }

    @GetMapping("/inicio")
    public ApiResponse<InicioResponse> inicio(Titular titular) {
        return ApiResponse.ok(InicioResponse.de(inicio.agora(titular.id(), quem(titular), outraPessoa(titular))));
    }

    @GetMapping("/ponto")
    public ApiResponse<MeuPontoResponse> ponto(@RequestParam(required = false) Integer ano,
                                              @RequestParam(required = false) Integer mes, Titular titular) {
        return ApiResponse.ok(MeuPontoResponse.de(
                meuPonto.mes(titular.id(), ReferenciaMes.resolver(ano, mes, clock), quem(titular))));
    }

    @GetMapping("/banco")
    public ApiResponse<BancoResponse> banco(Titular titular) {
        return ApiResponse.ok(BancoResponse.de(banco.agora(titular.id(), quem(titular).podeEditar(), outraPessoa(titular))));
    }

    /** Só o dono dos dados, com perfil que registra ponto, recebe ações liberadas. */
    private static MontadorDeDias.Quem quem(Titular titular) {
        return new MontadorDeDias.Quem(titular.proprio() && titular.logado().isTitular(), titular.logado().isAdmin());
    }

    /** Primeiro nome de quem é o ponto, quando não é de quem está olhando. */
    private static String outraPessoa(Titular titular) {
        if (titular.proprio()) {
            return null;
        }
        String nome = titular.nome() == null || titular.nome().isBlank() ? titular.login() : titular.nome().strip();
        return nome.split("\\s+")[0];
    }
}
