package br.com.conferenciaponto.application.planilha;

import br.com.conferenciaponto.application.ConsolidacaoBancoHoras;
import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.TextoDuracao;
import br.com.conferenciaponto.application.planilha.Estilo.Alinhamento;
import br.com.conferenciaponto.application.planilha.Estilo.Cor;
import br.com.conferenciaponto.application.usecase.ConsultarAuditoriaUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarCicloBancoUseCase;
import br.com.conferenciaponto.application.view.AuditoriaMesView;
import br.com.conferenciaponto.application.view.CicloBancoView;
import br.com.conferenciaponto.application.view.MesJornadaView;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.MarcacaoApurada;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.model.StatusJornada;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Monta a planilha de conferência de uma pessoa: todos os meses, do primeiro ao último com registro (jornada
 * ou lançamento no banco), dia a dia — inclusive os dias sem registro —, com os totais do mês em fórmulas, e a
 * aba de resumo com o banco de horas.
 *
 * <p>Os totais batem com os do sistema por construção: como no saldo do mês, só entram os dias fechados (um
 * dia em andamento ou incompleto fica com as horas em branco).
 */
@Service
@Transactional(readOnly = true)
public class MontadorPlanilhaConferencia {

    /** Limite de abas de mês (as mais recentes): 10 anos. */
    static final int MAXIMO_MESES = 120;

    /** Linhas do topo da aba do mês: título, subtítulo, espaço, cabeçalho e totais. */
    static final int LINHA_CABECALHO = 3;
    static final int LINHA_TOTAIS = 4;
    static final int PRIMEIRA_LINHA_DE_DIA = 5;

    private static final LocalDate DESDE_SEMPRE = LocalDate.of(1900, 1, 1);
    private static final LocalDate PARA_SEMPRE = LocalDate.of(9999, 12, 31);
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");
    private static final String[] MESES = {"Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho", "Julho",
            "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro"};
    private static final String[] DIAS = {"seg", "ter", "qua", "qui", "sex", "sáb", "dom"};

    private final UsuarioRepository usuarios;
    private final ConsolidacaoBancoHoras consolidacao;
    private final ConsultarAuditoriaUseCase auditoria;
    private final GerenciarCicloBancoUseCase ciclos;
    private final RegrasJornada regras;
    private final Clock clock;

    public MontadorPlanilhaConferencia(UsuarioRepository usuarios, ConsolidacaoBancoHoras consolidacao,
                                       ConsultarAuditoriaUseCase auditoria, GerenciarCicloBancoUseCase ciclos,
                                       RegrasJornada regras, Clock clock) {
        this.usuarios = usuarios;
        this.consolidacao = consolidacao;
        this.auditoria = auditoria;
        this.ciclos = ciclos;
        this.regras = regras;
        this.clock = clock;
    }

    public PlanilhaConferencia montar(UUID usuarioId) {
        Usuario pessoa = usuarios.buscarPorId(usuarioId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("USUARIO_NAO_ENCONTRADO", "Usuário não encontrado."));
        Instant agora = clock.instant();
        LocalDate hoje = LocalDate.now(clock);
        String atualizado = "Atualizado em " + DATA_HORA.format(LocalDateTime.now(clock));
        String nome = pessoa.nome() == null || pessoa.nome().isBlank() ? pessoa.login() : pessoa.nome();

        List<AbaDoMes> meses = new ArrayList<>();
        for (YearMonth mes : mesesComRegistro(usuarioId)) {
            meses.add(abaDoMes(nome, auditoria.mes(usuarioId, mes), hoje, atualizado));
        }
        List<Aba> abas = new ArrayList<>(meses.size() + 1);
        abas.add(abaResumo(nome, meses, ciclos.listar(usuarioId), horarios(usuarioId), atualizado));
        meses.forEach(m -> abas.add(m.aba()));
        return new PlanilhaConferencia(nome, pessoa.login(), abas, agora);
    }

