package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.tela.DiaView.Tom;
import br.com.conferenciaponto.application.tela.Horas;
import br.com.conferenciaponto.application.tela.MontadorDeDias.Quem;
import br.com.conferenciaponto.application.tela.NomesDeBatida;
import br.com.conferenciaponto.application.view.ConciliacaoResumoView;
import br.com.conferenciaponto.application.view.ConciliacaoResumoView.Comparacao;
import br.com.conferenciaponto.application.view.ConciliacaoResumoView.Comparativo;
import br.com.conferenciaponto.application.view.DivergenciaView;
import br.com.conferenciaponto.application.view.DivergenciaView.Acoes;
import br.com.conferenciaponto.application.view.DivergenciaView.Hora;
import br.com.conferenciaponto.application.view.DivergenciaView.Lado;
import br.com.conferenciaponto.application.view.DivergenciaView.Marca;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.MarcacaoApurada;
import br.com.conferenciaponto.domain.model.OcorrenciaRh;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.StatusJornada;
import br.com.conferenciaponto.domain.model.StatusRelatorioRh;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;
import br.com.conferenciaponto.domain.port.AusenciaRepository;
import br.com.conferenciaponto.domain.port.DivergenciaRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.port.RelatorioRhRepository.DiaVigente;
import br.com.conferenciaponto.domain.port.RelatorioRhRepository;
import br.com.conferenciaponto.domain.service.ComparadorConciliacaoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * O que a tela "Conferir com o RH" mostra: os relatórios enviados, o resultado da comparação e cada diferença
 * com os dois lados. Além dos dados, entrega as frases prontas e o que cada diferença aceita ({@link Textos}):
 * a tela não decide nem faz conta.
 */
@Service
@Transactional(readOnly = true)
public class ConsultarConciliacaoUseCase {

    /**
     * Tipos que já vêm marcados em "usar o do RH em vários dias": dias que só o RH tem (de antes do uso do
     * sistema), folgas e feriados, e batidas iguais com segundos diferentes. Os outros (batida faltando ou a
     * mais, horário diferente) pedem um olhar dia a dia.
     */
    public static final Set<TipoDivergencia> SUGERIDOS_PARA_VARIOS_DIAS = Collections.unmodifiableSet(
            EnumSet.of(TipoDivergencia.SOMENTE_RH, TipoDivergencia.TIPO_DIA, TipoDivergencia.DIFERENCA_SEGUNDOS));

    private final RelatorioRhRepository relatorios;
    private final DivergenciaRepository divergencias;
    private final RegistroJornadaRepository registros;
    private final RegrasJornada regras;
    private final AusenciaRepository ausencias;

    public ConsultarConciliacaoUseCase(RelatorioRhRepository relatorios, DivergenciaRepository divergencias,
                                       RegistroJornadaRepository registros, RegrasJornada regras,
                                       AusenciaRepository ausencias) {
        this.relatorios = relatorios;
        this.divergencias = divergencias;
        this.registros = registros;
        this.regras = regras;
        this.ausencias = ausencias;
    }

    /** As diferenças como quem só consulta as vê (sem nenhum botão). */
    public List<DivergenciaView> divergencias(UUID usuarioId, StatusDivergencia status, LocalDate inicio,
                                              LocalDate fim) {
        return divergencias(usuarioId, status, inicio, fim, Quem.SO_CONSULTA);
    }

    /**
     * @param status {@code null} = todas
     * @param quem   quem está olhando: decide os botões de cada diferença
     */
    public List<DivergenciaView> divergencias(UUID usuarioId, StatusDivergencia status, LocalDate inicio,
                                              LocalDate fim, Quem quem) {
        return montar(usuarioId, divergencias.listar(usuarioId, status, inicio, fim), quem);
    }

    /** Só as que já têm decisão: usado o do RH, mantido como está ou resolvidas por outro caminho. */
    public List<DivergenciaView> decididas(UUID usuarioId, LocalDate inicio, LocalDate fim, Quem quem) {
        return montar(usuarioId, divergencias.listar(usuarioId, null, inicio, fim).stream()
                .filter(d -> !d.isPendente()).toList(), quem);
    }

