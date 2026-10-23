package br.com.conferenciaponto.domain.service;

import br.com.conferenciaponto.domain.model.AvisoLegal;
import br.com.conferenciaponto.domain.model.AvisoLegal.Nivel;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * As regras da lei sobre a duração do trabalho que o sistema confere, com as datas em que cada uma vale:
 * <ul>
 *   <li>até 15/12/2026: 44 horas por semana e 1 folga semanal (Constituição, art. 7º, XIII e XV);</li>
 *   <li>de 16/12/2026: 42 horas e 2 folgas por semana, uma de preferência no domingo (EC 141/2026, que acabou
 *       com a escala 6x1); jornada normal de 8 horas por dia (com acordo ou convenção coletiva, até 8h24 para
 *       fechar 42 horas em 5 dias); regime de compensação só por acordo ou convenção coletiva, com pelo menos 1
 *       folga em toda semana e média de 2 por semana no mês;</li>
 *   <li>de 16/12/2027: 40 horas por semana.</li>
 * </ul>
 * E as da CLT que valem o tempo todo: até 2 horas extras por dia (10 horas no total, art. 59), intervalo de 1 a 2
 * horas acima de 6 horas e de 15 minutos de 4 a 6 horas (art. 71) e 11 horas de descanso entre um dia e outro
 * (art. 66).
 *
 * <p>Tudo aqui é aviso: há acordos, convenções e exceções (ex.: quem tem diploma superior e ganha 2,5 vezes o teto
 * do INSS fica fora do controle de jornada). Quem decide é o RH.
 */
public final class LeiDaJornada {

    public static final LocalDate EC_141_42_HORAS = LocalDate.of(2026, 12, 16);
    public static final LocalDate EC_141_40_HORAS = LocalDate.of(2027, 12, 16);

    static final int HORA = 3600;
    static final int LIMITE_DIARIO_COM_EXTRAS = 10 * HORA;
    static final int JORNADA_NORMAL_DIARIA = 8 * HORA;
    static final int INTERJORNADA = 11 * HORA;
    static final int INTERVALO_MINIMO = HORA;
    static final int INTERVALO_MAXIMO = 2 * HORA;
    static final int INTERVALO_CURTO = 15 * 60;
    static final int JORNADA_COM_INTERVALO_DE_1H = 6 * HORA;
    static final int JORNADA_COM_INTERVALO_DE_15MIN = 4 * HORA;
    static final int DIAS_SEGUIDOS_SEM_FOLGA = 7;

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM");
    private static final String CONSTITUICAO = "Constituição, art. 7º, XIII";
    private static final String EC_141 = "EC 141/2026";

    /**
     * Uma fase da lei.
     *
     * @param limiteSemanalSegundos horas normais por semana
     * @param folgasPorSemana       folgas (descanso semanal remunerado) por semana
     * @param jornadaDiariaSegundos jornada normal por dia, quando a fase limita (null = só o limite das extras)
     * @param textoDoDia            o que vale por dia nessa fase (para a mensagem)
     */
    public record Fase(LocalDate desde, int limiteSemanalSegundos, int folgasPorSemana, Integer jornadaDiariaSegundos,
                       String fundamento, String textoDoDia) {

        public Fase {
            Objects.requireNonNull(desde, "desde");
            Objects.requireNonNull(fundamento, "fundamento");
        }

        public boolean duasFolgas() {
            return folgasPorSemana >= 2;
        }
    }

    /** Um dia com trabalho registrado: as batidas em ordem (pode faltar a última saída) e o tempo trabalhado. */
    public record DiaTrabalhado(LocalDate data, List<LocalTime> batidas, int segundosTrabalhados) {

        public DiaTrabalhado {
            Objects.requireNonNull(data, "data");
            batidas = batidas == null ? List.of() : batidas.stream().sorted().toList();
        }

        public boolean trabalhou() {
            return segundosTrabalhados > 0 || !batidas.isEmpty();
        }

        boolean fechado() {
            return !batidas.isEmpty() && batidas.size() % 2 == 0;
        }
    }

    private final List<Fase> fases;