    /** Do mês mais recente para o mais antigo, sem pular os meses do meio (férias, licença). */
    private List<YearMonth> mesesComRegistro(UUID usuarioId) {
        List<SaldoMensal> comDados = consolidacao.periodo(usuarioId, DESDE_SEMPRE, PARA_SEMPRE);
        if (comDados.isEmpty()) {
            return List.of();
        }
        YearMonth primeiro = mes(comDados.get(0));
        YearMonth ultimo = mes(comDados.get(comDados.size() - 1));
        List<YearMonth> meses = new ArrayList<>();
        for (YearMonth m = ultimo; !m.isBefore(primeiro) && meses.size() < MAXIMO_MESES; m = m.minusMonths(1)) {
            meses.add(m);
        }
        return meses;
    }

    private List<HorarioTrabalho> horarios(UUID usuarioId) {
        List<HorarioTrabalho> lista = regras.horarios(usuarioId);
        return lista.isEmpty() ? List.of(regras.padrao(usuarioId)) : lista;
    }

    // ------------------------------------------------------------------ aba do mês

    /** A aba e onde ficam os totais dela (o resumo aponta para eles). */
    record AbaDoMes(Aba aba, YearMonth mes, int colunaTrabalhado, int colunaPrevisto, int colunaTolerancia,
                    int colunaSaldo, int colunaLancamentos, long trabalhado, long previsto, long tolerancia,
                    long saldo, long lancamentos, int diasRegistrados, int diasEmAberto) {

        long saldoDoMes() {
            return saldo + lancamentos;
        }
    }

