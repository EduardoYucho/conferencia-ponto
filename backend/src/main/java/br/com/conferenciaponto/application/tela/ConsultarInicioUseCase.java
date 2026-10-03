package br.com.conferenciaponto.application.tela;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.tela.DiaView.Situacao;
import br.com.conferenciaponto.application.tela.InicioView.Banco;
import br.com.conferenciaponto.application.tela.InicioView.Hoje;
import br.com.conferenciaponto.application.tela.InicioView.Mes;
import br.com.conferenciaponto.application.tela.InicioView.Pendencia;
import br.com.conferenciaponto.application.usecase.ConsultarJornadaUseCase;
import br.com.conferenciaponto.application.usecase.GerenciarCicloBancoUseCase;
import br.com.conferenciaponto.application.view.CicloBancoView;
import br.com.conferenciaponto.application.view.PresencaView;
import br.com.conferenciaponto.domain.exception.DominioException;
import br.com.conferenciaponto.domain.model.Batidas;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.SaldoMensal;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.TipoBatida;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.port.DivergenciaRepository;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * A tela "Início": como está o dia de hoje (trabalhando? quanto falta? qual é a próxima batida?), o que espera
 * uma ação da pessoa e os saldos do mês e do banco de horas. Nada disso é calculado no navegador.
 */
@Service
public class ConsultarInicioUseCase {

    /** Quantos dias para trás entram nas pendências e em "últimos dias". */
    private static final int JANELA_DIAS = 45;
    private static final int ULTIMOS_DIAS = 5;

    private final MontadorDeDias montador;
    private final ConsultarJornadaUseCase consultar;
    private final GerenciarCicloBancoUseCase ciclos;
    private final DivergenciaRepository divergencias;
    private final RegrasJornada regras;
    private final Clock clock;

    public ConsultarInicioUseCase(MontadorDeDias montador, ConsultarJornadaUseCase consultar,
                                  GerenciarCicloBancoUseCase ciclos, DivergenciaRepository divergencias,
                                  RegrasJornada regras, Clock clock) {
        this.montador = montador;
        this.consultar = consultar;
        this.ciclos = ciclos;
        this.divergencias = divergencias;
        this.regras = regras;
        this.clock = clock;
    }

    /**
     * @param outraPessoa primeiro nome de quem é o ponto, quando quem olha não é o dono ("Maria está
     *                    trabalhando"); {@code null} para o próprio ("Você está trabalhando")
     */
    @Transactional
    public InicioView agora(UUID usuarioId, MontadorDeDias.Quem quem, String outraPessoa) {
        LocalDate hoje = LocalDate.now(clock);
        LocalTime agora = LocalTime.now(clock).truncatedTo(ChronoUnit.SECONDS);

        List<DiaView> janela = montador.montar(usuarioId, hoje.minusDays(JANELA_DIAS), hoje, quem);
        DiaView diaDeHoje = janela.get(janela.size() - 1);
        List<DiaView> passados = janela.subList(0, janela.size() - 1);

        SaldoMensal resumo = consultar.saldos(usuarioId, YearMonth.from(hoje)).meses().get(hoje.getMonthValue() - 1);
        int fechados = resumo.diasRegistrados() - resumo.diasEmAberto();
        Mes mes = new Mes(Horas.mes(hoje.getMonthValue()), resumo.saldoMensalSegundos(), fechados,
                sentido(resumo.saldoMensalSegundos(), outraPessoa) + (fechados == 0 ? " · nenhum dia fechado ainda"
                        : ", em %d dia%s fechado%s".formatted(fechados, fechados == 1 ? "" : "s", fechados == 1 ? "" : "s")));

        Banco banco = banco(usuarioId, outraPessoa);

        List<DiaView> ultimos = new ArrayList<>(janela.stream()
                .filter(d -> d.situacao() != Situacao.SEM_EXPEDIENTE || !d.batidas().isEmpty())
                .toList());
        Collections.reverse(ultimos);

        return new InicioView(hoje, agora, Horas.saudacao(agora), Horas.porExtenso(hoje),
                hoje(usuarioId, hoje, agora, diaDeHoje, quem, outraPessoa), mes, banco,
                pendencias(usuarioId, passados, banco), ultimos.stream().limit(ULTIMOS_DIAS).toList());
    }

    // ------------------------------------------------------------------ o dia de hoje