    public LeiDaJornada(List<Fase> fases) {
        if (fases == null || fases.isEmpty()) {
            throw new IllegalArgumentException("A lei precisa de pelo menos uma fase");
        }
        this.fases = fases.stream().sorted(Comparator.comparing(Fase::desde)).toList();
    }

    /** As regras de hoje, com as datas da EC 141/2026 (publicada em 16/10/2026). */
    public static LeiDaJornada vigente() {
        return new LeiDaJornada(List.of(
                new Fase(LocalDate.of(1988, 10, 5), 44 * HORA, 1, null, CONSTITUICAO,
                        null),
                new Fase(EC_141_42_HORAS, 42 * HORA, 2, JORNADA_NORMAL_DIARIA, EC_141,
                        "até 8 horas por dia (com acordo ou convenção coletiva, até 8h24 para fechar 42 horas em 5 dias)"),
                new Fase(EC_141_40_HORAS, 40 * HORA, 2, JORNADA_NORMAL_DIARIA, EC_141,
                        "até 8 horas por dia")));
    }

    public List<Fase> fases() {
        return fases;
    }

    /** A fase que vale na data. */
    public Fase faseEm(LocalDate data) {
        Fase atual = fases.get(0);
        for (Fase f : fases) {
            if (!f.desde().isAfter(data)) {
                atual = f;
            }
        }
        return atual;
    }

    /** A fase da data de início e as que começam depois dela, antes do fim (exclusivo; null = sem fim). */
    public List<Fase> fasesEntre(LocalDate inicio, LocalDate fimExclusivo) {
        List<Fase> lista = new ArrayList<>();
        lista.add(faseEm(inicio));
        for (Fase f : fases) {
            if (f.desde().isAfter(inicio) && (fimExclusivo == null || f.desde().isBefore(fimExclusivo))) {
                lista.add(f);
            }
        }
        return lista;
    }

    // ------------------------------------------------------------------ o horário