    private AbaDoMes abaDoMes(String nome, AuditoriaMesView mes, LocalDate hoje, String atualizado) {
        YearMonth referencia = mes.referencia();
        Map<LocalDate, AuditoriaMesView.Dia> porData = mes.dias().stream()
                .collect(Collectors.toMap(d -> d.registro().data(), Function.identity()));
        Map<LocalDate, Feriado> feriados = mes.feriados().stream()
                .collect(Collectors.toMap(Feriado::data, Function.identity(), (a, b) -> a));
        Map<LocalDate, MesJornadaView.Expediente> expedientes = mes.expedientes().stream()
                .collect(Collectors.toMap(MesJornadaView.Expediente::data, Function.identity(), (a, b) -> a));
        Map<LocalDate, List<LancamentoBanco>> lancamentos = mes.lancamentos().stream()
                .collect(Collectors.groupingBy(LancamentoBanco::data));

        boolean terceiroPeriodo = mes.dias().stream().flatMap(d -> d.registro().marcacoes().stream())
                .anyMatch(m -> m.real() != null && m.tipo().ordinal() >= 4);
        int batidas = terceiroPeriodo ? 6 : 4;
        int cTrabalhado = 3 + batidas;
        int cPrevisto = cTrabalhado + 1;
        int cTolerancia = cTrabalhado + 2;
        int cSaldo = cTrabalhado + 3;
        int cLancamento = cTrabalhado + 4;
        int cSituacao = cTrabalhado + 5;
        int cPdfs = cTrabalhado + 6;
        int cObservacoes = cTrabalhado + 7;
        int colunas = cObservacoes + 1;

        List<List<Celula>> linhas = new ArrayList<>();
        linhas.add(linhaDeTexto(colunas, "Conferência de ponto · %s de %d".formatted(MESES[referencia.getMonthValue() - 1],
                referencia.getYear()), Estilo.TITULO));
        linhas.add(linhaDeTexto(colunas, nome + " · " + atualizado, Estilo.SUBTITULO));
        linhas.add(linhaVazia(colunas));

        Celula[] cabecalho = nova(colunas, Estilo.CABECALHO);
        List<String> rotulos = new ArrayList<>(List.of("Data", "Dia", "Tipo de dia"));
        for (int i = 0; i < batidas; i++) {
            rotulos.add(TipoBatida.daPosicao(i).rotulo());
        }
        rotulos.addAll(List.of("Trabalhado", "Previsto", "Tolerância", "Saldo do dia", "Lançamento no banco",
                "Situação", "PDFs", "Observações"));
        for (int c = 0; c < colunas; c++) {
            cabecalho[c] = Celula.texto(rotulos.get(c), Estilo.CABECALHO);
        }
        linhas.add(List.of(cabecalho));
        linhas.add(List.of()); // os totais entram depois dos dias (as fórmulas dependem da última linha)

        long trabalhado = 0;
        long previsto = 0;
        long tolerancia = 0;
        long saldo = 0;
        long lancado = 0;
        long pdfs = 0;
        for (LocalDate data = referencia.atDay(1); !data.isAfter(referencia.atEndOfMonth()); data = data.plusDays(1)) {
            LocalDate dia = data;
            AuditoriaMesView.Dia registrado = porData.get(dia);
            RegistroJornadaView registro = registrado == null ? null : registrado.registro();
            Feriado feriado = feriados.get(dia);
            Ausencia ausencia = mes.ausencias().stream().filter(a -> a.contem(dia)).findFirst().orElse(null);
            TipoDia tipo = registro != null ? registro.tipoDia()
                    : feriado != null ? TipoDia.FERIADO
                    : !expedientes.containsKey(dia) ? TipoDia.FIM_DE_SEMANA
                    : ausencia != null ? TipoDia.AUSENCIA : TipoDia.UTIL;
            boolean util = tipo.isUtil();
            boolean fechada = registro != null && registro.status() == StatusJornada.FECHADA;
            Estilo base = util ? Estilo.NORMAL : Estilo.NORMAL.comFundo(Cor.FUNDO_APAGADO);
            Estilo apoio = base.comTexto(Cor.SUAVE);

            Celula[] linha = nova(colunas, base);
            linha[0] = Celula.data(dia, base.alinhado(Alinhamento.CENTRO));
            linha[1] = Celula.texto(DIAS[dia.getDayOfWeek().getValue() - 1], apoio.alinhado(Alinhamento.CENTRO));
            linha[2] = Celula.texto(rotuloDoTipo(tipo, dia, ausencia), util ? base : apoio);
            if (registro != null) {
                for (MarcacaoApurada m : registro.marcacoes()) {
                    if (m.real() == null || m.tipo().ordinal() >= batidas) {
                        continue;
                    }
                    Estilo estilo = base.alinhado(Alinhamento.CENTRO);
                    if (util && m.oficial() != null && !m.toleranciaAplicada()) {
                        estilo = estilo.comTexto(Cor.DEBITO);
                    }
                    if (registro.horariosAjustados().contains(m.real())) {
                        estilo = estilo.emItalico();
                    }
                    linha[3 + m.tipo().ordinal()] = Celula.hora(m.real(), estilo);
                }
                linha[cPdfs] = Celula.inteiro(registrado.comprovantes().size(), base.alinhado(Alinhamento.CENTRO));
                pdfs += registrado.comprovantes().size();
            }
            if (fechada) {
                linha[cTrabalhado] = Celula.duracao(registro.segundosTrabalhados(), base);
                linha[cPrevisto] = Celula.duracao(registro.jornadaPrevistaSegundos(), base);
                linha[cSaldo] = Celula.duracao(registro.saldoDiarioSegundos(), base.corDoSaldo(registro.saldoDiarioSegundos()));
                // o que a tolerância acrescentou ou tirou: saldo = trabalhado + tolerância - previsto
                long ajuste = registro.saldoDiarioSegundos()
                        - (registro.segundosTrabalhados() - registro.jornadaPrevistaSegundos());
                if (ajuste != 0) {
                    linha[cTolerancia] = Celula.duracao(ajuste, apoio);
                    tolerancia += ajuste;
                }
                trabalhado += registro.segundosTrabalhados();
                previsto += registro.jornadaPrevistaSegundos();
                saldo += registro.saldoDiarioSegundos();
            }
            List<LancamentoBanco> doDia = lancamentos.getOrDefault(dia, List.of());
            if (!doDia.isEmpty()) {
                long soma = doDia.stream().mapToLong(LancamentoBanco::segundos).sum();
                linha[cLancamento] = Celula.duracao(soma, base.corDoSaldo(soma));
                lancado += soma;
            }
            linha[cSituacao] = situacao(registro, util, dia, hoje, base);
            linha[cObservacoes] = Celula.texto(observacoes(registrado, tipo, feriado, ausencia, doDia), base.comQuebra());
            linhas.add(List.of(linha));
        }
        int ultimaLinhaDeDia = linhas.size() - 1;

        Celula[] totais = nova(colunas, Estilo.TOTAL);
        totais[0] = Celula.texto("Total do mês", Estilo.TOTAL);
        totais[cTrabalhado] = Celula.formulaDuracao(soma(cTrabalhado, ultimaLinhaDeDia), trabalhado, Estilo.TOTAL);
        totais[cPrevisto] = Celula.formulaDuracao(soma(cPrevisto, ultimaLinhaDeDia), previsto, Estilo.TOTAL);
        totais[cTolerancia] = Celula.formulaDuracao(soma(cTolerancia, ultimaLinhaDeDia), tolerancia, Estilo.TOTAL);
        totais[cSaldo] = Celula.formulaDuracao(soma(cSaldo, ultimaLinhaDeDia), saldo, Estilo.TOTAL.corDoSaldo(saldo));
        totais[cLancamento] = Celula.formulaDuracao(soma(cLancamento, ultimaLinhaDeDia), lancado,
                Estilo.TOTAL.corDoSaldo(lancado));
        totais[cPdfs] = Celula.formulaInteiro(soma(cPdfs, ultimaLinhaDeDia), pdfs,
                Estilo.TOTAL.alinhado(Alinhamento.CENTRO));
        linhas.set(LINHA_TOTAIS, List.of(totais));

        int diasRegistrados = mes.dias().size();
        int diasEmAberto = (int) mes.dias().stream()
                .filter(d -> d.registro().status() != StatusJornada.FECHADA).count();
        long saldoDoMes = saldo + lancado;
        linhas.add(linhaVazia(colunas));
        linhas.add(linhaDeTexto(colunas, "Resumo do mês", Estilo.SECAO));
        linhas.add(linhaDeResumo(colunas, "Horas trabalhadas",
                Celula.formulaDuracao(Aba.ref(LINHA_TOTAIS, cTrabalhado), trabalhado, Estilo.NORMAL)));
        linhas.add(linhaDeResumo(colunas, "Horas previstas",
                Celula.formulaDuracao(Aba.ref(LINHA_TOTAIS, cPrevisto), previsto, Estilo.NORMAL)));
        linhas.add(linhaDeResumo(colunas, "Tolerância",
                Celula.formulaDuracao(Aba.ref(LINHA_TOTAIS, cTolerancia), tolerancia, Estilo.NORMAL)));
        linhas.add(linhaDeResumo(colunas, "Saldo dos dias",
                Celula.formulaDuracao(Aba.ref(LINHA_TOTAIS, cSaldo), saldo, Estilo.NORMAL.corDoSaldo(saldo))));
        linhas.add(linhaDeResumo(colunas, "Lançamentos no banco",
                Celula.formulaDuracao(Aba.ref(LINHA_TOTAIS, cLancamento), lancado, Estilo.NORMAL.corDoSaldo(lancado))));
        linhas.add(linhaDeResumo(colunas, "Saldo do mês",
                Celula.formulaDuracao(Aba.ref(LINHA_TOTAIS, cSaldo) + "+" + Aba.ref(LINHA_TOTAIS, cLancamento), saldoDoMes,
                        Estilo.ROTULO.corDoSaldo(saldoDoMes))));
        linhas.add(linhaDeResumo(colunas, "Dias com registro", Celula.inteiro(diasRegistrados, Estilo.NORMAL)));
        linhas.add(linhaDeResumo(colunas, "Dias em aberto", Celula.inteiro(diasEmAberto, Estilo.NORMAL)));
        linhas.add(linhaVazia(colunas));
        linhas.add(linhaDeTexto(colunas, "Saldo do dia = Trabalhado + Tolerância − Previsto. Tolerância: a batida "
                + "dentro da tolerância do horário previsto vale o horário previsto (regra do RH).", Estilo.SUBTITULO));
        linhas.add(linhaDeTexto(colunas, "Horário em vermelho: batida fora da tolerância (vale o horário exato). "
                + "Em itálico: batida incluída ou corrigida à mão.", Estilo.SUBTITULO));
        linhas.add(linhaDeTexto(colunas, "Só os dias fechados entram nos totais: dia em andamento ou incompleto "
                + "(falta batida) fica com as horas em branco.", Estilo.SUBTITULO));
        linhas.add(linhaDeTexto(colunas, "Lançamento no banco: débito ou crédito avulso (ex.: folga compensada), "
                + "somado ao saldo do mês.", Estilo.SUBTITULO));

        List<Integer> larguras = new ArrayList<>(List.of(88, 44, 118));
        for (int i = 0; i < batidas; i++) {
            larguras.add(84);
        }
        larguras.addAll(List.of(92, 92, 86, 92, 104, 108, 50, 420));

        Aba aba = new Aba(idDoMes(referencia), tituloDoMes(referencia), linhas, larguras, PRIMEIRA_LINHA_DE_DIA);
        return new AbaDoMes(aba, referencia, cTrabalhado, cPrevisto, cTolerancia, cSaldo, cLancamento, trabalhado,
                previsto, tolerancia, saldo, lancado, diasRegistrados, diasEmAberto);
    }