    private Hoje hoje(UUID usuarioId, LocalDate hoje, LocalTime agora, DiaView dia, MontadorDeDias.Quem quem,
                      String outraPessoa) {
        MotorCalculoJornadaService motor = regras.motor(usuarioId, hoje);
        GradeHoraria grade = regras.horario(usuarioId, hoje).gradeDo(hoje).orElse(null);
        TipoDia tipoDia = dia.registro() != null ? dia.registro().tipoDia() : regras.classificar(usuarioId, hoje);
        GradeHoraria gradeDoDia = tipoDia == TipoDia.UTIL ? grade : null;
        List<LocalTime> batidas = dia.batidas().stream().map(DiaView.Batida::horario).toList();
        int n = batidas.size();
        boolean aberto = n % 2 == 1;
        LocalTime ultima = n == 0 ? null : batidas.get(n - 1);
        int periodos = gradeDoDia == null ? 0 : gradeDoDia.periodos().size();
        int previsto = motor.jornadaPrevistaSegundos(tipoDia);

        int trabalhado = dia.trabalhadoSegundos() == null ? 0 : dia.trabalhadoSegundos();
        if (aberto && agora.isAfter(ultima)) {
            trabalhado += (int) ChronoUnit.SECONDS.between(ultima, agora);
        }
        // saldo se o dia fechasse neste instante (com a tolerância): diz quanto falta ou quanto passou
        int saldoAgora;
        if (aberto) {
            Integer simulado = saldoSaindoAs(motor, tipoDia, batidas, agora);
            saldoAgora = simulado != null ? simulado : trabalhado - previsto;
        } else {
            saldoAgora = n == 0 ? -previsto : (dia.saldoSegundos() == null ? 0 : dia.saldoSegundos());
        }
        int faltam = Math.max(0, -saldoAgora);
        int percentual = previsto == 0 ? (n > 0 ? 100 : 0)
                : (int) Math.max(0, Math.min(100, Math.round((previsto - faltam) * 100.0 / previsto)));

        String sujeito = outraPessoa == null ? "Você" : outraPessoa;
        PresencaView.Situacao situacao;
        String titulo;
        String detalhe = null;
        String resumo = null;

        if (dia.marcador() != null && !dia.marcador().feriado()) {
            situacao = PresencaView.Situacao.AUSENCIA;
            titulo = "Hoje " + (outraPessoa == null ? "você" : outraPessoa) + " está " + comoAusencia(dia.marcador().tipo());
            detalhe = dia.marcador().fim().isAfter(hoje) ? "até " + Horas.diaMes(dia.marcador().fim()) : null;
            resumo = n > 0 ? "Há batidas hoje: o tempo trabalhado fica inteiro a favor." : "O dia não conta como falta.";
        } else if (aberto) {
            situacao = PresencaView.Situacao.TRABALHANDO;
            titulo = sujeito + " está trabalhando";
            detalhe = "desde " + Horas.hora(ultima);
            resumo = resumoTrabalhando(motor, tipoDia, gradeDoDia, batidas, agora, previsto, saldoAgora);
        } else if (n > 0) {
            boolean haPeriodoPelaFrente = gradeDoDia != null && n / 2 < periodos && agora.isBefore(fim(gradeDoDia));
            if (haPeriodoPelaFrente) {
                situacao = PresencaView.Situacao.INTERVALO;
                titulo = sujeito + " está em intervalo";
                detalhe = "desde " + Horas.hora(ultima);
                resumo = faltam > 0 ? "Faltam %s para completar o dia.".formatted(Horas.duracao(faltam)) : null;
            } else {
                situacao = PresencaView.Situacao.ENCERROU;
                titulo = "Dia encerrado";
                detalhe = "saída às " + Horas.hora(ultima);
                resumo = previsto == 0 ? "Dia sem jornada: o tempo trabalhado ficou inteiro a favor."
                        : saldoAgora == 0 ? "O dia fechou em dia."
                        : "O dia fechou com %s.".formatted(Horas.saldoComSentido(saldoAgora));
            }
        } else if (dia.marcador() != null) {
            situacao = PresencaView.Situacao.FERIADO;
            titulo = "Hoje é feriado";
            detalhe = dia.marcador().descricao();
        } else if (gradeDoDia == null) {
            situacao = PresencaView.Situacao.SEM_EXPEDIENTE;
            titulo = "Hoje não há expediente";
            detalhe = Horas.diaDaSemana(hoje);
        } else if (agora.isBefore(inicio(gradeDoDia))) {
            situacao = PresencaView.Situacao.ANTES_DO_EXPEDIENTE;
            titulo = "O expediente começa às " + Horas.hora(inicio(gradeDoDia));
            resumo = jornadaDeHoje(gradeDoDia);
        } else if (!agora.isAfter(fim(gradeDoDia))) {
            situacao = PresencaView.Situacao.SEM_BATIDA;
            titulo = sujeito + " ainda não bateu o ponto hoje";
            detalhe = "o expediente começou às " + Horas.hora(inicio(gradeDoDia));
            resumo = jornadaDeHoje(gradeDoDia);
        } else {
            situacao = PresencaView.Situacao.NAO_REGISTROU;
            titulo = sujeito + " não registrou ponto hoje";
            detalhe = "o expediente terminou às " + Horas.hora(fim(gradeDoDia));
            resumo = outraPessoa == null
                    ? "Se você trabalhou hoje, use \"Esqueci de bater ou bati errado\" para informar as batidas." : null;
        }

        // batidas do dia completas: com expediente, todas as da grade; a 3ª entrada/saída é exceção (vem do ajuste)
        boolean completa = !aberto && n > 0 && (n >= TipoBatida.MAXIMO || (periodos > 0 ? n >= 2 * periodos : n >= 4));
        boolean podeBater = quem.podeEditar() && !completa;
        String rotuloBotao = completa ? "Jornada completa" : NomesDeBatida.botao(gradeDoDia, n);
        String aviso = !quem.podeEditar() || !completa ? null
                : "As batidas de hoje estão completas. Para incluir outra, use \"Esqueci de bater ou bati errado\".";
        String proxima = completa ? null : NomesDeBatida.de(gradeDoDia, n);
        LocalTime proximaPrevista = completa || gradeDoDia == null || n >= gradeDoDia.marcas().size() ? null
                : gradeDoDia.marcas().get(n).horario();

        return new Hoje(situacao, titulo, detalhe, aberto, trabalhado, previsto, percentual, resumo, proxima,
                proximaPrevista, podeBater, rotuloBotao, aviso, dia);
    }

