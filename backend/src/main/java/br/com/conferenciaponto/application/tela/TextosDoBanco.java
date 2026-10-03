package br.com.conferenciaponto.application.tela;

import br.com.conferenciaponto.application.tela.InicioView.Banco;
import br.com.conferenciaponto.application.view.CicloBancoView;

import java.time.LocalDate;

/** As frases do banco de horas (saldo e prazo de fechamento), iguais no Início e na tela do banco. */
final class TextosDoBanco {

    /** A partir de quantos dias para o fechamento o banco de horas pede atenção. */
    static final int PRAZO_URGENTE_DIAS = 30;

    private TextosDoBanco() {
    }

    /** @param outraPessoa primeiro nome de quem é o banco, quando não é de quem está olhando ({@code null} = o próprio) */
    static Banco resumo(CicloBancoView ciclo, String outraPessoa) {
        Long dias = ciclo.diasAtePrevisao();
        LocalDate fechaEm = ciclo.ciclo().dataFimPrevista();
        String prazo = null;
        if (fechaEm != null && dias != null) {
            if (dias < 0) {
                prazo = "A previsão de fechamento (%s) passou há %d dia%s".formatted(Horas.diaMes(fechaEm), -dias,
                        dias == -1 ? "" : "s");
            } else if (dias == 0) {
                prazo = "O RH fecha hoje (%s)".formatted(Horas.diaMes(fechaEm));
            } else {
                prazo = "O RH fecha em %s (falta%s %d dia%s)".formatted(Horas.diaMes(fechaEm), dias == 1 ? "" : "m",
                        dias, dias == 1 ? "" : "s");
            }
        }
        return new Banco(ciclo.saldoSegundos(), ciclo.ciclo().dataInicio(), fechaEm, dias,
                dias != null && dias <= PRAZO_URGENTE_DIAS,
                sentido(ciclo.saldoSegundos(), outraPessoa) + " desde " + Horas.diaMes(ciclo.ciclo().dataInicio()), prazo);
    }

    /** "a seu favor", "devendo" ou "zerado" (para outra pessoa: "a favor"). */
    static String sentido(int saldo, String outraPessoa) {
        if (saldo == 0) {
            return "zerado";
        }
        return saldo > 0 ? (outraPessoa == null ? "a seu favor" : "a favor") : "devendo";
    }
}
