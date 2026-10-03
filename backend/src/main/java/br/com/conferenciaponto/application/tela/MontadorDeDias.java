package br.com.conferenciaponto.application.tela;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.tela.DiaView.Acoes;
import br.com.conferenciaponto.application.tela.DiaView.Batida;
import br.com.conferenciaponto.application.tela.DiaView.Filtro;
import br.com.conferenciaponto.application.tela.DiaView.Marcador;
import br.com.conferenciaponto.application.tela.DiaView.Situacao;
import br.com.conferenciaponto.application.tela.DiaView.Tom;
import br.com.conferenciaponto.application.view.ComprovanteArquivoView;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.ComprovanteArquivado;
import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.LancamentoBanco;
import br.com.conferenciaponto.domain.model.MarcacaoApurada;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.StatusJornada;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.port.AjusteJornadaRepository;
import br.com.conferenciaponto.domain.port.ArmazenamentoComprovantes;
import br.com.conferenciaponto.domain.port.AusenciaRepository;
import br.com.conferenciaponto.domain.port.CalendarioFeriados;
import br.com.conferenciaponto.domain.port.ComprovanteArquivadoRepository;
import br.com.conferenciaponto.domain.port.DivergenciaRepository;
import br.com.conferenciaponto.domain.port.LancamentoBancoRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Monta os dias de um período do jeito que as telas mostram ({@link DiaView}): junta o registro, o horário da
 * pessoa, feriados, ausências, lançamentos no banco, comprovantes, ajustes e diferenças com o RH, e decide a
 * situação, a frase e as ações de cada dia. É aqui que moram as regras que antes ficavam no navegador
 * ("incompleto", "fora da tolerância", quem pode ajustar o quê).
 */
@Service
@Transactional(readOnly = true)
public class MontadorDeDias {

    /**
     * Quem está olhando.
     *
     * @param podeEditar os dados são do próprio usuário e o perfil dele registra ponto
     * @param admin      administrador (só ele cadastra e remove feriado)
     */
    public record Quem(boolean podeEditar, boolean admin) {

        public static final Quem SO_CONSULTA = new Quem(false, false);
    }

    private final RegistroJornadaRepository registros;
    private final RegrasJornada regras;
    private final AusenciaRepository ausencias;
    private final CalendarioFeriados feriados;
    private final LancamentoBancoRepository lancamentos;
    private final DivergenciaRepository divergencias;
    private final ComprovanteArquivadoRepository arquivos;
    private final ArmazenamentoComprovantes armazenamento;
    private final AjusteJornadaRepository ajustes;
    private final Clock clock;

    public MontadorDeDias(RegistroJornadaRepository registros, RegrasJornada regras, AusenciaRepository ausencias,
                          CalendarioFeriados feriados, LancamentoBancoRepository lancamentos,
                          DivergenciaRepository divergencias, ComprovanteArquivadoRepository arquivos,
                          ArmazenamentoComprovantes armazenamento, AjusteJornadaRepository ajustes, Clock clock) {
        this.registros = registros;
        this.regras = regras;
        this.ausencias = ausencias;
        this.feriados = feriados;
        this.lancamentos = lancamentos;
        this.divergencias = divergencias;
        this.arquivos = arquivos;
        this.armazenamento = armazenamento;
        this.ajustes = ajustes;
        this.clock = clock;
    }