    private static String soma(int coluna, int ultimaLinha) {
        return "SUM(%s:%s)".formatted(Aba.ref(PRIMEIRA_LINHA_DE_DIA, coluna), Aba.ref(ultimaLinha, coluna));
    }

    private static String rotuloDoTipo(TipoDia tipo, LocalDate dia, Ausencia ausencia) {
        return switch (tipo) {
            case UTIL -> "Útil";
            case FERIADO -> "Feriado";
            case AUSENCIA -> ausencia == null ? "Ausência" : ausencia.tipo().rotulo();
            case FIM_DE_SEMANA -> dia.getDayOfWeek() == DayOfWeek.SATURDAY || dia.getDayOfWeek() == DayOfWeek.SUNDAY
                    ? "Fim de semana" : "Sem expediente";
        };
    }

    private static Celula situacao(RegistroJornadaView registro, boolean util, LocalDate dia, LocalDate hoje, Estilo base) {
        if (registro == null) {
            return util && dia.isBefore(hoje)
                    ? Celula.texto("Sem registro", base.comTexto(Cor.DEBITO))
                    : Celula.vazia(base);
        }
        if (registro.status() == StatusJornada.FECHADA) {
            return Celula.texto("Fechada", base.comTexto(Cor.SUAVE));
        }
        return Celula.texto(dia.isBefore(hoje) ? "Incompleto" : "Em andamento", base.comTexto(Cor.PENDENTE).emNegrito());
    }