    private List<DivergenciaView> montar(UUID usuarioId, List<Divergencia> lista, Quem quem) {
        if (lista.isEmpty()) {
            return List.of();
        }
        LocalDate de = lista.stream().map(Divergencia::data).min(LocalDate::compareTo).orElseThrow();
        LocalDate ate = lista.stream().map(Divergencia::data).max(LocalDate::compareTo).orElseThrow();
        Map<LocalDate, DiaVigente> vigentes = relatorios.vigentes(usuarioId, de, ate).stream()
                .collect(Collectors.toMap(v -> v.dia().data(), Function.identity()));
        Map<LocalDate, RegistroJornada> locais = registros.listarPorPeriodo(usuarioId, de, ate).stream()
                .collect(Collectors.toMap(RegistroJornada::getDataReferencia, Function.identity()));
        Map<UUID, RelatorioRh> porId = relatorios.listar(usuarioId).stream()
                .collect(Collectors.toMap(RelatorioRh::id, Function.identity()));
        List<Ausencia> ausenciasDoPeriodo = ausencias.listarNoPeriodo(usuarioId, de, ate);

        List<DivergenciaView> views = new ArrayList<>(lista.size());
        for (Divergencia d : lista) {
            DiaVigente vigente = vigentes.get(d.data());
            RegistroJornada local = locais.get(d.data());
            RegistroJornadaView localView = local == null ? null : RegistroJornadaView.de(local, regras.motor(local));
            TipoDia tipoDiaLocal = local == null ? regras.classificar(usuarioId, d.data()) : local.getTipoDia();
            DiaRelatorioRh rh = vigente == null ? null : vigente.dia();
            RelatorioRh relatorio = porId.get(vigente == null ? d.relatorioId() : vigente.relatorioId());
            GradeHoraria grade = regras.horario(usuarioId, d.data()).gradeDo(d.data()).orElse(null);
            // qual folga está marcada no dia ("férias", "abono"...), para a frase dizer qual é
            String ausencia = ausenciasDoPeriodo.stream().filter(a -> a.contem(d.data())).findFirst()
                    .map(a -> a.tipo().rotulo().toLowerCase()).orElse(null);
            views.add(new DivergenciaView(d, localView, tipoDiaLocal, rh, relatorio,
                    Textos.divergencia(d, localView, tipoDiaLocal, ausencia, rh, relatorio, grade, quem)));
        }
        return views;
    }

    public ConciliacaoResumoView resumo(UUID usuarioId) {
        List<Divergencia> todas = divergencias.listar(usuarioId, null, null, null);
        Map<TipoDivergencia, Integer> pendentesPorTipo = new EnumMap<>(TipoDivergencia.class);
        Map<TipoDivergencia, Integer> aceitaveisPorTipo = new EnumMap<>(TipoDivergencia.class);
        Map<StatusDivergencia, Integer> porStatus = new EnumMap<>(StatusDivergencia.class);
        for (Divergencia d : todas) {
            porStatus.merge(d.status(), 1, Integer::sum);
            if (d.isPendente()) {
                pendentesPorTipo.merge(d.tipo(), 1, Integer::sum);
                if (d.aceitavel()) {
                    aceitaveisPorTipo.merge(d.tipo(), 1, Integer::sum);
                }
            }
        }

        List<Comparativo> comparativos = new ArrayList<>();
        for (RelatorioRh r : relatorios.listar(usuarioId)) {
            List<DiaRelatorioRh> dias = relatorios.dias(r.id());
            if (dias.isEmpty()) {
                comparativos.add(new Comparativo(r, 0, 0, 0, 0, 0, 0));
                continue;
            }
            Set<LocalDate> datas = dias.stream().map(DiaRelatorioRh::data).collect(Collectors.toSet());
            LocalDate de = Collections.min(datas);
            LocalDate ate = Collections.max(datas);
            int saldoRh = dias.stream().mapToInt(DiaRelatorioRh::saldoSegundos).sum();
            List<SaldoMensal> local = registros.consolidarPeriodo(usuarioId, de, ate);
            int pendentes = (int) todas.stream()
                    .filter(d -> d.isPendente() && !d.data().isBefore(de) && !d.data().isAfter(ate)).count();
            int diferentes = (int) todas.stream()
                    .filter(d -> continuaDiferente(d) && datas.contains(d.data())).count();
            comparativos.add(new Comparativo(r, dias.size(), saldoRh,
                    local.stream().mapToInt(SaldoMensal::saldoMensalSegundos).sum(),
                    local.stream().mapToInt(SaldoMensal::diasEmAberto).sum(), pendentes, diferentes));
        }
        return new ConciliacaoResumoView(comparativos, pendentesPorTipo, porStatus, aceitaveisPorTipo,
                comparacao(usuarioId, todas));
    }