    /** "Faltam 4h 06min. Saindo às 17:48 o dia fecha sem dever nem sobrar." */
    private static String resumoTrabalhando(MotorCalculoJornadaService motor, TipoDia tipoDia, GradeHoraria grade,
                                            List<LocalTime> batidas, LocalTime agora, int previsto, int saldoAgora) {
        if (previsto == 0) {
            return "Hoje não conta jornada: todo o tempo trabalhado fica a favor.";
        }
        if (saldoAgora > 0) {
            return "A jornada de hoje já passou em %s.".formatted(Horas.duracao(saldoAgora));
        }
        if (saldoAgora == 0) {
            return "A jornada de hoje está completa: já dá para bater a saída.";
        }
        String faltam = "Faltam %s.".formatted(Horas.duracao(-saldoAgora));
        boolean ultimoPeriodo = grade != null && batidas.size() == 2 * grade.periodos().size() - 1;
        if (!ultimoPeriodo) {
            return "Faltam %s para completar o dia.".formatted(Horas.duracao(-saldoAgora));
        }
        LocalTime fim = fim(grade);
        if (!agora.isBefore(fim)) {
            return faltam;
        }
        Integer noFim = saldoSaindoAs(motor, tipoDia, batidas, fim);
        if (noFim == null) {
            return faltam;
        }
        if (noFim == 0) {
            return "%s Saindo às %s o dia fecha sem dever nem sobrar.".formatted(faltam, Horas.hora(fim));
        }
        if (noFim > 0) {
            return "%s Saindo às %s o dia fecha com %s.".formatted(faltam, Horas.hora(fim), Horas.saldoComSentido(noFim));
        }
        // chegou depois do horário (ou saiu mais no intervalo): para zerar, sai mais tarde
        long ateMeiaNoite = ChronoUnit.SECONDS.between(fim, LocalTime.MAX);
        if (-noFim >= ateMeiaNoite) {
            return faltam;
        }
        LocalTime paraZerar = fim.plusSeconds(-noFim);
        Integer conferido = saldoSaindoAs(motor, tipoDia, batidas, paraZerar);
        return conferido != null && conferido >= 0
                ? "%s Para não ficar devendo, a saída é às %s.".formatted(faltam, Horas.hora(paraZerar.plusSeconds(59)))
                : faltam;
    }

