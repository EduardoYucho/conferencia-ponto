package br.com.conferenciaponto.modulos.fiscal.infrastructure.web;

import br.com.conferenciaponto.domain.exception.AcessoNegadoException;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.infrastructure.web.acesso.AcessoUsuarios;
import br.com.conferenciaponto.infrastructure.web.dto.ApiResponse;
import br.com.conferenciaponto.modulos.fiscal.application.acesso.PermissoesDoMonitor;
import br.com.conferenciaponto.modulos.fiscal.application.coleta.LerAgora;
import br.com.conferenciaponto.modulos.fiscal.application.consulta.ConsultarMonitor;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A tela do monitor fiscal.
 *
 * <pre>
 * GET  /api/v1/fiscal/meu-acesso              qualquer pessoa: {ver, configurar}
 * GET  /api/v1/fiscal/painel                  o quadro, os autorizadores, as fontes e os avisos
 * GET  /api/v1/fiscal/incidentes?dias=1       os incidentes do período (1 a 90 dias), o mais recente primeiro
 * GET  /api/v1/fiscal/disponibilidade?dias=1  a porcentagem no ar de cada autorizador no período
 * POST /api/v1/fiscal/coletar                 uma leitura agora (fiscal.configurar; no máximo uma por minuto)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/fiscal")
public class MonitorFiscalController {

    public record MeuAcessoResponse(boolean ver, boolean configurar) {
    }

    private final AcessoUsuarios acesso;
    private final ConsultarMonitor consulta;
    private final LerAgora lerAgora;

    public MonitorFiscalController(AcessoUsuarios acesso, ConsultarMonitor consulta, LerAgora lerAgora) {
        this.acesso = acesso;
        this.consulta = consulta;
        this.lerAgora = lerAgora;
    }

    @GetMapping("/meu-acesso")
    public ApiResponse<MeuAcessoResponse> meuAcesso() {
        Usuario logado = acesso.logado();
        boolean ativo = logado.ativo();
        return ApiResponse.ok(new MeuAcessoResponse(ativo && logado.pode(PermissoesDoMonitor.VER),
                ativo && logado.pode(PermissoesDoMonitor.CONFIGURAR)));
    }

    @GetMapping("/painel")
    public ApiResponse<ConsultarMonitor.PainelView> painel() {
        return ApiResponse.ok(consulta.painel());
    }

    @GetMapping("/incidentes")
    public ApiResponse<List<ConsultarMonitor.IncidenteView>> incidentes(@RequestParam(defaultValue = "1") int dias) {
        return ApiResponse.ok(consulta.incidentes(dias));
    }

    @GetMapping("/disponibilidade")
    public ApiResponse<List<ConsultarMonitor.DisponibilidadeView>> disponibilidade(
            @RequestParam(defaultValue = "1") int dias) {
        return ApiResponse.ok(consulta.disponibilidade(dias));
    }

    @PostMapping("/coletar")
    public ApiResponse<ConsultarMonitor.RodadaView> coletar() {
        if (!acesso.logado().pode(PermissoesDoMonitor.CONFIGURAR)) {
            throw new AcessoNegadoException("SEM_PERMISSAO", "Ler agora é só para quem pode configurar o monitor.");
        }
        RegistroDoMonitor.Rodada r = lerAgora.executar();
        return ApiResponse.ok(new ConsultarMonitor.RodadaView(r.inicio(), r.fim(), r.semInternet(), r.fontesComProblema()));
    }
}
