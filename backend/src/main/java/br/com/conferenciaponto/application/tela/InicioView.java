package br.com.conferenciaponto.application.tela;

import br.com.conferenciaponto.application.view.PresencaView;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * A tela "Início": o dia de hoje, o que pede atenção e os saldos — tudo já calculado e escrito.
 *
 * @param saudacao      "Bom dia", "Boa tarde" ou "Boa noite", pela hora do servidor
 * @param dataPorExtenso "sexta-feira, 2 de outubro de 2026"
 * @param banco         {@code null} quando a pessoa ainda não tem ciclo de banco de horas
 * @param ultimosDias   os últimos dias com algo para mostrar (hoje primeiro)
 */
public record InicioView(LocalDate hoje, LocalTime agora, String saudacao, String dataPorExtenso, Hoje dia, Mes mes,
                         Banco banco, List<Pendencia> pendencias, List<DiaView> ultimosDias) {

    /**
     * @param titulo             "Você está trabalhando", "Você está em intervalo", "Hoje é feriado"...
     * @param detalhe            complemento do título ("desde 13:00")
     * @param trabalhadoSegundos trabalhado hoje até este instante (inclui o período em aberto)
     * @param percentual         quanto da jornada de hoje já foi cumprido (0 a 100)
     * @param resumo             "Faltam 4h 06min. Saindo às 17:48 o dia fecha sem dever nem sobrar."
     * @param proximaBatida      nome da próxima batida ("Saída"); {@code null} quando não há mais batida a fazer
     * @param proximaPrevista    horário da grade para ela, se houver
     * @param podeBater          o botão de bater ponto está liberado para quem está olhando
     * @param rotuloBotao        "Bater a saída agora", "Jornada completa"...
     * @param aviso              por que o botão não está liberado (quando não está)
     * @param dia                o dia de hoje completo (para corrigir ou ajustar)
     */
    public record Hoje(PresencaView.Situacao situacao, String titulo, String detalhe, boolean trabalhando,
                       int trabalhadoSegundos, int previstoSegundos, int percentual, String resumo,
                       String proximaBatida, LocalTime proximaPrevista, boolean podeBater, String rotuloBotao,
                       String aviso, DiaView dia) {
    }

    /** @param texto "a seu favor, em 12 dias fechados" */
    public record Mes(String nome, int saldoSegundos, int diasFechados, String texto) {
    }

    /**
     * @param texto  "a seu favor desde 25/05"
     * @param prazo  "O RH fecha em 24/11 (faltam 53 dias)"; {@code null} sem previsão
     * @param urgente faltam 30 dias ou menos (ou a previsão já passou)
     */
    public record Banco(int saldoSegundos, LocalDate desde, LocalDate fechaEm, Long diasParaFechar, boolean urgente,
                        String texto, String prazo) {
    }

    /**
     * Algo que espera uma ação da pessoa.
     *
     * @param texto "Quarta, 30/09: faltou bater a saída"
     * @param acao  rótulo do botão ("Corrigir", "Conferir", "Ver os dias")
     * @param data  o dia a abrir, quando a pendência é de um dia só
     */
    public record Pendencia(Tipo tipo, String texto, String acao, LocalDate data, int quantidade) {

        public enum Tipo {
            /** Um ou mais dias com batida faltando. */
            DIA_INCOMPLETO,
            /** Um ou mais dias de trabalho sem nenhuma batida. */
            DIA_SEM_REGISTRO,
            /** Diferenças com o relatório do RH esperando decisão. */
            DIVERGENCIAS_RH,
            /** O fechamento do banco de horas está perto (ou já passou). */
            BANCO_PRAZO
        }
    }
}
