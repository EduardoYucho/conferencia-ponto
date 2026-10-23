package br.com.conferenciaponto.application;

import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A diferença entre a carga semanal e o horário no banco de horas. Quando o horário não fecha a carga (ex.: horário
 * de 40h, carga de 42h), a diferença da semana é dividida pelos dias com expediente (2h ÷ 5 = 24 min) e cada dia
 * útil fechado leva esse débito ao banco; as horas do fim de semana (todo o tempo é crédito) compensam. Feriados,
 * férias, folgas e dias sem registro não levam débito, como já não levam a jornada do dia.
 *
 * <p>O saldo de cada dia (contra a grade, como o RH) não muda: a diferença entra à parte, como os lançamentos no
 * banco. Sem carga informada (o normal), não há diferença e nada muda.
 */
@Component
public class DiferencaDaCarga {

    private final RegrasJornada regras;
    private final RegistroJornadaRepository registros;

    public DiferencaDaCarga(RegrasJornada regras, RegistroJornadaRepository registros) {
        this.regras = regras;
        this.registros = registros;
    }

    /** Algum horário da pessoa tem carga diferente da soma (sem isso, não há o que calcular). */
    public boolean temDiferenca(UUID usuarioId) {
        return regras.horarios(usuarioId).stream().anyMatch(h -> h.diferencaSemanalSegundos() != 0);
    }

    /**
     * O que o dia leva ao banco pela carga: negativo = débito (o horário não fecha a carga), positivo = crédito
     * (o horário passa da carga). Zero em dia que não é útil ou que ainda não fechou.
     */
    public int doDia(UUID usuarioId, LocalDate data, TipoDia tipo, Integer saldoDoDia) {
        if (tipo == null || !tipo.isUtil() || saldoDoDia == null) {
            return 0;
        }
        return -regras.horario(usuarioId, data).diferencaPorDiaSegundos();
    }

    public int doDia(RegistroJornada r) {
        return doDia(r.getUsuarioId(), r.getDataReferencia(), r.getTipoDia(), r.getSaldoDiarioSegundos());
    }

    /** O que um dia útil leva ao banco pelo horário da data (para a previsão do mês, antes de o dia acontecer). */
    public int previstoNoDia(UUID usuarioId, LocalDate data) {
        HorarioTrabalho h = regras.horario(usuarioId, data);
        return h.temExpediente(data) ? -h.diferencaPorDiaSegundos() : 0;
    }

    /** A diferença de cada mês do período (só os meses com diferença). */
    public Map<YearMonth, Integer> porMes(UUID usuarioId, LocalDate inicio, LocalDate fim) {
        Map<YearMonth, Integer> porMes = new TreeMap<>();
        if (!temDiferenca(usuarioId)) {
            return porMes;
        }
        for (RegistroJornada r : registros.listarPorPeriodo(usuarioId, inicio, fim)) {
            int valor = doDia(r);
            if (valor != 0) {
                porMes.merge(YearMonth.from(r.getDataReferencia()), valor, Integer::sum);
            }
        }
        return porMes;
    }
}
