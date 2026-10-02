package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.SaldoMensal;

import java.time.YearMonth;
import java.util.List;

/**
 * Visão de auditoria de um mês: cada dia com seus comprovantes arquivados e ajustes manuais, mais o calendário
 * do mês (feriados, ausências e o expediente previsto de cada dia), usado pela planilha de conferência para
 * mostrar também os dias sem registro.
 */
public record AuditoriaMesView(YearMonth referencia, List<Dia> dias, SaldoMensal resumo, List<Ausencia> ausencias,
                               List<LancamentoBanco> lancamentos, List<Feriado> feriados,
                               List<MesJornadaView.Expediente> expedientes) {

    /** @param ajustes histórico de ajustes manuais do dia, do mais recente para o mais antigo */
    public record Dia(RegistroJornadaView registro, List<ComprovanteArquivoView> comprovantes,
                      List<AjusteJornada> ajustes) {
    }
}
