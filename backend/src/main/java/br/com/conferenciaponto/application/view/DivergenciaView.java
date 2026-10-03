package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.application.tela.DiaView.Tom;
import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.TipoDia;

import java.util.List;

/**
 * Diferença com os dois lados atuais (sistema × RH) e, em {@link #tela()}, tudo o que a tela "Conferir com o RH"
 * mostra já escrito e decidido: a frase do que difere, o que cada lado tem, o tamanho da diferença e quais
 * botões aparecem.
 *
 * @param local        o dia como está hoje no sistema (null se não houver registro)
 * @param tipoDiaLocal classificação atual da data no sistema
 * @param rh           o dia no relatório vigente do RH (null se o relatório foi removido)
 * @param tela         a diferença pronta para a tela
 */
public record DivergenciaView(Divergencia divergencia, RegistroJornadaView local, TipoDia tipoDiaLocal,
                              DiaRelatorioRh rh, RelatorioRh relatorio, Tela tela) {

    /** Só os dois lados, sem os textos da tela. */
    public DivergenciaView(Divergencia divergencia, RegistroJornadaView local, TipoDia tipoDiaLocal,
                           DiaRelatorioRh rh, RelatorioRh relatorio) {
        this(divergencia, local, tipoDiaLocal, rh, relatorio, null);
    }

    /**
     * A diferença em palavras.
     *
     * @param dia             "Quarta, 24/06/2026"
     * @param tipo            nome do tipo de diferença ("Falta batida no sistema")
     * @param frase           o que está diferente, numa frase ("Falta 1 batida no sistema: o RH tem 13:00.")
     * @param impacto         o tamanho da diferença no saldo do dia
     * @param sistema         o que o sistema tem hoje neste dia
     * @param rh              o que o relatório vigente do RH tem neste dia
     * @param situacao        "Para decidir", "Usado o do RH", "Mantido como está no sistema", "Ficou igual ao RH"
     * @param tom             a cor que a situação pede
     * @param acoes           o que quem está olhando pode fazer com esta diferença
     * @param porQueNaoUsarRh por que "Usar o do RH" não está disponível ({@code null} quando está, ou quando
     *                        quem olha só consulta)
     * @param aoUsarRh        o que acontece ao usar o do RH ({@code null} quando não dá para usar)
     * @param motivoDoAjuste  motivo sugerido para o ajuste à mão ("Conforme relatório do RH emitido em...")
     */
    public record Tela(String dia, String tipo, String frase, String impacto, Lado sistema, Lado rh, String situacao,
                       Tom tom, Acoes acoes, String porQueNaoUsarRh, String aoUsarRh, String motivoDoAjuste) {
    }

    /**
     * Um dos lados do dia.
     *
     * @param titulo     o que o dia é desse lado quando isso importa ("Dia de trabalho, sem batidas", "Férias");
     *                   {@code null} num dia de trabalho com batidas
     * @param horarios   as batidas, na ordem
     * @param trabalhado "8h 52min"; {@code null} quando não há tempo apurado
     * @param saldo      "+ 6 min a favor", "fora do saldo até ser corrigido"...; {@code null} sem relatório
     * @param tom        a cor que o saldo pede
     */
    public record Lado(String titulo, List<Hora> horarios, String trabalhado, String saldo, Tom tom) {

        public Lado {
            horarios = List.copyOf(horarios);
        }
    }

    /**
     * Uma batida de um dos lados.
     *
     * @param texto como a tela escreve ("08:05"; com segundos quando são eles que diferem)
     * @param exato o horário com segundos ("08:05:53")
     * @param marca como ela se compara com o outro lado
     */
    public record Hora(String texto, String exato, Marca marca) {
    }

    public enum Marca {
        /** O outro lado tem exatamente a mesma batida. */
        IGUAL,
        /** É a mesma batida, com segundos diferentes. */
        SEGUNDOS,
        /** O outro lado não tem esta batida. */
        DIFERENTE
    }

    /**
     * Os botões da diferença.
     *
     * @param usarRh  "Usar o do RH"
     * @param manter  "Manter o meu"
     * @param ajustar "Ajustar as batidas à mão" (abre o ajuste do dia com as batidas do RH sugeridas)
     * @param folgas  a diferença é de folga ou feriado: resolve-se em "Folgas e feriados"
     * @param reabrir a decisão já tomada pode voltar a ficar para decidir
     */
    public record Acoes(boolean usarRh, boolean manter, boolean ajustar, boolean folgas, boolean reabrir) {

        public static final Acoes NENHUMA = new Acoes(false, false, false, false, false);
    }
}