    /** Em quantos dias o RH e o sistema batem: cada data conta uma vez, pelo relatório mais novo que a contém. */
    private Comparacao comparacao(UUID usuarioId, List<Divergencia> todas) {
        Set<LocalDate> conferidos = relatorios.abrangencia(usuarioId)
                .map(a -> relatorios.vigentes(usuarioId, a.inicio(), a.fim()).stream()
                        .map(v -> v.dia().data()).collect(Collectors.toSet()))
                .orElse(Set.of());
        if (conferidos.isEmpty()) {
            return Comparacao.NADA;
        }
        int paraDecidir = 0;
        int mantidos = 0;
        for (Divergencia d : todas) {
            if (!conferidos.contains(d.data())) {
                continue;
            }
            if (d.isPendente()) {
                paraDecidir++;
            } else if (d.status() == StatusDivergencia.MANTIDO_LOCAL) {
                mantidos++;
            }
        }
        return new Comparacao(conferidos.size(), Math.max(0, conferidos.size() - paraDecidir - mantidos), paraDecidir,
                mantidos);
    }

    /** O dia segue diferente do RH: espera decisão ou foi mantido como está (os outros já ficaram iguais). */
    private static boolean continuaDiferente(Divergencia d) {
        return d.isPendente() || d.status() == StatusDivergencia.MANTIDO_LOCAL;
    }

    /**
     * As palavras da tela "Conferir com o RH". Quem lê não é da área: frases curtas, "sistema" e "RH" no lugar
     * de "conferência" e "local", horas do jeito que se fala ("8h 48min"). As regras continuam no domínio
     * (o que difere, se dá para copiar do RH): aqui só se escreve o que elas decidiram.
     */
    public static final class Textos {

        private static final DateTimeFormatter HORA_EXATA = DateTimeFormatter.ofPattern("HH:mm:ss");
        private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        private static final DateTimeFormatter DATA_E_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

        private Textos() {
        }

        // ------------------------------------------------------------------ tipos e situações

        /** Nome do tipo de diferença, como a tela escreve. */
        public static String tipo(TipoDivergencia tipo) {
            return switch (tipo) {
                case TIPO_DIA -> "Folga ou feriado diferente";
                case SOMENTE_RH -> "Dia que só o RH tem";
                case SOMENTE_LOCAL -> "Dia que só o sistema tem";
                case BATIDA_FALTANDO -> "Falta batida no sistema";
                case BATIDA_SOBRANDO -> "Batida a mais no sistema";
                case HORARIO_DIFERENTE -> "Horário diferente";
                case SALDO -> "Saldo diferente";
                case DIFERENCA_SEGUNDOS -> "Segundos diferentes";
            };
        }

        /** Uma frase dizendo o que é cada tipo de diferença. */
        public static String explicacao(TipoDivergencia tipo) {
            return switch (tipo) {
                case TIPO_DIA -> "O RH marcou feriado, férias ou folga num dia que no sistema é de trabalho (ou o contrário).";
                case SOMENTE_RH -> "O RH tem batidas e o sistema não tem registro — por exemplo, dias de antes do uso do sistema.";
                case SOMENTE_LOCAL -> "O sistema tem batidas e o RH não tem nenhuma.";
                case BATIDA_FALTANDO -> "O RH tem uma batida que o sistema não tem — por exemplo, uma batida esquecida que o RH corrigiu.";
                case BATIDA_SOBRANDO -> "O sistema tem uma batida que o RH não tem.";
                case HORARIO_DIFERENTE -> "A mesma batida com mais de 1 minuto de diferença.";
                case SALDO -> "As batidas são as mesmas, mas o saldo do dia é diferente.";
                case DIFERENCA_SEGUNDOS -> "As batidas são as mesmas, com segundos diferentes que mudam o saldo "
                        + "(o comprovante em PDF costuma marcar 1 segundo depois do RH).";
            };
        }

        public static String situacao(StatusDivergencia status) {
            return switch (status) {
                case PENDENTE -> "Para decidir";
                case ACEITO_RH -> "Usado o do RH";
                case MANTIDO_LOCAL -> "Mantido como está no sistema";
                case RESOLVIDA -> "Ficou igual ao RH";
            };
        }

        public static Tom tom(StatusDivergencia status) {
            return switch (status) {
                case PENDENTE -> Tom.ATENCAO;
                case ACEITO_RH, RESOLVIDA -> Tom.POSITIVO;
                case MANTIDO_LOCAL -> Tom.NEUTRO;
            };
        }

        // ------------------------------------------------------------------ resultado da comparação

