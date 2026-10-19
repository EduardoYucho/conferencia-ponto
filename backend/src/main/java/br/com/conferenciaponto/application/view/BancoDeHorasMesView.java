package br.com.conferenciaponto.application.view;

import br.com.conferenciaponto.application.BancoDeHorasPorMes;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.AvisoLegal;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A tela Banco de horas de um mês: a carga semanal comparada com o horário, quanto o fim de semana compensou, os dias
 * (com a origem de cada batida e os comprovantes), os avisos da lei e a conferência do mês.
 *
 * @param horario       o horário de referência do mês (o de hoje, no mês corrente; senão, o do último dia)
 * @param diferencaDoDia o que cada dia útil fechado levou ao banco pela carga (só os dias com diferença)
 * @param semExpediente os dias do mês sem expediente no horário (sábado e domingo, no horário padrão)
 * @param semRegistro   os dias úteis que já passaram sem nenhum registro (não entram no banco)
 * @param banco         o banco de horas no fim do mês, pelo ciclo (vazio = mês fora dos ciclos)
 * @param excluidos     os dias do mês que tinham batidas e foram excluídos (o histórico fica, o dia não)
 * @param avisosDoHorario o que o horário de referência esbarra na lei (de hoje, ou do início dele, em diante)
 */
public record BancoDeHorasMesView(YearMonth mes, LocalDate hoje, HorarioTrabalho horario, Compensacao compensacao,
                                  AuditoriaMesView auditoria, Map<LocalDate, Integer> diferencaDoDia,
                                  Set<LocalDate> semExpediente, List<LocalDate> semRegistro, List<AvisoLegal> avisos,
                                  ConferenciaDoMesView conferencia, Optional<BancoDeHorasPorMes.NoMes> banco,
                                  List<AjusteJornada> excluidos, List<AvisoLegal> avisosDoHorario) {

    /**
     * A compensação da carga no mês. Diferenças com sinal do banco: negativo = débito.
     *
     * @param diasUteis            dias úteis do mês pelo calendário (sem feriados, férias, folgas)
     * @param diasUteisContados    dias úteis já fechados (os que levaram a diferença ao banco)
     * @param diferencaPrevista    a diferença do mês inteiro (todos os dias úteis)
     * @param diferencaContada     a que já entrou no banco (dias úteis fechados)
     * @param semExpedienteSegundos o saldo dos dias sem expediente trabalhados (fim de semana: tudo é crédito)
     * @param compensado           quanto do fim de semana pagou a diferença do mês
     * @param paraOBanco           o que sobrou do fim de semana (crédito no banco)
     * @param falta                o que ainda falta compensar no mês
     * @param finsDeSemanaRestantes os dias sem expediente que ainda vêm no mês (de hoje em diante), em blocos
     */
    public record Compensacao(int cargaSemanal, int somaDoHorario, int diferencaSemanal, int diferencaPorDia,
                              int diasComExpediente, boolean cargaInformada, int diasUteis, int diasUteisContados,
                              int diferencaPrevista, int diferencaContada, int semExpedienteSegundos, int compensado,
                              int paraOBanco, int falta, List<Bloco> finsDeSemanaRestantes) {

        public boolean temDebito() {
            return diferencaPrevista < 0;
        }
    }

    /** Dias seguidos sem expediente (ex.: sábado e domingo). */
    public record Bloco(LocalDate inicio, LocalDate fim) {
    }
}
