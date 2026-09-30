package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.evento.ConciliacaoAtualizadaEvento;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.StatusRelatorioRh;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import br.com.conferenciaponto.domain.port.DivergenciaRepository;
import br.com.conferenciaponto.domain.port.RelatorioRhRepository;
import br.com.conferenciaponto.domain.port.RelatorioRhRepository.DiaVigente;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.ComparadorConciliacaoService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Motor da conciliação: compara cada dia do relatório do RH vigente (o mais recente que cobre a data)
 * com a conferência e mantém a tabela de divergências. Só registra diferenças — nunca altera a
 * conferência (isso é decisão do usuário, em {@link ResolverDivergenciaUseCase}).
 *
 * <p>Roda quando chega um relatório, quando um dia muda (PDF, ajuste, ausência...) e no "Reconferir".
 */
@Service
public class ConferirConciliacaoUseCase {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RelatorioRhRepository relatorios;
    private final DivergenciaRepository divergencias;
    private final RegistroJornadaRepository registros;
    private final ClassificadorDiaService classificador;
    private final ComparadorConciliacaoService comparador;
    private final NotificacoesUseCase notificacoes;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public ConferirConciliacaoUseCase(RelatorioRhRepository relatorios, DivergenciaRepository divergencias,
                                      RegistroJornadaRepository registros, ClassificadorDiaService classificador,
                                      ComparadorConciliacaoService comparador, NotificacoesUseCase notificacoes,
                                      ApplicationEventPublisher eventos, Clock clock) {
        this.relatorios = relatorios;
        this.divergencias = divergencias;
        this.registros = registros;
        this.classificador = classificador;
        this.comparador = comparador;
        this.notificacoes = notificacoes;
        this.eventos = eventos;
        this.clock = clock;
    }

    /** @param novas divergências que apareceram (ou voltaram a ficar pendentes) */
    public record Resultado(int diasConferidos, int novas, int resolvidas) {

        public static final Resultado NADA = new Resultado(0, 0, 0);

        boolean mudouAlgo() {
            return novas > 0 || resolvidas > 0;
        }
    }

    /** Relatório recém-enviado: confere o período dele, conclui e avisa no sino. */
    @Transactional
    public Resultado processarRelatorio(UUID relatorioId) {
        RelatorioRh relatorio = relatorios.buscarPorId(relatorioId).orElseThrow(() ->
                new RecursoNaoEncontradoException("RELATORIO_NAO_ENCONTRADO", "Relatório do RH não encontrado."));
        LocalDate inicio = relatorio.periodoInicio();
        LocalDate fim = relatorio.ultimoDiaConferido();
        Resultado resultado = conferirSemAviso(inicio, fim, "Igual ao RH");
        int pendentes = divergencias.listar(StatusDivergencia.PENDENTE, inicio, fim).size();
        relatorios.atualizar(relatorio.concluido(pendentes, clock.instant()));

        String periodo = "%s a %s".formatted(DATA.format(inicio), DATA.format(fim));
        notificacoes.notificar(TipoNotificacao.CONCILIACAO, "CONCILIACAO:" + relatorioId,
                pendentes == 0 ? "Relatório do RH conferido: tudo igual" : "Relatório do RH: %d divergência(s)".formatted(pendentes),
                pendentes == 0
                        ? "Período %s: a conferência bate com o RH em todos os %d dias.".formatted(periodo, relatorio.diasLidos())
                        : "Período %s: %d dia(s) com diferença entre a conferência e o RH. Abra a Conciliação para decidir."
                        .formatted(periodo, pendentes),
                "/conciliacao");
        eventos.publishEvent(new ConciliacaoAtualizadaEvento("Relatório do RH (%s) conferido".formatted(periodo),
                totalPendentes()));
        return resultado;
    }

    /** Falha no processamento em segundo plano: marca o relatório (transação própria). */
    @Transactional
    public void marcarErro(UUID relatorioId, String mensagem) {
        relatorios.buscarPorId(relatorioId)
                .filter(r -> r.status() == StatusRelatorioRh.PROCESSANDO)
                .ifPresent(r -> relatorios.atualizar(r.comErro(mensagem, clock.instant())));
        eventos.publishEvent(new ConciliacaoAtualizadaEvento("Falha ao conferir o relatório do RH", totalPendentes()));
    }

    /** Reconfere todas as datas cobertas por relatórios. */
    @Transactional
    public Resultado conferirTudo() {
        return relatorios.abrangencia()
                .map(a -> conferir(a.inicio(), a.fim(), "Igual ao RH na reconferência"))
                .orElse(Resultado.NADA);
    }

    /** Reconfere datas específicas (um dia mudou). */
    @Transactional
    public Resultado conferirDatas(Collection<LocalDate> datas, String motivo) {
        if (datas.isEmpty()) {
            return Resultado.NADA;
        }
        return conferir(Collections.min(datas), Collections.max(datas), motivo);
    }

    @Transactional
    public Resultado conferir(LocalDate inicio, LocalDate fim, String motivoResolucao) {
        Resultado r = conferirSemAviso(inicio, fim, motivoResolucao);
        if (r.mudouAlgo()) {
            eventos.publishEvent(new ConciliacaoAtualizadaEvento(
                    "%d nova(s), %d resolvida(s)".formatted(r.novas(), r.resolvidas()), totalPendentes()));
        }
        return r;
    }

    private Resultado conferirSemAviso(LocalDate inicio, LocalDate fim, String motivoResolucao) {
        Map<LocalDate, DiaVigente> vigentes = relatorios.vigentes(inicio, fim).stream()
                .collect(Collectors.toMap(v -> v.dia().data(), Function.identity()));
        Map<LocalDate, RegistroJornada> locais = registros.listarPorPeriodo(inicio, fim).stream()
                .collect(Collectors.toMap(RegistroJornada::getDataReferencia, Function.identity()));
        Set<LocalDate> datas = new HashSet<>(vigentes.keySet());
        Map<LocalDate, Divergencia> existentes = divergencias.listar(null, inicio, fim).stream()
                .collect(Collectors.toMap(Divergencia::data, Function.identity()));
        datas.addAll(existentes.keySet());

        int novas = 0;
        int resolvidas = 0;
        for (LocalDate data : datas) {
            DiaVigente vigente = vigentes.get(data);
            Divergencia existente = existentes.get(data);
            if (vigente == null) { // relatório removido: a divergência pendente perde o sentido
                if (existente != null && existente.isPendente()) {
                    divergencias.excluir(existente.id());
                    resolvidas++;
                }
                continue;
            }
            Optional<Divergencia.Achado> achado = comparador.comparar(vigente.dia(),
                    Optional.ofNullable(locais.get(data)), classificador.classificar(data));
            if (achado.isPresent()) {
                if (existente == null) {
                    divergencias.salvar(Divergencia.nova(data, vigente.relatorioId(), achado.get(), clock.instant()));
                    novas++;
                } else {
                    Divergencia atualizada = existente.atualizada(vigente.relatorioId(), achado.get(), clock.instant());
                    if (!atualizada.equals(existente)) {
                        divergencias.salvar(atualizada);
                        if (atualizada.isPendente() && !existente.isPendente()) {
                            novas++;
                        }
                    }
                }
            } else if (existente != null && existente.isPendente()) {
                divergencias.salvar(existente.resolvida(StatusDivergencia.RESOLVIDA, "sistema", motivoResolucao,
                        clock.instant()));
                resolvidas++;
            }
        }
        return new Resultado(vigentes.size(), novas, resolvidas);
    }

    private int totalPendentes() {
        return divergencias.listar(StatusDivergencia.PENDENTE, null, null).size();
    }
}