        /** "O RH e o sistema batem em 20 dos 22 dias conferidos. 2 dias têm diferença para decidir." */
        public static String comparacao(Comparacao c) {
            if (c.diasConferidos() == 0) {
                return null;
            }
            boolean um = c.diasConferidos() == 1;
            if (c.paraDecidir() + c.mantidos() == 0) {
                return um ? "O RH e o sistema batem no dia conferido."
                        : "O RH e o sistema batem em todos os %d dias conferidos.".formatted(c.diasConferidos());
            }
            StringBuilder texto = new StringBuilder();
            if (c.diasIguais() == 0) {
                texto.append(um ? "O RH e o sistema não batem no dia conferido."
                        : "O RH e o sistema não batem em nenhum dos %d dias conferidos.".formatted(c.diasConferidos()));
            } else {
                texto.append("O RH e o sistema batem em %d dos %d dias conferidos."
                        .formatted(c.diasIguais(), c.diasConferidos()));
            }
            if (c.paraDecidir() > 0) {
                texto.append(c.paraDecidir() == 1 ? " 1 dia tem diferença para decidir."
                        : " %d dias têm diferença para decidir.".formatted(c.paraDecidir()));
            }
            if (c.mantidos() > 0) {
                texto.append(c.mantidos() == 1 ? " 1 dia ficou diferente por decisão (mantido como está no sistema)."
                        : " %d dias ficaram diferentes por decisão (mantidos como estão no sistema)."
                        .formatted(c.mantidos()));
            }
            return texto.toString();
        }

        /**
         * O relatório em palavras.
         *
         * @param periodo      "01/06/2026 a 30/06/2026" (até o último dia conferido)
         * @param emitido      "emitido pelo RH em 08/07/2026 às 14:10"
         * @param situacao     "Comparando…", "Comparado" ou "Não foi possível comparar"
         * @param diasIguais   "20 de 22 dias iguais"; {@code null} enquanto não há comparação
         * @param saldoRh      saldo do RH nos dias conferidos ("+ 10h 41min a favor")
         * @param saldoSistema saldo do sistema nos mesmos dias
         * @param diferenca    "saldos iguais" ou "8h 49min de diferença"
         * @param foraDoSaldo  aviso dos dias com batida faltando, que não entram no saldo do sistema
         */
        public record Relatorio(String periodo, String emitido, String situacao, Tom tom, String diasIguais,
                                String saldoRh, String saldoSistema, String diferenca, Boolean saldosIguais,
                                String foraDoSaldo) {
        }

        /** @param c comparativo de saldo ({@code null} na resposta do envio: ainda não há o que comparar) */
        public static Relatorio relatorio(RelatorioRh r, Comparativo c) {
            String periodo = "%s a %s".formatted(DATA.format(r.periodoInicio()), DATA.format(r.ultimoDiaConferido()));
            String emitido = "emitido pelo RH em " + DATA_E_HORA.format(r.emitidoEm());
            String situacao = switch (r.status()) {
                case PROCESSANDO -> "Comparando…";
                case CONCLUIDO -> "Comparado";
                case ERRO -> "Não foi possível comparar";
            };
            Tom tom = switch (r.status()) {
                case PROCESSANDO -> Tom.INFO;
                case CONCLUIDO -> Tom.POSITIVO;
                case ERRO -> Tom.NEGATIVO;
            };
            if (c == null || r.status() != StatusRelatorioRh.CONCLUIDO || c.diasConferidos() == 0) {
                return new Relatorio(periodo, emitido, situacao, tom, null, null, null, null, null, null);
            }
            int iguais = Math.max(0, c.diasConferidos() - c.diasDiferentes());
            String diasIguais = c.diasDiferentes() == 0
                    ? (c.diasConferidos() == 1 ? "o dia conferido está igual"
                            : "todos os %d dias iguais".formatted(c.diasConferidos()))
                    : "%d de %s".formatted(iguais, c.diasConferidos() == 1 ? "1 dia igual"
                            : c.diasConferidos() + " dias iguais");
            long diferenca = Math.abs((long) c.saldoRhSegundos() - c.saldoLocalSegundos());
            String foraDoSaldo = c.diasEmAbertoLocal() == 0 ? null
                    : c.diasEmAbertoLocal() == 1
                            ? "1 dia com batida faltando fica fora do saldo do sistema até ser corrigido (não desconta nada)."
                            : "%d dias com batida faltando ficam fora do saldo do sistema até serem corrigidos (não descontam nada)."
                                    .formatted(c.diasEmAbertoLocal());
            return new Relatorio(periodo, emitido, situacao, tom, diasIguais,
                    Horas.saldoComSentido(c.saldoRhSegundos()), Horas.saldoComSentido(c.saldoLocalSegundos()),
                    diferenca == 0 ? "saldos iguais" : Horas.duracao(diferenca) + " de diferença", diferenca == 0,
                    foraDoSaldo);
        }

