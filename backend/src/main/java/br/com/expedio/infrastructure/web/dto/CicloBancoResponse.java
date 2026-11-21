package br.com.expedio.infrastructure.web.dto;

import br.com.expedio.application.view.CicloBancoView;
import br.com.expedio.domain.model.CicloBanco;
import br.com.expedio.domain.model.StatusCiclo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ciclo do banco de horas.
 *
 * @param dataFim            ciclo fechado: último dia incluído; aberto: igual à previsão
 * @param saldoSegundos      aberto: saldo atual; fechado: saldo final congelado no fechamento
 * @param diasAtePrevisao    só no aberto (negativo = previsão já passou)
 * @param sugestaoFechamento só no aberto: último dia sugerido para o botão "Fechar banco de horas"
 */
public record CicloBancoResponse(UUID id, StatusCiclo status, LocalDate dataInicio, LocalDate dataFim,
                                 LocalDate dataFimPrevista, int saldoSegundos, int diasRegistrados, int diasEmAberto,
                                 Long diasAtePrevisao, LocalDate sugestaoFechamento, Instant fechadoEm,
                                 String fechadoPor, String observacao, List<ResumoMensalResponse> meses) {

    public static CicloBancoResponse de(CicloBancoView v) {
        CicloBanco c = v.ciclo();
        return new CicloBancoResponse(c.id(), c.status(), c.dataInicio(), c.dataFim(), c.dataFimPrevista(),
                v.saldoSegundos(), v.diasRegistrados(), v.diasEmAberto(), v.diasAtePrevisao(), v.sugestaoFechamento(),
                c.fechadoEm(), c.fechadoPor(), c.observacao(),
                v.meses().stream().map(ResumoMensalResponse::de).toList());
    }
}
