package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.CalendarioAlteradoEvento;
import br.com.conferenciaponto.application.evento.ConciliacaoAtualizadaEvento;
import br.com.conferenciaponto.application.evento.JornadaAtualizadaEvento;
import br.com.conferenciaponto.application.evento.OrigemAtualizacao;
import br.com.conferenciaponto.application.view.RegistroJornadaView;
import br.com.conferenciaponto.domain.exception.ConflitoException;
import br.com.conferenciaponto.domain.exception.DominioException;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.DiaRelatorioRh;
import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.OcorrenciaRh;
import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.TipoAusencia;
import br.com.conferenciaponto.domain.model.TipoDivergencia;
import br.com.conferenciaponto.domain.port.CalendarioFeriados;
import br.com.conferenciaponto.domain.port.DivergenciaRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.port.RelatorioRhRepository;
import br.com.conferenciaponto.domain.port.RelatorioRhRepository.DiaVigente;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Decisões do usuário sobre as divergências com o RH: "Aceitar dados do RH" (a conferência passa a ser
 * igual ao relatório, com histórico), "Manter dados locais" (a diferença fica registrada) e o aceite em
 * lote. O "Ajuste manual" usa a tela de ajuste de batidas; a divergência se resolve sozinha quando o
 * dia passa a bater com o RH.
 */
@Service
public class ResolverDivergenciaUseCase {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final DivergenciaRepository divergencias;
    private final RelatorioRhRepository relatorios;
    private final RegistroJornadaRepository registros;
    private final AjustarBatidasUseCase ajustar;
    private final GerenciarAusenciasUseCase ausencias;
    private final CalendarioFeriados feriados;
    private final ClassificadorDiaService classificador;
    private final MotorCalculoJornadaService motor;
    private final ConferirConciliacaoUseCase conferir;
    private final ApplicationEventPublisher eventos;
    private final TransactionTemplate transacao;
    private final Clock clock;

    public ResolverDivergenciaUseCase(DivergenciaRepository divergencias, RelatorioRhRepository relatorios,
                                      RegistroJornadaRepository registros, AjustarBatidasUseCase ajustar,
                                      GerenciarAusenciasUseCase ausencias, CalendarioFeriados feriados,
                                      ClassificadorDiaService classificador, MotorCalculoJornadaService motor,
                                      ConferirConciliacaoUseCase conferir, ApplicationEventPublisher eventos,
                                      PlatformTransactionManager transacoes, Clock clock) {
        this.divergencias = divergencias;
        this.relatorios = relatorios;
        this.registros = registros;
        this.ajustar = ajustar;
        this.ausencias = ausencias;
        this.feriados = feriados;
        this.classificador = classificador;
        this.motor = motor;
        this.conferir = conferir;
        this.eventos = eventos;
        this.transacao = transacoes == null ? null : new TransactionTemplate(transacoes);
        this.clock = clock;
    }

    /** Resultado do aceite em lote. */
    public record ResultadoLote(int aceitas, List<Falha> falhas) {
        public record Falha(LocalDate data, TipoDivergencia tipo, String mensagem) {
        }
    }