    private String observacoes(AuditoriaMesView.Dia registrado, TipoDia tipo, Feriado feriado, Ausencia ausencia,
                               List<LancamentoBanco> lancamentos) {
        List<String> partes = new ArrayList<>();
        if (feriado != null && !"Feriado".equalsIgnoreCase(feriado.descricao())) {
            partes.add(feriado.descricao());
        }
        if (tipo == TipoDia.AUSENCIA && ausencia != null && ausencia.descricao() != null) {
            partes.add(ausencia.descricao());
        }
        if (registrado != null) {
            RegistroJornadaView registro = registrado.registro();
            if (registro.registroManual()) {
                partes.add("Lançado à mão");
            }
            if (registro.status() != StatusJornada.FECHADA && registro.segundosTrabalhados() > 0) {
                partes.add("Trabalhado até a última batida: %s (fora dos totais)"
                        .formatted(TextoDuracao.duracao(registro.segundosTrabalhados())));
            }
            for (AjusteJornada ajuste : registrado.ajustes()) {
                partes.add("Ajuste de %s em %s: %s".formatted(ajuste.usuario(),
                        DATA.format(LocalDate.ofInstant(ajuste.ajustadoEm(), clock.getZone())),
                        ajuste.justificativa()));
            }
        }
        for (LancamentoBanco l : lancamentos) {
            partes.add("Banco de horas %s: %s (por %s)".formatted(TextoDuracao.saldo(l.segundos()), l.descricao(),
                    l.criadoPor()));
        }
        return String.join(" · ", partes);
    }

