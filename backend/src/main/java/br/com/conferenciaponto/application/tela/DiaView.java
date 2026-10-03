package br.com.conferenciaponto.application.tela;

import br.com.conferenciaponto.application.view.ComprovanteArquivoView;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.TipoBatida;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Um dia pronto para a tela: o que aconteceu, como ficou e o que dá para fazer com ele. Tudo o que é decisão
 * (a situação, a frase, a cor, quais botões aparecem, em quais filtros o dia entra) já vem decidido — a tela só
 * mostra.
 *
 * @param relativo       "Hoje" ou "Ontem" ({@code null} nos outros dias)
 * @param situacaoTexto  a situação em palavras ("+ 15 min a favor", "Faltou bater a saída", "em dia")
 * @param descricao      linha de apoio ("entrada 12 min depois do horário", "Domingo, sem expediente")
 * @param faltando       o que falta para fechar o dia ("sem saída"); {@code null} quando nada falta
 * @param trabalhadoSegundos tempo entre as batidas fechadas; {@code null} sem registro
 * @param saldoSegundos  saldo do dia; {@code null} enquanto o dia não fecha (ou sem registro)
 * @param lancadoSegundos soma dos lançamentos avulsos no banco de horas feitos neste dia
 * @param divergenciaRh  há uma diferença com o relatório do RH esperando decisão
 * @param registro       o registro completo do dia (para a linha do tempo e o ajuste); {@code null} sem registro
 */
public record DiaView(LocalDate data, String rotulo, String rotuloLongo, String relativo, boolean hoje, boolean futuro,
                      Situacao situacao, String situacaoTexto, Tom tom, String descricao, List<Batida> batidas,
                      String faltando, Integer trabalhadoSegundos, Integer saldoSegundos, int previstoSegundos,
                      Marcador marcador, int lancadoSegundos, List<LancamentoBanco> lancamentos,
                      boolean divergenciaRh, boolean ajustado, boolean lancadoAMao, Acoes acoes, Set<Filtro> filtros,
                      RegistroJornadaView registro, List<ComprovanteArquivoView> comprovantes,
                      List<AjusteJornada> ajustes) {

    public DiaView {
        batidas = List.copyOf(batidas);
        lancamentos = List.copyOf(lancamentos);
        filtros = Set.copyOf(filtros);
        comprovantes = List.copyOf(comprovantes);
        ajustes = List.copyOf(ajustes);
    }

    /** Como o dia está. */
    public enum Situacao {
        /** Hoje, com entrada sem saída. */
        EM_ANDAMENTO,
        /** Dia que já passou com entrada sem saída: faltou batida. Fica fora do saldo até corrigir. */
        INCOMPLETO,
        EM_DIA,
        A_FAVOR,
        DEVENDO,
        /** Dia de trabalho que já passou (ou hoje) sem nenhuma batida. Fica fora do saldo. */
        SEM_REGISTRO,
        SEM_EXPEDIENTE,
        FERIADO,
        /** Férias, folga, licença, atestado ou abono. */
        AUSENCIA,
        /** Dia de trabalho que ainda não chegou. */
        FUTURO
    }

    /** A cor que a situação pede (a tela escolhe o tom exato em cada tema). */
    public enum Tom {
        POSITIVO, NEGATIVO, ATENCAO, INFO, NEUTRO
    }

    /** Em quais filtros da lista o dia aparece. */
    public enum Filtro {
        /** Falta batida ou não há registro num dia de trabalho. */
        CORRIGIR,
        /** Saldo diferente de zero ou alguma batida fora da tolerância. */
        DIFERENCA,
        /** Folga, férias, feriado, atestado... */
        FOLGA,
        /** Alguma batida foi incluída ou corrigida à mão. */
        AJUSTADO
    }

    /**
     * @param rotulo      nome que a pessoa usa ("Entrada", "Saída p/ almoço", "Volta do almoço", "Saída")
     * @param horario     a batida real, com segundos
     * @param considerado o horário que entrou na conta (o da grade, quando a diferença coube na tolerância)
     * @param oficial     o horário da grade a que a batida corresponde ({@code null}: batida fora da grade)
     * @param nota        "no horário", "12 min depois do horário"... ({@code null} sem horário de grade)
     * @param comprovanteId PDF arquivado desta batida, se houver
     */
    public record Batida(TipoBatida tipo, String rotulo, LocalTime horario, LocalTime considerado, LocalTime oficial,
                         Integer desvioSegundos, boolean tolerada, boolean ajustada, String nota, Tom tom,
                         UUID comprovanteId, String urlComprovante) {
    }

    /**
     * Marcação que tira a jornada do dia: feriado ou ausência.
     *
     * @param tipo     "FERIADO" ou o tipo da ausência (FERIAS, FOLGA, ATESTADO, LICENCA, ABONO)
     * @param rotulo   "Feriado", "Férias", "Folga"...
     * @param inicio   primeiro dia do período (o próprio dia, num feriado)
     * @param ausenciaId para remover a ausência; {@code null} num feriado
     */
    public record Marcador(String tipo, String rotulo, String descricao, LocalDate inicio, LocalDate fim,
                           UUID ausenciaId, String abrangencia) {

        public boolean feriado() {
            return ausenciaId == null;
        }
    }

    /**
     * O que quem está olhando pode fazer com o dia (tudo falso para quem só consulta).
     *
     * @param corrigir        o dia está incompleto: abre o ajuste das batidas
     * @param ajustar         incluir, corrigir ou remover batidas do dia
     * @param informarBatidas dia de trabalho sem registro: preencher as batidas
     * @param lancarHoras     dia sem expediente ou feriado sem registro: lançar as horas trabalhadas
     * @param editarLancamento o dia foi lançado à mão: editar o lançamento
     * @param marcar          marcar folga, férias, feriado ou outra justificativa
     * @param removerMarcacao tirar a folga/feriado do dia
     * @param excluir         apagar o registro do dia (só sem comprovantes arquivados)
     * @param lancarNoBanco   usar ou somar horas do banco nesta data
     * @param conferirRh      há diferença com o RH para decidir
     */
    public record Acoes(boolean corrigir, boolean ajustar, boolean informarBatidas, boolean lancarHoras,
                        boolean editarLancamento, boolean marcar, boolean removerMarcacao, boolean excluir,
                        boolean lancarNoBanco, boolean conferirRh) {

        public static final Acoes NENHUMA = new Acoes(false, false, false, false, false, false, false, false, false,
                false);
    }
}