    /** Todos os dias do período (inclusive os sem registro), do mais antigo para o mais recente. */
    public List<DiaView> montar(UUID usuarioId, LocalDate inicio, LocalDate fim, Quem quem) {
        LocalDate hoje = LocalDate.now(clock);
        Map<LocalDate, RegistroJornada> porData = registros.listarPorPeriodo(usuarioId, inicio, fim).stream()
                .collect(Collectors.toMap(RegistroJornada::getDataReferencia, Function.identity(), (a, b) -> a));
        Map<LocalDate, Feriado> feriadoPorData = feriados.listar(inicio, fim).stream()
                .collect(Collectors.toMap(Feriado::data, Function.identity(), (a, b) -> a));
        List<Ausencia> ausenciasDoPeriodo = ausencias.listarNoPeriodo(usuarioId, inicio, fim);
        Map<LocalDate, List<LancamentoBanco>> lancamentosPorData = lancamentos.listarNoPeriodo(usuarioId, inicio, fim)
                .stream().collect(Collectors.groupingBy(LancamentoBanco::data));
        Set<LocalDate> comDivergencia = divergencias.listar(usuarioId, StatusDivergencia.PENDENTE, inicio, fim).stream()
                .map(Divergencia::data).collect(Collectors.toSet());
        Map<UUID, List<ComprovanteArquivado>> comprovantesPorRegistro = porData.isEmpty() ? Map.of()
                : arquivos.listarPorRegistros(porData.values().stream().map(RegistroJornada::getId).toList()).stream()
                        .collect(Collectors.groupingBy(ComprovanteArquivado::registroJornadaId));
        Map<LocalDate, List<AjusteJornada>> ajustesPorData = ajustes.listarPorPeriodo(usuarioId, inicio, fim).stream()
                .collect(Collectors.groupingBy(AjusteJornada::data));

        List<DiaView> dias = new ArrayList<>();
        for (LocalDate d = inicio; !d.isAfter(fim); d = d.plusDays(1)) {
            LocalDate data = d;
            RegistroJornada registro = porData.get(data);
            Ausencia ausencia = ausenciasDoPeriodo.stream().filter(a -> a.contem(data)).findFirst().orElse(null);
            dias.add(dia(usuarioId, data, hoje, registro, feriadoPorData.get(data), ausencia,
                    lancamentosPorData.getOrDefault(data, List.of()), comDivergencia.contains(data),
                    registro == null ? List.of() : comprovantesPorRegistro.getOrDefault(registro.getId(), List.of()),
                    ajustesPorData.getOrDefault(data, List.of()), quem));
        }
        return dias;
    }