    /**
     * Confere o horário para o período em que ele vale: de {@code inicio} (a vigência, ou hoje se ela já
     * começou) até o próximo horário ({@code fimExclusivo}; null = sem fim). Uma fase da lei que começa dentro do
     * período aparece como aviso ("a partir de 16/12/2026...").
     */
    public List<AvisoLegal> avaliarHorario(HorarioTrabalho horario, LocalDate inicio, LocalDate fimExclusivo) {
        List<AvisoLegal> avisos = new ArrayList<>();
        for (DayOfWeek dia : DayOfWeek.values()) {
            GradeHoraria grade = horario.dias().get(dia);
            if (grade != null) {
                avaliarGrade(nomeDoDia(dia), grade, avisos);
            }
        }
        avaliarInterjornadaDoHorario(horario, avisos);

        List<Fase> periodo = fasesEntre(inicio, fimExclusivo);
        int semanal = Math.max(horario.cargaSemanalSegundos(), horario.somaSemanalSegundos());
        int folgas = 7 - horario.diasComExpediente();
        boolean avisouAcordo = false;
        java.util.Set<String> futurosAvisados = new java.util.HashSet<>();
        for (int i = 0; i < periodo.size(); i++) {
            Fase fase = periodo.get(i);
            boolean futura = i > 0 || fase.desde().isAfter(inicio);
            String quando = futura ? "a partir de " + DATA.format(fase.desde()) : null;
            if (semanal > fase.limiteSemanalSegundos()) {
                String quanto = horario.cargaSemanalSegundos() >= horario.somaSemanalSegundos()
                        ? "A carga de %s por semana".formatted(duracao(semanal))
                        : "O horário soma %s por semana e".formatted(duracao(semanal));
                avisos.add(new AvisoLegal(futura ? Nivel.AVISO : Nivel.ALERTA, "SEMANA_ACIMA_DO_LIMITE",
                        futura ? fase.desde() : null,
                        futura
                                ? "%s passa do limite de %s que vale %s. Cadastre um horário novo a partir dessa data."
                                        .formatted(quanto, duracao(fase.limiteSemanalSegundos()), quando)
                                : "%s passa do limite de %s por semana.".formatted(quanto, duracao(fase.limiteSemanalSegundos())),
                        fase.fundamento()));
            }
            if (folgas < 1 && i == 0) {
                avisos.add(new AvisoLegal(Nivel.ALERTA, "HORARIO_SEM_FOLGA", null,
                        "O horário tem expediente nos 7 dias: falta a folga semanal.",
                        "Constituição, art. 7º, XV; CLT, art. 67"));
            } else if (folgas < fase.folgasPorSemana()) {
                avisos.add(new AvisoLegal(futura ? Nivel.AVISO : Nivel.ALERTA, "HORARIO_UMA_FOLGA",
                        futura ? fase.desde() : null,
                        "%s são %d folgas por semana (uma de preferência no domingo); o horário tem %d."
                                .formatted(futura ? capitalizar(quando) : "Agora", fase.folgasPorSemana(), folgas),
                        fase.fundamento()));
            }
            if (fase.jornadaDiariaSegundos() != null) {
                List<String> longos = new ArrayList<>();
                for (DayOfWeek dia : DayOfWeek.values()) {
                    GradeHoraria grade = horario.dias().get(dia);
                    if (grade != null && grade.cargaHorariaSegundos() > fase.jornadaDiariaSegundos()
                            && grade.cargaHorariaSegundos() <= LIMITE_DIARIO_COM_EXTRAS) {
                        longos.add(nomeDoDia(dia));
                    }
                }
                if (!longos.isEmpty()) {
                    avisos.add(new AvisoLegal(Nivel.AVISO, "DIA_ACIMA_DA_JORNADA", futura ? fase.desde() : null,
                            "%s, a jornada normal é de %s: %s %s disso."
                                    .formatted(futura ? capitalizar(quando) : "Agora", fase.textoDoDia(),
                                            juntar(longos), longos.size() == 1 ? "passa" : "passam"),
                            fase.fundamento()));
                }
            }
            if (horario.diferencaSemanalSegundos() > 0 && fase.duasFolgas() && !avisouAcordo) {
                avisouAcordo = true;
                avisos.add(new AvisoLegal(Nivel.AVISO, "COMPENSACAO_COM_ACORDO", futura ? fase.desde() : null,
                        ("%s, trabalhar no fim de semana para completar a carga tira uma das 2 folgas da semana: só vale "
                                + "com acordo ou convenção coletiva, com pelo menos 1 folga em toda semana e média de 2 por "
                                + "semana no mês.").formatted(futura ? capitalizar(quando) : "Agora"),
                        fase.fundamento()));
            }
        }
        // de uma fase que ainda vai valer, cada regra aparece uma vez (a primeira data em que passa)
        List<AvisoLegal> filtrados = new ArrayList<>();
        for (AvisoLegal a : avisos) {
            if (a.data() == null || futurosAvisados.add(a.codigo())) {
                filtrados.add(a);
            }
        }
        avisos = filtrados;
        if (horario.diferencaSemanalSegundos() > 0 && !periodo.get(0).duasFolgas()) {
            avisos.add(new AvisoLegal(Nivel.INFO, "COMPENSACAO_NO_MES", null,
                    "As horas que faltam na semana precisam ser compensadas no mesmo mês (por acordo, que pode ser "
                            + "individual); o que não for compensado fica como débito no banco de horas.",
                    "CLT, art. 59, §§ 5º e 6º"));
        }
        return avisos;
    }