    // ------------------------------------------------------------------ aba de resumo

    private Aba abaResumo(String nome, List<AbaDoMes> meses, List<CicloBancoView> ciclosDoUsuario,
                          List<HorarioTrabalho> horarios, String atualizado) {
        int colunas = 10;
        List<List<Celula>> linhas = new ArrayList<>();
        linhas.add(linhaDeTexto(colunas, "Conferência de ponto · " + nome, Estilo.TITULO));
        linhas.add(linhaDeTexto(colunas, atualizado + " pelo sistema de conferência de ponto. As abas são reescritas "
                + "a cada atualização: anote em outra aba.", Estilo.SUBTITULO));
        linhas.add(linhaVazia(colunas));

        // --- banco de horas
        linhas.add(linhaDeTexto(colunas, "Banco de horas", Estilo.SECAO));
        linhas.add(cabecalho(colunas, "Ciclo", "Início", "Fim (previsto, se aberto)", "Situação", "Dias com registro",
                "Dias em aberto", "Saldo"));
        if (ciclosDoUsuario.isEmpty()) {
            linhas.add(linhaDeTexto(colunas, "Nenhum ciclo do banco de horas aberto.", Estilo.SUBTITULO));
        }
        for (CicloBancoView v : ciclosDoUsuario) {
            CicloBanco c = v.ciclo();
            Estilo estilo = c.isAberto() ? Estilo.ROTULO : Estilo.NORMAL;
            Celula[] linha = nova(colunas, Estilo.NORMAL);
            linha[0] = Celula.texto(c.isAberto() ? "Ciclo atual" : "Encerrado", estilo);
            linha[1] = Celula.data(c.dataInicio(), Estilo.CENTRO);
            linha[2] = Celula.data(c.isAberto() ? c.dataFimPrevista() : c.dataFim(), Estilo.CENTRO);
            linha[3] = Celula.texto(c.isAberto() ? "Aberto"
                    : "Fechado" + (c.fechadoPor() == null ? "" : " por " + c.fechadoPor()), Estilo.NORMAL.comQuebra());
            linha[4] = Celula.inteiro(v.diasRegistrados(), Estilo.CENTRO);
            linha[5] = Celula.inteiro(v.diasEmAberto(), Estilo.CENTRO);
            linha[6] = Celula.duracao(v.saldoSegundos(), estilo.corDoSaldo(v.saldoSegundos()));
            linhas.add(List.of(linha));
        }
        linhas.add(linhaVazia(colunas));

        // --- meses
        linhas.add(linhaDeTexto(colunas, "Meses", Estilo.SECAO));
        linhas.add(cabecalho(colunas, "Mês", "Dias com registro", "Dias em aberto", "Trabalhado", "Previsto",
                "Tolerância", "Saldo dos dias", "Lançamentos no banco", "Saldo do mês", "Banco de horas no fim do mês"));
        int linhaTotal = linhas.size();
        linhas.add(List.of());
        Map<YearMonth, Integer> bancoNoFimDoMes = bancoNoFimDoMes(ciclosDoUsuario);
        int primeiraLinhaDeMes = linhas.size();
        long[] totais = new long[8];
        for (AbaDoMes m : meses) {
            Aba aba = m.aba();
            String trabalhado = aba.refExterna(LINHA_TOTAIS, m.colunaTrabalhado());
            String previsto = aba.refExterna(LINHA_TOTAIS, m.colunaPrevisto());
            String tolerancia = aba.refExterna(LINHA_TOTAIS, m.colunaTolerancia());
            String saldo = aba.refExterna(LINHA_TOTAIS, m.colunaSaldo());
            String lancamentos = aba.refExterna(LINHA_TOTAIS, m.colunaLancamentos());
            Celula[] linha = nova(colunas, Estilo.NORMAL);
            linha[0] = Celula.texto("%s de %d".formatted(MESES[m.mes().getMonthValue() - 1], m.mes().getYear()));
            linha[1] = Celula.inteiro(m.diasRegistrados(), Estilo.CENTRO);
            linha[2] = Celula.inteiro(m.diasEmAberto(), Estilo.CENTRO);
            linha[3] = Celula.formulaDuracao(trabalhado, m.trabalhado(), Estilo.NORMAL);
            linha[4] = Celula.formulaDuracao(previsto, m.previsto(), Estilo.NORMAL);
            linha[5] = Celula.formulaDuracao(tolerancia, m.tolerancia(), Estilo.NORMAL.comTexto(Cor.SUAVE));
            linha[6] = Celula.formulaDuracao(saldo, m.saldo(), Estilo.NORMAL.corDoSaldo(m.saldo()));
            linha[7] = Celula.formulaDuracao(lancamentos, m.lancamentos(), Estilo.NORMAL.corDoSaldo(m.lancamentos()));
            linha[8] = Celula.formulaDuracao(saldo + "+" + lancamentos, m.saldoDoMes(),
                    Estilo.ROTULO.corDoSaldo(m.saldoDoMes()));
            Integer banco = bancoNoFimDoMes.get(m.mes());
            if (banco != null) {
                linha[9] = Celula.duracao(banco, Estilo.NORMAL.corDoSaldo(banco));
            }
            linhas.add(List.of(linha));
            long[] valores = {m.diasRegistrados(), m.diasEmAberto(), m.trabalhado(), m.previsto(), m.tolerancia(),
                    m.saldo(), m.lancamentos(), m.saldoDoMes()};
            for (int i = 0; i < totais.length; i++) {
                totais[i] += valores[i];
            }
        }
        Celula[] total = nova(colunas, Estilo.TOTAL);
        total[0] = Celula.texto("Total", Estilo.TOTAL);
        if (!meses.isEmpty()) {
            int ultimaLinhaDeMes = linhas.size() - 1;
            for (int i = 0; i < totais.length; i++) {
                int coluna = i + 1;
                String formula = "SUM(%s:%s)".formatted(Aba.ref(primeiraLinhaDeMes, coluna), Aba.ref(ultimaLinhaDeMes, coluna));
                total[coluna] = i < 2
                        ? Celula.formulaInteiro(formula, totais[i], Estilo.TOTAL.alinhado(Alinhamento.CENTRO))
                        : Celula.formulaDuracao(formula, totais[i], i >= 5 ? Estilo.TOTAL.corDoSaldo(totais[i]) : Estilo.TOTAL);
            }
        }
        linhas.set(linhaTotal, List.of(total));
        if (meses.isEmpty()) {
            linhas.add(linhaDeTexto(colunas, "Ainda não há registros de ponto.", Estilo.SUBTITULO));
        }
        linhas.add(linhaVazia(colunas));

        // --- horário
        linhas.add(linhaDeTexto(colunas, "Horário de trabalho", Estilo.SECAO));
        linhas.add(cabecalho(colunas, "Vale a partir de", "Segunda", "Terça", "Quarta", "Quinta", "Sexta", "Sábado",
                "Domingo", "Tolerância por batida"));
        List<HorarioTrabalho> doMaisRecente = horarios.stream()
                .sorted(Comparator.comparing(HorarioTrabalho::vigenteDesde).reversed()).toList();
        for (HorarioTrabalho h : doMaisRecente) {
            Celula[] linha = nova(colunas, Estilo.NORMAL);
            linha[0] = h.vigenteDesde().equals(HorarioTrabalho.DESDE_SEMPRE)
                    ? Celula.texto("desde o início")
                    : Celula.data(h.vigenteDesde(), Estilo.NORMAL.alinhado(Alinhamento.ESQUERDA));
            for (DayOfWeek d : DayOfWeek.values()) {
                GradeHoraria grade = h.dias().get(d);
                linha[d.getValue()] = Celula.texto(grade == null ? "—" : grade.texto().replace(' ', '\n'),
                        Estilo.CENTRO.comQuebra());
            }
            linha[8] = Celula.texto(h.toleranciaMinutos() + " min", Estilo.CENTRO);
            linhas.add(List.of(linha));
        }

        List<Integer> larguras = new ArrayList<>(List.of(190));
        larguras.addAll(Arrays.asList(118, 118, 118, 118, 118, 118, 118, 118, 132));
        return new Aba(Aba.ID_RESUMO, "Resumo", linhas, larguras, 0);
    }