    private DiaView dia(UUID usuarioId, LocalDate data, LocalDate hoje, RegistroJornada registro, Feriado feriado,
                        Ausencia ausencia, List<LancamentoBanco> lancamentosDoDia, boolean divergencia,
                        List<ComprovanteArquivado> comprovantes, List<AjusteJornada> ajustesDoDia, Quem quem) {
        boolean ehHoje = data.equals(hoje);
        boolean futuro = data.isAfter(hoje);
        GradeHoraria grade = regras.horario(usuarioId, data).gradeDo(data).orElse(null);
        Marcador marcador = marcador(feriado, ausencia);
        RegistroJornadaView view = registro == null ? null : RegistroJornadaView.de(registro, regras.motor(registro));
        List<ComprovanteArquivoView> arquivados = comprovantes.stream()
                .map(c -> new ComprovanteArquivoView(c, armazenamento.uriDeAcesso(c.id()))).toList();

        List<Batida> batidas = view == null ? List.of() : batidas(view, grade, arquivados);
        boolean foraDaTolerancia = batidas.stream().anyMatch(b -> b.oficial() != null && !b.tolerada());
        boolean ajustado = view != null && !view.horariosAjustados().isEmpty();
        boolean lancadoAMao = view != null && view.registroManual();

        Situacao situacao;
        String texto;
        Tom tom;
        String descricao = null;
        String faltando = null;
        Integer saldo = null;

        if (view != null && view.status() == StatusJornada.EM_ANDAMENTO) {
            if (ehHoje) {
                situacao = Situacao.EM_ANDAMENTO;
                texto = "Em andamento";
                tom = Tom.INFO;
            } else {
                situacao = Situacao.INCOMPLETO;
                texto = "Faltou bater a saída";
                tom = Tom.NEGATIVO;
                faltando = "sem saída";
                descricao = "Fica fora do saldo até corrigir";
            }
        } else if (view != null) {
            saldo = view.saldoDiarioSegundos() == null ? 0 : view.saldoDiarioSegundos();
            situacao = saldo == 0 ? Situacao.EM_DIA : saldo > 0 ? Situacao.A_FAVOR : Situacao.DEVENDO;
            texto = Horas.saldoComSentido(saldo);
            tom = saldo == 0 ? Tom.NEUTRO : saldo > 0 ? Tom.POSITIVO : Tom.NEGATIVO;
            descricao = descricaoDoTrabalho(view, batidas, marcador);
        } else if (feriado != null) {
            situacao = Situacao.FERIADO;
            texto = "Feriado";
            tom = Tom.NEUTRO;
            descricao = feriado.descricao();
        } else if (ausencia != null) {
            situacao = Situacao.AUSENCIA;
            texto = ausencia.tipo().rotulo();
            tom = Tom.INFO;
            descricao = (ausencia.descricao() == null ? "" : ausencia.descricao() + " · ") + "não conta como falta";
        } else if (grade == null) {
            situacao = Situacao.SEM_EXPEDIENTE;
            texto = "Sem expediente";
            tom = Tom.NEUTRO;
            descricao = Horas.diaDaSemana(data) + ", sem expediente";
        } else if (futuro) {
            situacao = Situacao.FUTURO;
            texto = "";
            tom = Tom.NEUTRO;
        } else if (ehHoje) {
            situacao = Situacao.SEM_REGISTRO;
            texto = "Sem batidas ainda";
            tom = Tom.NEUTRO;
            descricao = "Nenhuma batida registrada hoje até agora";
        } else {
            situacao = Situacao.SEM_REGISTRO;
            texto = "Sem registro";
            tom = Tom.ATENCAO;
            descricao = "Dia de trabalho sem nenhuma batida: fica fora do saldo até informar as batidas ou marcar folga";
        }

        int previsto = view != null ? view.jornadaPrevistaSegundos()
                : (marcador != null || grade == null ? 0 : grade.cargaHorariaSegundos());

        Set<Filtro> filtros = EnumSet.noneOf(Filtro.class);
        if (situacao == Situacao.INCOMPLETO || (situacao == Situacao.SEM_REGISTRO && !ehHoje)) {
            filtros.add(Filtro.CORRIGIR);
        }
        if (situacao == Situacao.A_FAVOR || situacao == Situacao.DEVENDO || foraDaTolerancia) {
            filtros.add(Filtro.DIFERENCA);
        }
        if (marcador != null) {
            filtros.add(Filtro.FOLGA);
        }
        if (ajustado || lancadoAMao) {
            filtros.add(Filtro.AJUSTADO);
        }

        Acoes acoes = Acoes.NENHUMA;
        if (quem.podeEditar()) {
            boolean diaDeTrabalhoSemRegistro = view == null && grade != null && marcador == null && !futuro;
            boolean ajustar = !futuro && (view != null ? !lancadoAMao : diaDeTrabalhoSemRegistro);
            acoes = new Acoes(
                    situacao == Situacao.INCOMPLETO,
                    ajustar,
                    diaDeTrabalhoSemRegistro,
                    view == null && !futuro && ausencia == null && (grade == null || feriado != null),
                    lancadoAMao,
                    marcador == null,
                    marcador != null && (!marcador.feriado() || quem.admin()),
                    view != null && comprovantes.isEmpty(),
                    true,
                    divergencia);
        }

        int lancado = lancamentosDoDia.stream().mapToInt(LancamentoBanco::segundos).sum();
        return new DiaView(data, Horas.dia(data), Horas.diaLongo(data),
                ehHoje ? "Hoje" : data.equals(hoje.minusDays(1)) ? "Ontem" : null, ehHoje, futuro, situacao, texto, tom,
                descricao, batidas, faltando, view == null ? null : view.segundosTrabalhados(), saldo, previsto, marcador,
                lancado, lancamentosDoDia, divergencia, ajustado, lancadoAMao, acoes, filtros, view, arquivados,
                ajustesDoDia);
    }