    private static void avaliarGrade(String dia, GradeHoraria grade, List<AvisoLegal> avisos) {
        int jornada = grade.cargaHorariaSegundos();
        if (jornada > LIMITE_DIARIO_COM_EXTRAS) {
            avisos.add(new AvisoLegal(Nivel.ALERTA, "DIA_ACIMA_DE_10H", null,
                    "%s tem %s de trabalho: passa de 10 horas por dia (8 horas mais 2 extras).".formatted(capitalizar(dia),
                            duracao(jornada)), "CLT, art. 59"));
        }
        List<GradeHoraria.Periodo> periodos = grade.periodos();
        int maiorIntervalo = 0;
        for (int i = 1; i < periodos.size(); i++) {
            int intervalo = (int) ChronoUnit.SECONDS.between(periodos.get(i - 1).saida(), periodos.get(i).entrada());
            maiorIntervalo = Math.max(maiorIntervalo, intervalo);
            if (intervalo > INTERVALO_MAXIMO) {
                avisos.add(new AvisoLegal(Nivel.AVISO, "INTERVALO_ACIMA_DE_2H", null,
                        "%s tem intervalo de %s: acima de 2 horas, só com acordo escrito ou convenção coletiva."
                                .formatted(capitalizar(dia), duracao(intervalo)), "CLT, art. 71"));
            }
        }
        int maiorPeriodo = periodos.stream()
                .mapToInt(p -> (int) ChronoUnit.SECONDS.between(p.entrada(), p.saida())).max().orElse(0);
        if (jornada > JORNADA_COM_INTERVALO_DE_1H && maiorIntervalo < INTERVALO_MINIMO) {
            avisos.add(new AvisoLegal(Nivel.ALERTA, "INTERVALO_CURTO", null,
                    "%s tem %s de trabalho e %s: acima de 6 horas, o intervalo precisa ser de pelo menos 1 hora."
                            .formatted(capitalizar(dia), duracao(jornada),
                                    maiorIntervalo == 0 ? "nenhum intervalo" : "intervalo de " + duracao(maiorIntervalo)),
                    "CLT, art. 71"));
        } else if (jornada > JORNADA_COM_INTERVALO_DE_15MIN && jornada <= JORNADA_COM_INTERVALO_DE_1H
                && maiorPeriodo > JORNADA_COM_INTERVALO_DE_15MIN && maiorIntervalo < INTERVALO_CURTO) {
            avisos.add(new AvisoLegal(Nivel.AVISO, "INTERVALO_15_MINUTOS", null,
                    "%s tem %s de trabalho: de 4 a 6 horas, o intervalo precisa ser de pelo menos 15 minutos."
                            .formatted(capitalizar(dia), duracao(jornada)), "CLT, art. 71, § 1º"));
        }
    }

    private static void avaliarInterjornadaDoHorario(HorarioTrabalho horario, List<AvisoLegal> avisos) {
        for (DayOfWeek dia : DayOfWeek.values()) {
            GradeHoraria hoje = horario.dias().get(dia);
            GradeHoraria amanha = horario.dias().get(dia.plus(1));
            if (hoje == null || amanha == null) {
                continue;
            }
            LocalTime saida = hoje.periodos().get(hoje.periodos().size() - 1).saida();
            LocalTime entrada = amanha.periodos().get(0).entrada();
            int descanso = (int) (ChronoUnit.SECONDS.between(saida, LocalTime.MAX) + 1
                    + ChronoUnit.SECONDS.between(LocalTime.MIDNIGHT, entrada));
            if (descanso < INTERJORNADA) {
                avisos.add(new AvisoLegal(Nivel.ALERTA, "INTERJORNADA", null,
                        "Da saída de %s (%s) à entrada de %s (%s) são %s de descanso: o mínimo é de 11 horas."
                                .formatted(nomeDoDia(dia), hhmm(saida), nomeDoDia(dia.plus(1)), hhmm(entrada),
                                        duracao(descanso)), "CLT, art. 66"));
            }
        }
    }

    // ------------------------------------------------------------------ os dias trabalhados