    /**
     * Saldo do banco de horas (do ciclo que cobre o mês) acumulado até o fim de cada mês. Quando um ciclo fecha
     * no meio do mês, vale o do ciclo seguinte.
     */
    private static Map<YearMonth, Integer> bancoNoFimDoMes(List<CicloBancoView> ciclosDoUsuario) {
        Map<YearMonth, Integer> porMes = new HashMap<>();
        ciclosDoUsuario.stream()
                .sorted(Comparator.comparing((CicloBancoView v) -> v.ciclo().dataInicio()))
                .forEach(v -> v.meses().forEach(m -> porMes.put(mes(m), m.saldoAnualAcumuladoSegundos())));
        return porMes;
    }

    // ------------------------------------------------------------------ apoio

    static int idDoMes(YearMonth mes) {
        return mes.getYear() * 100 + mes.getMonthValue();
    }

    static String tituloDoMes(YearMonth mes) {
        return MESES[mes.getMonthValue() - 1].substring(0, 3) + " " + mes.getYear();
    }

    private static YearMonth mes(SaldoMensal s) {
        return YearMonth.of(s.ano(), s.mes());
    }

    private static Celula[] nova(int colunas, Estilo estilo) {
        Celula[] linha = new Celula[colunas];
        Arrays.fill(linha, Celula.vazia(estilo));
        return linha;
    }

    private static List<Celula> linhaVazia(int colunas) {
        return List.of(nova(colunas, Estilo.NORMAL));
    }

    /** Texto na primeira coluna, transbordando sobre as vizinhas (vazias). */
    private static List<Celula> linhaDeTexto(int colunas, String texto, Estilo estilo) {
        Celula[] linha = nova(colunas, Estilo.NORMAL);
        linha[0] = Celula.texto(texto, estilo);
        return List.of(linha);
    }

    /** Rótulo na primeira coluna e o valor na quarta (a primeira das batidas, larga o bastante para "-123:45:59"). */
    private static List<Celula> linhaDeResumo(int colunas, String rotulo, Celula valor) {
        Celula[] linha = nova(colunas, Estilo.NORMAL);
        linha[0] = Celula.texto(rotulo, Estilo.ROTULO);
        linha[3] = valor;
        return List.of(linha);
    }

    private static List<Celula> cabecalho(int colunas, String... rotulos) {
        Celula[] linha = nova(colunas, Estilo.NORMAL);
        for (int i = 0; i < rotulos.length; i++) {
            linha[i] = Celula.texto(rotulos[i], Estilo.CABECALHO);
        }
        return List.of(linha);
    }
}