    /** Saldo do dia se a próxima batida (uma saída) fosse neste horário; {@code null} se não dá para simular. */
    private static Integer saldoSaindoAs(MotorCalculoJornadaService motor, TipoDia tipoDia, List<LocalTime> batidas,
                                         LocalTime saida) {
        if (batidas.isEmpty() || batidas.size() % 2 == 0 || !saida.isAfter(batidas.get(batidas.size() - 1))) {
            return null;
        }
        try {
            List<LocalTime> comSaida = new ArrayList<>(batidas);
            comSaida.add(saida.truncatedTo(ChronoUnit.SECONDS));
            return motor.apurar(tipoDia, Batidas.deHorarios(comSaida)).saldo().orElse(null);
        } catch (DominioException | IllegalArgumentException e) {
            return null; // batidas que o domínio não aceita simular: a tela mostra só o que é certo
        }
    }

    private static String jornadaDeHoje(GradeHoraria grade) {
        return "A jornada de hoje é de %s (%s).".formatted(Horas.duracao(grade.cargaHorariaSegundos()),
                ConsultarMeuPontoUseCase.texto(grade));
    }

    private static String comoAusencia(String tipo) {
        return switch (tipo) {
            case "FERIAS" -> "de férias";
            case "FOLGA" -> "de folga";
            case "LICENCA" -> "de licença";
            case "ATESTADO" -> "de atestado";
            default -> "com o dia abonado";
        };
    }

    private static LocalTime inicio(GradeHoraria grade) {
        return grade.periodos().get(0).entrada();
    }

    private static LocalTime fim(GradeHoraria grade) {
        return grade.periodos().get(grade.periodos().size() - 1).saida();
    }

    // ------------------------------------------------------------------ saldos

    private Banco banco(UUID usuarioId, String outraPessoa) {
        try {
            return TextosDoBanco.resumo(ciclos.atual(usuarioId), outraPessoa);
        } catch (DominioException semCiclo) {
            return null;
        }
    }

    private static String sentido(int saldo, String outraPessoa) {
        return TextosDoBanco.sentido(saldo, outraPessoa);
    }

    // ------------------------------------------------------------------ o que pede atenção

    private List<Pendencia> pendencias(UUID usuarioId, List<DiaView> passados, Banco banco) {
        List<Pendencia> pendencias = new ArrayList<>();

        List<DiaView> incompletos = passados.stream().filter(d -> d.situacao() == Situacao.INCOMPLETO).toList();
        if (incompletos.size() == 1) {
            DiaView d = incompletos.get(0);
            pendencias.add(new Pendencia(Pendencia.Tipo.DIA_INCOMPLETO, d.rotuloLongo() + ": faltou bater a saída",
                    "Corrigir", d.data(), 1));
        } else if (!incompletos.isEmpty()) {
            pendencias.add(new Pendencia(Pendencia.Tipo.DIA_INCOMPLETO,
                    "%d dias com batida faltando".formatted(incompletos.size()), "Ver os dias", null, incompletos.size()));
        }

        List<DiaView> semRegistro = passados.stream().filter(d -> d.situacao() == Situacao.SEM_REGISTRO).toList();
        if (semRegistro.size() == 1) {
            DiaView d = semRegistro.get(0);
            pendencias.add(new Pendencia(Pendencia.Tipo.DIA_SEM_REGISTRO, d.rotuloLongo() + ": dia de trabalho sem batidas",
                    "Resolver", d.data(), 1));
        } else if (!semRegistro.isEmpty()) {
            pendencias.add(new Pendencia(Pendencia.Tipo.DIA_SEM_REGISTRO,
                    "%d dias de trabalho sem batidas".formatted(semRegistro.size()), "Ver os dias", null,
                    semRegistro.size()));
        }

        int diferencas = divergencias.listar(usuarioId, StatusDivergencia.PENDENTE, null, null).size();
        if (diferencas > 0) {
            pendencias.add(new Pendencia(Pendencia.Tipo.DIVERGENCIAS_RH,
                    diferencas == 1 ? "RH: 1 dia diferente do ponto" : "RH: %d dias diferentes do ponto".formatted(diferencas),
                    "Conferir", null, diferencas));
        }

        if (banco != null && banco.urgente() && banco.prazo() != null) {
            pendencias.add(new Pendencia(Pendencia.Tipo.BANCO_PRAZO, "Banco de horas: " + lower(banco.prazo()),
                    "Ver o banco", null, 1));
        }
        return pendencias;
    }

    private static String lower(String texto) {
        return Character.toLowerCase(texto.charAt(0)) + texto.substring(1);
    }
}
