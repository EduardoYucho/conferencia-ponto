package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.DiferencaDaCarga;
import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.view.AuditoriaMesView;
import br.com.conferenciaponto.application.view.BancoDeHorasMesView;
import br.com.conferenciaponto.application.view.BancoDeHorasMesView.Bloco;
import br.com.conferenciaponto.application.view.BancoDeHorasMesView.Compensacao;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.model.AjusteJornada;
import br.com.conferenciaponto.domain.model.Ausencia;
import br.com.conferenciaponto.domain.model.AvisoLegal;
import br.com.conferenciaponto.domain.model.Feriado;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.StatusJornada;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.port.AjusteJornadaRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.service.LeiDaJornada;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A tela Banco de horas de um mês: a carga semanal contra o horário, o que o fim de semana compensou e o que foi para
 * o banco, cada dia com a origem das batidas (relógio com PDF, sem comprovante, ajustada, lançada à mão), os avisos
 * da lei e a conferência do mês.
 *
 * <p>Os números são os mesmos do painel e da planilha: o saldo do mês já soma a diferença da carga
 * ({@link DiferencaDaCarga}); aqui só se mostra de onde ela vem e quanto o fim de semana pagou.
 */
@Service
@Transactional(readOnly = true)
public class ConsultarBancoDeHorasUseCase {

    private final ConsultarAuditoriaUseCase auditoria;
    private final RegistroJornadaRepository registros;
    private final RegrasJornada regras;
    private final DiferencaDaCarga carga;
    private final LeiDaJornada lei;
    private final ConferirMesUseCase conferencias;
    private final GerenciarCicloBancoUseCase ciclos;
    private final AjusteJornadaRepository historico;
    private final Clock clock;

    public ConsultarBancoDeHorasUseCase(ConsultarAuditoriaUseCase auditoria, RegistroJornadaRepository registros,
                                        RegrasJornada regras, DiferencaDaCarga carga, LeiDaJornada lei,
                                        ConferirMesUseCase conferencias, GerenciarCicloBancoUseCase ciclos,
                                        AjusteJornadaRepository historico, Clock clock) {
        this.auditoria = auditoria;
        this.registros = registros;
        this.regras = regras;
        this.carga = carga;
        this.lei = lei;
        this.conferencias = conferencias;
        this.ciclos = ciclos;
        this.historico = historico;
        this.clock = clock;
    }

    public BancoDeHorasMesView mes(UUID usuarioId, YearMonth mes) {
        LocalDate hoje = LocalDate.now(clock);
        AuditoriaMesView doMes = auditoria.mes(usuarioId, mes);
        Calendario calendario = new Calendario(usuarioId, mes, doMes);

        Map<LocalDate, Integer> diferencaDoDia = new TreeMap<>();
        for (AuditoriaMesView.Dia d : doMes.dias()) {
            RegistroJornadaView r = d.registro();
            int valor = carga.doDia(usuarioId, r.data(), r.tipoDia(), r.saldoDiarioSegundos());
            if (valor != 0) {
                diferencaDoDia.put(r.data(), valor);
            }
        }
        Set<LocalDate> registrados = doMes.dias().stream().map(d -> d.registro().data())
                .collect(Collectors.toCollection(TreeSet::new));
        List<LocalDate> semRegistro = new ArrayList<>();
        for (LocalDate d = mes.atDay(1); !d.isAfter(mes.atEndOfMonth()) && d.isBefore(hoje); d = d.plusDays(1)) {
            if (calendario.tipo(d) == TipoDia.UTIL && !registrados.contains(d)) {
                semRegistro.add(d);
            }
        }

        HorarioTrabalho horario = referencia(usuarioId, mes, hoje);
        return new BancoDeHorasMesView(mes, hoje, horario,
                compensacao(usuarioId, mes, hoje, doMes, calendario), doMes, diferencaDoDia, calendario.semExpediente(),
                semRegistro, avisos(usuarioId, mes, hoje), conferencias.situacao(usuarioId, mes),
                ciclos.bancoNoFimDoMes(usuarioId, mes), excluidos(usuarioId, mes, registrados),
                avisosDoHorario(usuarioId, horario, hoje));
    }