    /**
     * Confere os dias trabalhados de {@code inicio} a {@code fim} (só até hoje): limite do dia, intervalo,
     * descanso entre um dia e outro e as folgas. {@code dias} pode trazer os 6 dias antes do início (para a
     * sequência sem folga e a semana que começa no mês anterior); os que não vêm são dias sem trabalho.
     */
    public List<AvisoLegal> avaliarDias(List<DiaTrabalhado> dias, LocalDate inicio, LocalDate fim, LocalDate hoje) {
        Map<LocalDate, DiaTrabalhado> porData = new TreeMap<>();
        dias.forEach(d -> porData.put(d.data(), d));
        LocalDate ultimo = fim.isAfter(hoje) ? hoje : fim;
        List<AvisoLegal> avisos = new ArrayList<>();
        if (ultimo.isBefore(inicio)) {
            return avisos;
        }
        for (LocalDate data = inicio; !data.isAfter(ultimo); data = data.plusDays(1)) {
            DiaTrabalhado dia = porData.get(data);
            if (dia == null || !dia.trabalhou()) {
                continue;
            }
            avaliarDia(dia, avisos);
            DiaTrabalhado anterior = porData.get(data.minusDays(1));
            if (anterior != null && !anterior.batidas().isEmpty() && !dia.batidas().isEmpty() && anterior.fechado()) {
                LocalTime saida = anterior.batidas().get(anterior.batidas().size() - 1);
                LocalTime entrada = dia.batidas().get(0);
                int descanso = (int) (ChronoUnit.SECONDS.between(saida, LocalTime.MAX) + 1
                        + ChronoUnit.SECONDS.between(LocalTime.MIDNIGHT, entrada));
                if (descanso < INTERJORNADA) {
                    avisos.add(new AvisoLegal(Nivel.AVISO, "DIA_INTERJORNADA", data,
                            "%s: da saída do dia anterior (%s) à entrada (%s) foram %s de descanso; o mínimo é de 11 horas."
                                    .formatted(DIA.format(data), hhmm(saida), hhmm(entrada), duracao(descanso)),
                            "CLT, art. 66"));
                }
            }
        }
        avaliarFolgas(porData, inicio, ultimo, fim, avisos);
        return avisos;
    }

    private static void avaliarDia(DiaTrabalhado dia, List<AvisoLegal> avisos) {
        int trabalhado = dia.segundosTrabalhados();
        if (trabalhado > LIMITE_DIARIO_COM_EXTRAS) {
            avisos.add(new AvisoLegal(Nivel.AVISO, "DIA_ACIMA_DE_10H", dia.data(),
                    "%s: %s de trabalho, mais de 10 horas no dia (8 horas mais 2 extras)."
                            .formatted(DIA.format(dia.data()), duracao(trabalhado)), "CLT, art. 59"));
        }
        if (!dia.fechado() || trabalhado <= JORNADA_COM_INTERVALO_DE_1H) {
            return;
        }
        int maiorIntervalo = 0;
        List<LocalTime> b = dia.batidas();
        for (int i = 2; i < b.size(); i += 2) {
            maiorIntervalo = Math.max(maiorIntervalo, (int) ChronoUnit.SECONDS.between(b.get(i - 1), b.get(i)));
        }
        if (maiorIntervalo < INTERVALO_MINIMO) {
            avisos.add(new AvisoLegal(Nivel.AVISO, "DIA_INTERVALO_CURTO", dia.data(),
                    "%s: %s de trabalho com %s; acima de 6 horas, o intervalo é de pelo menos 1 hora."
                            .formatted(DIA.format(dia.data()), duracao(trabalhado),
                                    maiorIntervalo == 0 ? "nenhum intervalo" : "intervalo de " + duracao(maiorIntervalo)),
                    "CLT, art. 71"));
        }
    }