    private static Marcador marcador(Feriado feriado, Ausencia ausencia) {
        if (feriado != null) { // o feriado vale para todos e vem antes da ausência da pessoa
            return new Marcador("FERIADO", "Feriado", feriado.descricao(), feriado.data(), feriado.data(), null,
                    feriado.abrangencia().name());
        }
        if (ausencia != null) {
            return new Marcador(ausencia.tipo().name(), ausencia.tipo().rotulo(), ausencia.descricao(),
                    ausencia.dataInicio(), ausencia.dataFim(), ausencia.id(), null);
        }
        return null;
    }

    /** As batidas feitas, com o nome que a pessoa usa, a nota ("12 min depois do horário") e o comprovante. */
    static List<Batida> batidas(RegistroJornadaView view, GradeHoraria grade, List<ComprovanteArquivoView> arquivados) {
        Map<TipoBatida, ComprovanteArquivoView> porTipo = new EnumMap<>(TipoBatida.class);
        arquivados.forEach(c -> porTipo.putIfAbsent(c.comprovante().tipoBatida(), c));
        GradeHoraria gradeDoDia = view.tipoDia() == TipoDia.UTIL ? grade : null;
        List<Batida> batidas = new ArrayList<>();
        for (MarcacaoApurada m : view.marcacoes()) {
            if (m.real() == null) {
                continue;
            }
            int posicao = m.tipo().ordinal();
            String nota = null;
            Tom tom = Tom.NEUTRO;
            if (m.oficial() != null && m.desvioSegundos() != null) {
                int desvio = m.desvioSegundos();
                if (m.toleranciaAplicada() || desvio == 0) {
                    nota = "no horário";
                } else {
                    nota = Horas.duracao(desvio) + (desvio > 0 ? " depois do horário" : " antes do horário");
                    // entrada atrasada e saída antecipada tiram tempo; o contrário soma
                    boolean perde = m.tipo().isEntrada() == desvio > 0;
                    tom = perde ? Tom.NEGATIVO : Tom.POSITIVO;
                }
            }
            ComprovanteArquivoView comprovante = porTipo.get(m.tipo());
            batidas.add(new Batida(m.tipo(), NomesDeBatida.de(gradeDoDia, posicao), m.real(), m.considerado(),
                    m.oficial(), m.desvioSegundos(), m.toleranciaAplicada(),
                    view.horariosAjustados().contains(m.real()), nota, tom,
                    comprovante == null ? null : comprovante.comprovante().id(),
                    comprovante == null ? null : comprovante.uriDownload().toString()));
        }
        return batidas;
    }

    /** "entrada 12 min depois do horário", "trabalho em feriado: conta inteiro a favor"... */
    private static String descricaoDoTrabalho(RegistroJornadaView view, List<Batida> batidas, Marcador marcador) {
        if (view.tipoDia() != TipoDia.UTIL) {
            String motivo = switch (view.tipoDia()) {
                case FERIADO -> "trabalho em feriado";
                case AUSENCIA -> "trabalho em dia de " + (marcador == null ? "ausência" : marcador.rotulo().toLowerCase());
                default -> "trabalho em dia sem expediente";
            };
            return motivo + ": conta inteiro a favor";
        }
        List<String> notas = batidas.stream()
                .filter(b -> b.oficial() != null && !b.tolerada() && b.desvioSegundos() != null && b.desvioSegundos() != 0)
                .map(b -> b.rotulo().toLowerCase() + " " + b.nota())
                .toList();
        if (notas.isEmpty()) {
            return null;
        }
        return notas.size() <= 2 ? String.join(" · ", notas)
                : notas.get(0) + " · " + notas.get(1) + " · mais " + (notas.size() - 2);
    }
}