    /** Os avisos da lei do horário, para o período em que ele vale (de hoje, ou do início dele, até o próximo). */
    private List<AvisoLegal> avisosDoHorario(UUID usuarioId, HorarioTrabalho horario, LocalDate hoje) {
        LocalDate proxima = regras.horarios(usuarioId).stream().map(HorarioTrabalho::vigenteDesde)
                .filter(d -> d.isAfter(horario.vigenteDesde())).min(LocalDate::compareTo).orElse(null);
        if (proxima != null && !proxima.isAfter(hoje)) {
            return List.of(); // um horário que já deixou de valer
        }
        LocalDate inicio = horario.vigenteDesde().isAfter(hoje) ? horario.vigenteDesde() : hoje;
        return lei.avaliarHorario(horario, inicio, proxima);
    }

    /** As exclusões de dias que hoje não têm registro (a mais recente de cada dia). */
    private List<AjusteJornada> excluidos(UUID usuarioId, YearMonth mes, Set<LocalDate> registrados) {
        Map<LocalDate, AjusteJornada> porDia = new TreeMap<>();
        for (AjusteJornada a : historico.listarPorPeriodo(usuarioId, mes.atDay(1), mes.atEndOfMonth())) {
            if (a.origem() == AjusteJornada.Origem.EXCLUSAO && !registrados.contains(a.data())) {
                porDia.putIfAbsent(a.data(), a); // a lista vem da mais recente para a mais antiga
            }
        }
        return List.copyOf(porDia.values());
    }

    /** A compensação do mês (também usada pelo aviso no sino). */
    public Compensacao compensacao(UUID usuarioId, YearMonth mes) {
        LocalDate hoje = LocalDate.now(clock);
        AuditoriaMesView doMes = auditoria.mes(usuarioId, mes);
        return compensacao(usuarioId, mes, hoje, doMes, new Calendario(usuarioId, mes, doMes));
    }

    /** Os avisos da lei sobre os dias trabalhados do mês (até hoje). */
    public List<AvisoLegal> avisos(UUID usuarioId, YearMonth mes, LocalDate hoje) {
        return avisos(usuarioId, mes.atDay(1), mes.atEndOfMonth(), hoje);
    }

    /** Os avisos da lei sobre os dias trabalhados do período (até hoje). */
    public List<AvisoLegal> avisos(UUID usuarioId, LocalDate inicio, LocalDate fim, LocalDate hoje) {
        List<LeiDaJornada.DiaTrabalhado> dias = registros
                .listarPorPeriodo(usuarioId, inicio.minusDays(6), fim).stream()
                .map(ConsultarBancoDeHorasUseCase::trabalhado)
                .toList();
        return lei.avaliarDias(dias, inicio, fim, hoje);
    }

    private static LeiDaJornada.DiaTrabalhado trabalhado(RegistroJornada r) {
        return new LeiDaJornada.DiaTrabalhado(r.getDataReferencia(), r.getBatidas().horarios(), r.getSegundosTrabalhados());
    }

    private HorarioTrabalho referencia(UUID usuarioId, YearMonth mes, LocalDate hoje) {
        LocalDate data = YearMonth.from(hoje).equals(mes) ? hoje : mes.atEndOfMonth();
        return regras.horario(usuarioId, data);
    }