    /**
     * Aceita os dados do RH para o dia.
     *
     * @return a divergência depois do aceite (ACEITO_RH, ou pendente de outro tipo se sobrou diferença)
     */
    @Transactional
    public Divergencia aceitarRh(UUID id, String usuario) {
        Divergencia d = pendente(id);
        DiaVigente vigente = relatorios.vigentes(d.data(), d.data()).stream().findFirst()
                .orElseThrow(() -> new ConflitoException("DIVERGENCIA_SEM_RELATORIO",
                        "O relatório do RH deste dia não está mais disponível. Use \"Reconferir\"."));
        RelatorioRh relatorio = relatorios.buscarPorId(vigente.relatorioId()).orElseThrow();
        String justificativa = "Conforme relatório do RH emitido em %s (conciliação)"
                .formatted(DATA.format(relatorio.emitidoEm()));

        LocalDate ate = d.data();
        LocalDate de = d.data();
        switch (d.tipo()) {
            case TIPO_DIA -> {
                exigirAceitavel(d);
                OcorrenciaRh ocorrencia = vigente.dia().tipoOcorrencia().orElseThrow(() -> new RegraNegocioException(
                        "DIVERGENCIA_NAO_ACEITAVEL", "O RH não indica feriado nem ausência neste dia."));
                if (ocorrencia == OcorrenciaRh.FERIADO) {
                    aceitarFeriado(d.data(), vigente.dia());
                } else {
                    TipoAusencia tipo = ocorrencia.ausencia().orElseThrow(() -> new RegraNegocioException(
                            "DIVERGENCIA_NAO_ACEITAVEL", "Ocorrência do RH não reconhecida: \"%s\"."
                            .formatted(vigente.dia().ocorrencia())));
                    List<LocalDate> periodo = periodoDaOcorrencia(vigente, ocorrencia);
                    for (LocalDate dia : periodo) {
                        ausencias.registrarDia(dia, tipo, "Conforme RH", usuario);
                    }
                    de = periodo.get(0);
                    ate = periodo.get(periodo.size() - 1);
                }
            }
            case SOMENTE_RH, BATIDA_FALTANDO, BATIDA_SOBRANDO, HORARIO_DIFERENTE, DIFERENCA_SEGUNDOS -> {
                exigirAceitavel(d);
                if (vigente.dia().horarios().isEmpty()) {
                    throw new RegraNegocioException("DIVERGENCIA_NAO_ACEITAVEL", "O RH não tem batidas neste dia.");
                }
                ajustar.conformeRh(d.data(), vigente.dia().horarios(), justificativa, usuario);
            }
            case SOMENTE_LOCAL, SALDO -> {
                exigirAceitavel(d);
                throw new RegraNegocioException("DIVERGENCIA_NAO_ACEITAVEL", "Não há o que trazer do RH neste dia.");
            }
        }
        divergencias.salvar(d.resolvida(StatusDivergencia.ACEITO_RH, usuario, null, clock.instant()));
        String motivo = "Aceito junto com %s (dados do RH)".formatted(DATA.format(d.data()));
        conferir.conferir(de, ate, motivo);
        return divergencias.buscarPorId(id).orElseThrow();
    }

    /** "Manter dados locais": a conferência fica como está e a diferença deixa de ser pendência. */
    @Transactional
    public Divergencia manterLocal(UUID id, String observacao, String usuario) {
        Divergencia d = pendente(id);
        if (observacao != null && observacao.strip().length() > 300) {
            throw new RegraNegocioException("OBSERVACAO_LONGA", "A observação pode ter no máximo 300 caracteres.");
        }
        Divergencia mantida = d.resolvida(StatusDivergencia.MANTIDO_LOCAL, usuario, observacao, clock.instant());
        divergencias.salvar(mantida);
        publicar("Divergência de %s mantida".formatted(DATA.format(d.data())));
        return mantida;
    }

    /** Volta uma decisão ("manter" ou "resolvida") para pendente. */
    @Transactional
    public Divergencia reabrir(UUID id) {
        Divergencia d = divergencias.buscarPorId(id).orElseThrow(this::naoEncontrada);
        if (d.isPendente()) {
            return d;
        }
        Divergencia reaberta = new Divergencia(d.id(), d.data(), d.relatorioId(), d.tipo(), d.descricao(), d.aceitavel(),
                d.motivoNaoAceitavel(), d.horariosRh(), d.horariosLocal(), d.ocorrenciaRh(), d.tipoDiaLocal(),
                d.saldoRhSegundos(), d.saldoLocalSegundos(), StatusDivergencia.PENDENTE, d.detectadaEm(), null, null,
                null);
        divergencias.salvar(reaberta);
        conferir.conferir(d.data(), d.data(), "Igual ao RH");
        publicar("Divergência de %s reaberta".formatted(DATA.format(d.data())));
        return divergencias.buscarPorId(id).orElseThrow();
    }