        // ------------------------------------------------------------------ uma diferença

        /** @param ausencia a folga marcada hoje no dia, em minúsculas ("férias", "abono"); {@code null} sem folga */
        static DivergenciaView.Tela divergencia(Divergencia d, RegistroJornadaView local, TipoDia tipoDiaLocal,
                                               String ausencia, DiaRelatorioRh rh, RelatorioRh relatorio,
                                               GradeHoraria grade, Quem quem) {
            List<LocalTime> doSistema = local == null ? List.of()
                    : local.marcacoes().stream().map(MarcacaoApurada::real).filter(Objects::nonNull).toList();
            List<LocalTime> doRh = rh == null ? List.of() : rh.horarios();
            Optional<OcorrenciaRh> ocorrencia = rh == null ? Optional.empty() : rh.tipoOcorrencia();
            // feriado vale para todas as pessoas: só o administrador o cadastra
            boolean feriadoSoAdmin = d.tipo() == TipoDivergencia.TIPO_DIA
                    && ocorrencia.filter(o -> o == OcorrenciaRh.FERIADO).isPresent() && !quem.admin();

            Acoes acoes = Acoes.NENHUMA;
            String porQueNao = null;
            String aoUsar = null;
            if (quem.podeEditar() && d.isPendente()) {
                boolean usarRh = d.aceitavel() && rh != null && !feriadoSoAdmin;
                boolean folgaOuFeriado = d.tipo() == TipoDivergencia.TIPO_DIA;
                acoes = new Acoes(usarRh, true, !folgaOuFeriado && rh != null, folgaOuFeriado, false);
                if (usarRh) {
                    aoUsar = aoUsarRh(d, ocorrencia.orElse(null));
                } else {
                    porQueNao = porQueNaoUsarRh(d, rh, feriadoSoAdmin);
                }
            } else if (quem.podeEditar()) {
                acoes = new Acoes(false, false, false, false, true);
            }

            // os segundos só aparecem (e são marcados) quando são eles a diferença do dia
            boolean segundos = d.tipo() == TipoDivergencia.DIFERENCA_SEGUNDOS;
            return new DivergenciaView.Tela(
                    "%s/%d".formatted(Horas.diaLongo(d.data()), d.data().getYear()),
                    tipo(d.tipo()),
                    frase(d, rh, tipoDiaLocal == TipoDia.UTIL ? grade : null, ausencia),
                    impacto(d),
                    ladoDoSistema(local, tipoDiaLocal, ausencia, horas(doSistema, doRh, segundos)),
                    ladoDoRh(rh, horas(doRh, doSistema, segundos)),
                    situacao(d.status()),
                    tom(d.status()),
                    acoes,
                    porQueNao,
                    aoUsar,
                    relatorio == null ? null
                            : "Conforme relatório do RH emitido em " + DATA.format(relatorio.emitidoEm()));
        }

