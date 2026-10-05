package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.TipoDia;

/**
 * Divergência com os dois lados atuais, para a tela dividida (conferência × RH).
 *
 * @param local        o dia como está hoje na conferência (null se não houver registro)
 * @param tipoDiaLocal classificação atual da data na conferência
 * @param rh           o dia no relatório vigente do RH (null se o relatório foi removido)
 */
public record DivergenciaView(Divergencia divergencia, RegistroJornadaView local, TipoDia tipoDiaLocal,
                              DiaRelatorioRh rh, RelatorioRh relatorio) {
}
