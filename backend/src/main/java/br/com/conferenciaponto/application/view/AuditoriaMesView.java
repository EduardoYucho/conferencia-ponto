package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.SaldoMensal;

import java.time.YearMonth;
import java.util.List;

/** Visão de auditoria de um mês: cada dia com seus comprovantes arquivados e ajustes manuais. */
public record AuditoriaMesView(YearMonth referencia, List<Dia> dias, SaldoMensal resumo, List<Ausencia> ausencias) {

    /** @param ajustes histórico de ajustes manuais do dia, do mais recente para o mais antigo */
    public record Dia(RegistroJornadaView registro, List<ComprovanteArquivoView> comprovantes,
                      List<AjusteJornada> ajustes) {
    }
}