        /** O que está diferente, numa frase (a fotografia dos dois lados de quando a diferença foi encontrada). */
        static String frase(Divergencia d, DiaRelatorioRh rh, GradeHoraria grade, String ausencia) {
            List<LocalTime> r = d.horariosRh();
            List<LocalTime> l = d.horariosLocal();
            return switch (d.tipo()) {
                case TIPO_DIA -> {
                    Optional<OcorrenciaRh> ocorrencia = OcorrenciaRh.de(d.ocorrenciaRh())
                            .filter(o -> o != OcorrenciaRh.OUTRA);
                    if (ocorrencia.isPresent()) {
                        yield "O RH marcou este dia como %s. No sistema ele está como %s."
                                .formatted(nome(ocorrencia.get()), comoEstaNoSistema(d.tipoDiaLocal(), l, ausencia));
                    }
                    String previsto = rh != null && rh.jornadaPrevistaSegundos() > 0
                            ? " (previsto: %s)".formatted(Horas.duracao(rh.jornadaPrevistaSegundos())) : "";
                    yield "O RH conta este dia como dia de trabalho%s. No sistema ele está como %s."
                            .formatted(previsto, comoEstaNoSistema(d.tipoDiaLocal(), l, ausencia));
                }
                case SOMENTE_RH -> r.isEmpty()
                        ? "O RH lançou %s neste dia, sem batidas, e o sistema não tem registro."
                                .formatted(Horas.saldoComSentido(d.saldoRhSegundos() == null ? 0 : d.saldoRhSegundos()))
                        : "O RH tem %s neste dia e o sistema não tem nenhum registro.".formatted(batidas(r.size()));
                case SOMENTE_LOCAL -> "O sistema tem %s neste dia e o RH não tem nenhuma.".formatted(batidas(l.size()));
                case BATIDA_FALTANDO -> {
                    List<LocalTime> faltando = semPar(r, l);
                    if (faltando.isEmpty()) {
                        yield "O RH tem %s neste dia e o sistema tem %d.".formatted(batidas(r.size()), l.size());
                    }
                    yield (faltando.size() == 1 ? "Falta 1 batida no sistema" : "Faltam %d batidas no sistema"
                            .formatted(faltando.size())) + ": o RH tem %s e o sistema não.".formatted(horas(faltando));
                }
                case BATIDA_SOBRANDO -> {
                    List<LocalTime> sobrando = semPar(l, r);
                    if (sobrando.isEmpty()) {
                        yield "O sistema tem %s neste dia e o RH tem %d.".formatted(batidas(l.size()), r.size());
                    }
                    yield (sobrando.size() == 1 ? "O sistema tem 1 batida que o RH não tem"
                            : "O sistema tem %d batidas que o RH não tem".formatted(sobrando.size()))
                            + ": %s.".formatted(horas(sobrando));
                }
                case HORARIO_DIFERENTE -> {
                    List<String> diferentes = new ArrayList<>();
                    for (int i = 0; i < Math.min(r.size(), l.size()); i++) {
                        if (Math.abs(ChronoUnit.SECONDS.between(r.get(i), l.get(i)))
                                > ComparadorConciliacaoService.MESMA_BATIDA_SEGUNDOS) {
                            diferentes.add("%s (%s no sistema e %s no RH)".formatted(
                                    NomesDeBatida.de(grade, i), Horas.hora(l.get(i)), Horas.hora(r.get(i))));
                        }
                    }
                    if (diferentes.isEmpty()) {
                        yield "As batidas têm horários diferentes no RH e no sistema.";
                    }
                    yield (diferentes.size() == 1 ? "1 batida com horário diferente: "
                            : "%d batidas com horário diferente: ".formatted(diferentes.size()))
                            + String.join("; ", diferentes) + ".";
                }
                case SALDO -> r.size() != l.size()
                        ? "As batidas são as mesmas, mas uma delas aparece repetida num dos lados, e por isso o saldo do dia é diferente."
                        : "As batidas são iguais, mas o saldo do dia é diferente: o RH fez a conta de outro jeito.";
                case DIFERENCA_SEGUNDOS -> "As batidas são as mesmas, só que com alguns segundos de diferença — "
                        + "e esses segundos mudam o saldo do dia.";
            };
        }

        /**
         * O tamanho da diferença no saldo do dia. Dia sem registro ou com batida faltando não desconta nada no
         * sistema: fica fora do saldo até ser corrigido.
         */
        static String impacto(Divergencia d) {
            Integer rh = d.saldoRhSegundos();
            Integer sistema = d.saldoLocalSegundos();
            if (rh == null) {
                return null;
            }
            if (sistema != null) {
                long diferenca = Math.abs((long) rh - sistema);
                if (diferenca == 0) {
                    return "O saldo do dia é o mesmo dos dois lados.";
                }
                String noRh = Horas.saldoComSentido(rh);
                String noSistema = Horas.saldoComSentido(sistema);
                // diferença de segundos: escritos em minutos, os dois saldos saem iguais — basta o tamanho
                return noRh.equals(noSistema) ? "Diferença de %s no saldo do dia.".formatted(Horas.duracao(diferenca))
                        : "Diferença de %s no saldo do dia: %s no RH e %s no sistema.".formatted(
                                Horas.duracao(diferenca), noRh, noSistema);
            }
            String noRh = rh == 0 ? "No RH o dia não soma nem desconta."
                    : rh > 0 ? "No RH o dia soma %s a favor.".formatted(Horas.duracao(rh))
                    : "No RH o dia desconta %s.".formatted(Horas.duracao(rh));
            if (!d.horariosLocal().isEmpty()) {
                return "No sistema falta batida neste dia: ele fica fora do saldo até ser corrigido (não desconta nada). "
                        + noRh;
            }
            if (d.tipoDiaLocal() == null || d.tipoDiaLocal() == TipoDia.UTIL) {
                return "No sistema o dia não tem registro: fica fora do saldo até ser corrigido (não desconta nada). "
                        + noRh;
            }
            return "No sistema este dia não entra no saldo. " + noRh;
        }