    private void avaliarFolgas(Map<LocalDate, DiaTrabalhado> porData, LocalDate inicio, LocalDate ultimo, LocalDate fim,
                               List<AvisoLegal> avisos) {
        // até 15/12/2026: no máximo 6 dias seguidos de trabalho (a folga semanal a cada 7 dias)
        LocalDate comecoDaSequencia = null;
        int seguidos = 0;
        for (LocalDate data = inicio.minusDays(DIAS_SEGUIDOS_SEM_FOLGA - 1); !data.isAfter(ultimo); data = data.plusDays(1)) {
            DiaTrabalhado dia = porData.get(data);
            if (dia != null && dia.trabalhou()) {
                if (seguidos == 0) {
                    comecoDaSequencia = data;
                }
                seguidos++;
                if (seguidos == DIAS_SEGUIDOS_SEM_FOLGA && !data.isBefore(inicio) && !faseEm(data).duasFolgas()) {
                    avisos.add(new AvisoLegal(Nivel.ALERTA, "SEM_FOLGA_7_DIAS", comecoDaSequencia,
                            "De %s a %s: 7 dias seguidos com trabalho, sem a folga semanal."
                                    .formatted(DIA.format(comecoDaSequencia), DIA.format(data)),
                            "Constituição, art. 7º, XV; CLT, art. 67"));
                }
            } else {
                seguidos = 0;
            }
        }

        // de 16/12/2026: 2 folgas por semana (segunda a domingo); com acordo coletivo, pelo menos 1 e média de 2 no mês
        int folgasNoMes = 0;
        int diasNoMes = 0;
        for (LocalDate segunda = inicio.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
             !segunda.isAfter(ultimo); segunda = segunda.plusWeeks(1)) {
            LocalDate domingo = segunda.plusDays(6);
            if (domingo.isAfter(ultimo) || domingo.isBefore(inicio) || !faseEm(segunda).duasFolgas()) {
                continue; // semana que ainda não terminou, que terminou no mês anterior ou antes da regra nova
            }
            int folgas = 0;
            for (LocalDate d = segunda; !d.isAfter(domingo); d = d.plusDays(1)) {
                DiaTrabalhado dia = porData.get(d);
                if (dia == null || !dia.trabalhou()) {
                    folgas++;
                }
            }
            if (folgas == 0) {
                avisos.add(new AvisoLegal(Nivel.ALERTA, "SEMANA_SEM_FOLGA", segunda,
                        "Semana de %s a %s sem nenhuma folga.".formatted(DIA.format(segunda), DIA.format(domingo)),
                        EC_141 + "; Constituição, art. 7º, XV"));
            } else if (folgas == 1) {
                avisos.add(new AvisoLegal(Nivel.AVISO, "SEMANA_COM_UMA_FOLGA", segunda,
                        "Semana de %s a %s com 1 folga: só vale com acordo ou convenção coletiva e média de 2 folgas por semana no mês."
                                .formatted(DIA.format(segunda), DIA.format(domingo)), EC_141));
            }
        }
        if (!ultimo.isBefore(fim)) { // o mês terminou: a média das folgas
            for (LocalDate d = inicio; !d.isAfter(fim); d = d.plusDays(1)) {
                if (faseEm(d).duasFolgas()) {
                    diasNoMes++;
                    DiaTrabalhado dia = porData.get(d);
                    if (dia == null || !dia.trabalhou()) {
                        folgasNoMes++;
                    }
                }
            }
            if (diasNoMes >= 7 && folgasNoMes * 7.0 / diasNoMes < 2.0 - 1e-9) {
                avisos.add(new AvisoLegal(Nivel.ALERTA, "MEDIA_DE_FOLGAS", inicio,
                        "No mês, %d folga(s) em %d dias: média de %s por semana, abaixo das 2 da regra nova."
                                .formatted(folgasNoMes, diasNoMes, "%.1f".formatted(folgasNoMes * 7.0 / diasNoMes)
                                        .replace('.', ',')), EC_141));
            }
        }
    }

    // ------------------------------------------------------------------ textos

    /** "8h48", "10h", "0h15". */
    public static String duracao(long segundos) {
        long minutos = Math.round(Math.abs(segundos) / 60.0);
        long h = minutos / 60;
        long m = minutos % 60;
        return m == 0 ? h + "h" : "%dh%02d".formatted(h, m);
    }

    private static String hhmm(LocalTime t) {
        return "%02d:%02d".formatted(t.getHour(), t.getMinute());
    }

    static String nomeDoDia(DayOfWeek dia) {
        return switch (dia) {
            case MONDAY -> "segunda";
            case TUESDAY -> "terça";
            case WEDNESDAY -> "quarta";
            case THURSDAY -> "quinta";
            case FRIDAY -> "sexta";
            case SATURDAY -> "sábado";
            case SUNDAY -> "domingo";
        };
    }

    private static String capitalizar(String texto) {
        return texto == null || texto.isEmpty() ? texto : Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
    }

    private static String juntar(List<String> itens) {
        if (itens.size() == 1) {
            return itens.get(0);
        }
        return String.join(", ", itens.subList(0, itens.size() - 1)) + " e " + itens.get(itens.size() - 1);
    }
}