    /**
     * Aceita os dados do RH em todas as divergências pendentes dos tipos informados (cada dia na sua
     * transação: um dia que não puder ser aceito não impede os demais).
     */
    public ResultadoLote aceitarEmLote(Set<TipoDivergencia> tipos, LocalDate inicio, LocalDate fim, String usuario) {
        List<Divergencia> alvo = divergencias.listar(StatusDivergencia.PENDENTE, inicio, fim).stream()
                .filter(d -> tipos.contains(d.tipo()) && d.aceitavel())
                .sorted(Comparator.comparing(Divergencia::data))
                .toList();
        int aceitas = 0;
        List<ResultadoLote.Falha> falhas = new ArrayList<>();
        for (Divergencia d : alvo) {
            try {
                Boolean aceita = transacao.execute(status -> {
                    boolean aindaPendente = divergencias.buscarPorId(d.id()).map(Divergencia::isPendente).orElse(false);
                    if (aindaPendente) { // pode ter sido resolvida junto com outro dia (ex.: período de férias)
                        aceitarRh(d.id(), usuario);
                    }
                    return aindaPendente;
                });
                if (Boolean.TRUE.equals(aceita)) {
                    aceitas++;
                }
            } catch (DominioException e) {
                falhas.add(new ResultadoLote.Falha(d.data(), d.tipo(), e.getMessage()));
            } catch (RuntimeException e) {
                falhas.add(new ResultadoLote.Falha(d.data(), d.tipo(), "Erro inesperado: " + e.getMessage()));
            }
        }
        publicar("%d divergência(s) aceita(s) em lote".formatted(aceitas));
        return new ResultadoLote(aceitas, falhas);
    }

    private void aceitarFeriado(LocalDate data, DiaRelatorioRh dia) {
        feriados.cadastrar(data, "Feriado (conforme RH)");
        registros.buscarPorData(data).ifPresent(registro -> {
            if (registro.reclassificar(classificador.classificar(data), motor)) {
                registros.salvar(registro);
                eventos.publishEvent(new JornadaAtualizadaEvento(data, OrigemAtualizacao.CONCILIACAO,
                        RegistroJornadaView.de(registro, motor), "Feriado de %s conforme RH".formatted(DATA.format(data))));
            }
        });
        eventos.publishEvent(new CalendarioAlteradoEvento(data, data));
    }

    /**
     * Dias vizinhos com a mesma ocorrência no relatório (inclusive sábados e domingos, que o RH também
     * marca), para cadastrar o período inteiro de uma vez.
     */
    private List<LocalDate> periodoDaOcorrencia(DiaVigente vigente, OcorrenciaRh ocorrencia) {
        Map<LocalDate, DiaRelatorioRh> dias = relatorios.dias(vigente.relatorioId()).stream()
                .collect(Collectors.toMap(DiaRelatorioRh::data, Function.identity()));
        LocalDate inicio = vigente.dia().data();
        while (mesmaOcorrencia(dias.get(inicio.minusDays(1)), ocorrencia)) {
            inicio = inicio.minusDays(1);
        }
        LocalDate fim = vigente.dia().data();
        while (mesmaOcorrencia(dias.get(fim.plusDays(1)), ocorrencia)) {
            fim = fim.plusDays(1);
        }
        return inicio.datesUntil(fim.plusDays(1)).toList();
    }

    private static boolean mesmaOcorrencia(DiaRelatorioRh dia, OcorrenciaRh ocorrencia) {
        return dia != null && dia.tipoOcorrencia().filter(o -> o == ocorrencia).isPresent();
    }

    private Divergencia pendente(UUID id) {
        Divergencia d = divergencias.buscarPorId(id).orElseThrow(this::naoEncontrada);
        if (!d.isPendente()) {
            throw new ConflitoException("DIVERGENCIA_JA_RESOLVIDA", "Esta divergência já foi resolvida.");
        }
        return d;
    }

    private static void exigirAceitavel(Divergencia d) {
        if (!d.aceitavel()) {
            throw new RegraNegocioException("DIVERGENCIA_NAO_ACEITAVEL", d.motivoNaoAceitavel() != null
                    ? d.motivoNaoAceitavel() : "Esta divergência não pode ser aceita automaticamente.");
        }
    }

    private RecursoNaoEncontradoException naoEncontrada() {
        return new RecursoNaoEncontradoException("DIVERGENCIA_NAO_ENCONTRADA", "Divergência não encontrada.");
    }

    private void publicar(String descricao) {
        eventos.publishEvent(new ConciliacaoAtualizadaEvento(descricao,
                divergencias.listar(StatusDivergencia.PENDENTE, null, null).size()));
    }
}