        private static Lado ladoDoSistema(RegistroJornadaView local, TipoDia tipoDia, String ausencia,
                                          List<Hora> horarios) {
            String folga = ausencia == null ? "folga, férias ou atestado" : ausencia;
            if (local == null) {
                boolean deTrabalho = tipoDia == null || tipoDia == TipoDia.UTIL;
                String titulo = deTrabalho ? "Dia de trabalho, sem batidas" : switch (tipoDia) {
                    case FERIADO -> "Feriado, sem batidas";
                    case AUSENCIA -> comMaiuscula(folga);
                    default -> "Dia sem expediente, sem batidas";
                };
                return new Lado(titulo, List.of(), null,
                        deTrabalho ? "fora do saldo até ser corrigido" : "não entra no saldo",
                        deTrabalho ? Tom.ATENCAO : Tom.NEUTRO);
            }
            String titulo = local.registroManual() ? "Horas lançadas à mão" : switch (local.tipoDia()) {
                case UTIL -> horarios.isEmpty() ? "Dia de trabalho, sem batidas" : null;
                case FERIADO -> "Trabalho em feriado";
                case AUSENCIA -> "Trabalho em dia de " + folga;
                case FIM_DE_SEMANA -> "Trabalho em dia sem expediente";
            };
            Integer saldo = local.status() == StatusJornada.FECHADA ? local.saldoDiarioSegundos() : null;
            return new Lado(titulo, horarios,
                    local.segundosTrabalhados() > 0 ? Horas.duracao(local.segundosTrabalhados()) : null,
                    saldo == null ? "falta batida: fora do saldo até ser corrigido" : Horas.saldoComSentido(saldo),
                    saldo == null ? Tom.ATENCAO : tomDoSaldo(saldo));
        }

        private static Lado ladoDoRh(DiaRelatorioRh rh, List<Hora> horarios) {
            if (rh == null) {
                return new Lado("O relatório deste dia foi removido", List.of(), null, null, Tom.NEUTRO);
            }
            String titulo = rh.ocorrencia() != null ? comMaiuscula(rh.ocorrencia())
                    : horarios.isEmpty() ? "Sem batidas" : null;
            return new Lado(titulo, horarios,
                    rh.segundosTrabalhados() > 0 ? Horas.duracao(rh.segundosTrabalhados()) : null,
                    rh.saldoSegundos() == 0 && horarios.isEmpty() ? "não soma nem desconta"
                            : Horas.saldoComSentido(rh.saldoSegundos()),
                    tomDoSaldo(rh.saldoSegundos()));
        }

        /**
         * As batidas de um lado, marcadas pelo que o outro lado tem (sem batidas do outro lado, nada a marcar).
         * Batidas a até 1 minuto são a mesma batida: só são marcadas (e escritas com segundos) quando a
         * diferença do dia é justamente de segundos.
         */
        private static List<Hora> horas(List<LocalTime> lista, List<LocalTime> outroLado, boolean mostrarSegundos) {
            return lista.stream().map(h -> {
                String exato = HORA_EXATA.format(h);
                if (outroLado.isEmpty()) {
                    return new Hora(Horas.hora(h), exato, null);
                }
                if (outroLado.contains(h)) {
                    return new Hora(Horas.hora(h), exato, Marca.IGUAL);
                }
                if (outroLado.stream().noneMatch(o -> mesmaBatida(h, o))) {
                    return new Hora(Horas.hora(h), exato, Marca.DIFERENTE);
                }
                return mostrarSegundos ? new Hora(exato, exato, Marca.SEGUNDOS)
                        : new Hora(Horas.hora(h), exato, Marca.IGUAL);
            }).toList();
        }

        private static String aoUsarRh(Divergencia d, OcorrenciaRh ocorrencia) {
            if (d.tipo() != TipoDivergencia.TIPO_DIA) {
                return "Ao usar o do RH, as batidas do dia ficam iguais às do relatório e a troca fica no histórico do dia. "
                        + "Batida com comprovante (PDF) nunca é apagada: só os segundos são alinhados.";
            }
            if (ocorrencia == OcorrenciaRh.FERIADO) {
                return "Ao usar o do RH, este dia vira feriado no sistema — para todas as pessoas.";
            }
            if (ocorrencia == null || ocorrencia == OcorrenciaRh.OUTRA) {
                return "Ao usar o do RH, o dia fica marcado no sistema como está no relatório.";
            }
            return "Ao usar o do RH, este dia fica marcado como %s no sistema, junto com os dias vizinhos que o RH marcou do mesmo jeito."
                    .formatted(nome(ocorrencia));
        }