    private Compensacao compensacao(UUID usuarioId, YearMonth mes, LocalDate hoje, AuditoriaMesView doMes,
                                    Calendario calendario) {
        HorarioTrabalho horario = referencia(usuarioId, mes, hoje);
        int diasUteis = 0;
        int prevista = 0;
        for (LocalDate d = mes.atDay(1); !d.isAfter(mes.atEndOfMonth()); d = d.plusDays(1)) {
            if (calendario.tipo(d) == TipoDia.UTIL) {
                diasUteis++;
                prevista += carga.previstoNoDia(usuarioId, d);
            }
        }
        int contada = 0;
        int contados = 0;
        int semExpediente = 0;
        for (AuditoriaMesView.Dia dia : doMes.dias()) {
            RegistroJornadaView r = dia.registro();
            if (r.status() != StatusJornada.FECHADA || r.saldoDiarioSegundos() == null) {
                continue;
            }
            if (r.tipoDia() == TipoDia.UTIL) {
                contados++;
                contada += carga.doDia(usuarioId, r.data(), r.tipoDia(), r.saldoDiarioSegundos());
            }
            if (calendario.semExpediente().contains(r.data())) {
                semExpediente += r.saldoDiarioSegundos();
            }
        }
        // mês que terminou: o que de fato entrou no banco; mês corrente: a previsão com os dias úteis que faltam
        boolean terminou = mes.isBefore(YearMonth.from(hoje));
        int debito = Math.max(0, -(terminou ? contada : prevista));
        int creditoDoFimDeSemana = Math.max(0, semExpediente);
        int compensado = Math.min(debito, creditoDoFimDeSemana);
        int paraOBanco = creditoDoFimDeSemana - compensado;
        int falta = debito - compensado;

        List<Bloco> restantes = new ArrayList<>();
        LocalDate desde = hoje.isAfter(mes.atDay(1)) ? hoje : mes.atDay(1);
        LocalDate inicioBloco = null;
        for (LocalDate d = desde; !d.isAfter(mes.atEndOfMonth()); d = d.plusDays(1)) {
            boolean livre = calendario.semExpediente().contains(d);
            if (livre && inicioBloco == null) {
                inicioBloco = d;
            }
            if (!livre && inicioBloco != null) {
                restantes.add(new Bloco(inicioBloco, d.minusDays(1)));
                inicioBloco = null;
            }
        }
        if (inicioBloco != null) {
            restantes.add(new Bloco(inicioBloco, mes.atEndOfMonth()));
        }
        return new Compensacao(horario.cargaSemanalSegundos(), horario.somaSemanalSegundos(),
                horario.diferencaSemanalSegundos(), horario.diferencaPorDiaSegundos(), horario.diasComExpediente(),
                horario.cargaSemanalInformadaSegundos() != null, diasUteis, contados, prevista, contada, semExpediente,
                compensado, paraOBanco, falta, restantes);
    }

    /**
     * O tipo de cada dia do mês pelo horário de cada data e pelo calendário já carregado (feriados e ausências), sem
     * uma consulta por dia. Mesma precedência do classificador: feriado, sem expediente, ausência, útil.
     */
    private final class Calendario {

        private final Map<LocalDate, TipoDia> tipos = new TreeMap<>();
        private final Set<LocalDate> semExpediente = new TreeSet<>();

        Calendario(UUID usuarioId, YearMonth mes, AuditoriaMesView doMes) {
            Map<LocalDate, Feriado> feriados = doMes.feriados().stream()
                    .collect(Collectors.toMap(Feriado::data, Function.identity(), (a, b) -> a));
            Map<LocalDate, TipoDia> registrados = doMes.dias().stream()
                    .collect(Collectors.toMap(d -> d.registro().data(), d -> d.registro().tipoDia(), (a, b) -> a));
            for (LocalDate d = mes.atDay(1); !d.isAfter(mes.atEndOfMonth()); d = d.plusDays(1)) {
                LocalDate data = d;
                boolean expediente = regras.horario(usuarioId, data).temExpediente(data);
                if (!expediente) {
                    semExpediente.add(data);
                }
                TipoDia tipo = registrados.get(data);
                if (tipo == null) {
                    boolean ausente = doMes.ausencias().stream().anyMatch((Ausencia a) -> a.contem(data));
                    tipo = feriados.containsKey(data) ? TipoDia.FERIADO
                            : !expediente ? TipoDia.FIM_DE_SEMANA
                            : ausente ? TipoDia.AUSENCIA : TipoDia.UTIL;
                }
                tipos.put(data, tipo);
            }
        }

        TipoDia tipo(LocalDate data) {
            return tipos.get(data);
        }

        Set<LocalDate> semExpediente() {
            return semExpediente;
        }
    }
}