        /** Por que "Usar o do RH" não está disponível (o comparador decidiu; aqui só se explica). */
        private static String porQueNaoUsarRh(Divergencia d, DiaRelatorioRh rh, boolean feriadoSoAdmin) {
            if (rh == null) {
                return "O relatório do RH deste dia não está mais disponível. Use “Comparar de novo”.";
            }
            if (feriadoSoAdmin) {
                return "Feriado vale para todas as pessoas: peça ao administrador para cadastrá-lo em Folgas e feriados. "
                        + "Depois disso, esta diferença some sozinha.";
            }
            List<LocalTime> r = d.horariosRh();
            String motivo = switch (d.tipo()) {
                case TIPO_DIA -> "Para ficar igual ao RH, remova o feriado ou a folga deste dia em Folgas e feriados.";
                case SOMENTE_LOCAL -> "Usar o do RH apagaria o dia inteiro. Se for o caso, apague o registro do dia em Meu ponto.";
                case SALDO -> r.size() != d.horariosLocal().size()
                        ? "A diferença vem de uma batida repetida: use “Manter o meu” ou ajuste as batidas à mão."
                        : "As batidas já são iguais às do RH: não há o que copiar.";
                default -> {
                    if (r.isEmpty()) {
                        yield "O RH não tem batidas neste dia para copiar: confira o motivo com o RH.";
                    }
                    if (r.size() > TipoBatida.MAXIMO) {
                        yield "O RH tem %d batidas e o sistema guarda até %d por dia: ajuste as batidas à mão."
                                .formatted(r.size(), TipoBatida.MAXIMO);
                    }
                    if (r.size() % 2 == 1) {
                        yield "O RH tem um número ímpar de batidas (%d): copiar deixaria o dia com batida faltando. Ajuste as batidas à mão."
                                .formatted(r.size());
                    }
                    for (int i = 1; i < r.size(); i++) {
                        if (!r.get(i).isAfter(r.get(i - 1))) {
                            yield "O RH tem batidas repetidas ou fora de ordem: ajuste as batidas à mão.";
                        }
                    }
                    yield null;
                }
            };
            // motivo que só o comparador conhece: vai como ele escreveu
            return motivo != null ? motivo : d.motivoNaoAceitavel();
        }

        // ------------------------------------------------------------------ peças

        private static boolean mesmaBatida(LocalTime a, LocalTime b) {
            return Math.abs(ChronoUnit.SECONDS.between(a, b)) <= ComparadorConciliacaoService.MESMA_BATIDA_SEGUNDOS;
        }

        /** Batidas de {@code origem} sem nenhuma em {@code outra} a até 1 minuto. */
        private static List<LocalTime> semPar(List<LocalTime> origem, List<LocalTime> outra) {
            return origem.stream().filter(h -> outra.stream().noneMatch(o -> mesmaBatida(h, o))).toList();
        }

        private static Tom tomDoSaldo(int segundos) {
            return segundos == 0 ? Tom.NEUTRO : segundos > 0 ? Tom.POSITIVO : Tom.NEGATIVO;
        }

        private static String batidas(int quantidade) {
            return quantidade == 1 ? "1 batida" : quantidade + " batidas";
        }

        /** "13:00", "12:00 e 13:00", "08:00, 12:00 e 13:00" */
        private static String horas(List<LocalTime> horarios) {
            List<String> textos = horarios.stream().map(Horas::hora).toList();
            if (textos.size() <= 1) {
                return String.join("", textos);
            }
            return String.join(", ", textos.subList(0, textos.size() - 1)) + " e " + textos.get(textos.size() - 1);
        }

        private static String nome(OcorrenciaRh ocorrencia) {
            return switch (ocorrencia) {
                case FERIADO -> "feriado";
                case FERIAS -> "férias";
                case FOLGA -> "folga";
                case ATESTADO -> "atestado";
                case LICENCA -> "licença";
                case OUTRA -> "outra ocorrência";
            };
        }

        private static String comoEstaNoSistema(TipoDia tipoDia, List<LocalTime> batidasDoSistema, String ausencia) {
            if (tipoDia == null || tipoDia == TipoDia.UTIL) {
                return batidasDoSistema.isEmpty() ? "dia de trabalho, sem batidas"
                        : "dia de trabalho, com " + batidas(batidasDoSistema.size());
            }
            return switch (tipoDia) {
                case FERIADO -> "feriado";
                case AUSENCIA -> ausencia == null ? "folga, férias ou atestado" : ausencia;
                default -> "dia sem expediente";
            };
        }

        private static String comMaiuscula(String texto) {
            return texto.isEmpty() ? texto : Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
        }
    }
}
