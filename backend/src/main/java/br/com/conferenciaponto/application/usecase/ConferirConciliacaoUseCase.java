package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.evento.ConciliacaoAtualizadaEvento;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.model.Divergencia;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.RelatorioRh;
import br.com.conferenciaponto.domain.model.StatusDivergencia;
import br.com.conferenciaponto.domain.model.StatusRelatorioRh;
import br.com.conferenciaponto.domain.model.TipoNotificacao;
import br.com.conferenciaponto.domain.model.Usuario;
import br.com.conferenciaponto.domain.port.DivergenciaRepository;
import br.com.conferenciaponto.domain.port.RegistroJornadaRepository;
import br.com.conferenciaponto.domain.port.RelatorioRhRepository.DiaVigente;
import br.com.conferenciaponto.domain.port.RelatorioRhRepository;
import br.com.conferenciaponto.domain.port.UsuarioRepository;
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
import java.util.List;
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
    private final UsuarioRepository usuarios;
    private final RegrasJornada regras;
    private final ComparadorConciliacaoService comparador;
    private final NotificacoesUseCase notificacoes;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public ConferirConciliacaoUseCase(RelatorioRhRepository relatorios, DivergenciaRepository divergencias,
                                      RegistroJornadaRepository registros, UsuarioRepository usuarios,
                                      RegrasJornada regras, ComparadorConciliacaoService comparador,
                                      NotificacoesUseCase notificacoes, ApplicationEventPublisher eventos,
                                      Clock clock) {
        this.relatorios = relatorios;
        this.divergencias = divergencias;
        this.registros = registros;
        this.usuarios = usuarios;
        this.regras = regras;
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

        Resultado somado(Resultado outro) {
            return new Resultado(diasConferidos + outro.diasConferidos, novas + outro.novas,
                    resolvidas + outro.resolvidas);
        }
    }

    /** Relatório recém-enviado: confere o período dele, conclui e avisa no sino. */
    @Transactional
    public Resultado processarRelatorio(UUID relatorioId) {
        RelatorioRh relatorio = relatorios.buscarPorId(relatorioId).orElseThrow(() ->
                new RecursoNaoEncontradoException("RELATORIO_NAO_ENCONTRADO", "Relatório do RH não encontrado."));
        UUID usuarioId = relatorio.usuarioId();
        LocalDate inicio = relatorio.periodoInicio();
        LocalDate fim = relatorio.ultimoDiaConferido();
        Resultado resultado = conferirSemAviso(usuarioId, inicio, fim, "Igual ao RH");
        int pendentes = divergencias.listar(usuarioId, StatusDivergencia.PENDENTE, inicio, fim).size();
        relatorios.atualizar(relatorio.concluido(pendentes, clock.instant()));

        String periodo = "%s a %s".formatted(DATA.format(inicio), DATA.format(fim));
        notificacoes.notificar(usuarioId, TipoNotificacao.CONCILIACAO, "CONCILIACAO:" + relatorioId,
                pendentes == 0 ? "Relatório do RH conferido: tudo igual" : "Relatório do RH: %d dia(s) com diferença".formatted(pendentes),
                pendentes == 0
                        ? "Período %s: o sistema bate com o RH em todos os %d dias.".formatted(periodo, relatorio.diasLidos())
                        : "Período %s: %d dia(s) com diferença entre o sistema e o RH. Abra \"Conferir com o RH\" para decidir."
                        .formatted(periodo, pendentes),
                "/conciliacao");
        eventos.publishEvent(new ConciliacaoAtualizadaEvento(usuarioId,
                "Relatório do RH (%s) conferido".formatted(periodo), totalPendentes(usuarioId)));
        return resultado;
    }

    /** Relatórios recebidos que ainda não foram conferidos (o sistema foi desligado antes de terminar). */
    @Transactional(readOnly = true)
    public List<UUID> relatoriosPorConferir() {
        return usuarios.listar().stream()
                .flatMap(u -> relatorios.listar(u.id()).stream())
                .filter(r -> r.status() == StatusRelatorioRh.PROCESSANDO)
                .map(RelatorioRh::id)
                .toList();
    }

    /** Falha no processamento em segundo plano: marca o relatório (transação própria). */
    @Transactional
    public void marcarErro(UUID relatorioId, String mensagem) {
        relatorios.buscarPorId(relatorioId).ifPresent(r -> {
            if (r.status() == StatusRelatorioRh.PROCESSANDO) {
                relatorios.atualizar(r.comErro(mensagem, clock.instant()));
            }
            eventos.publishEvent(new ConciliacaoAtualizadaEvento(r.usuarioId(), "Falha ao conferir o relatório do RH",
                    totalPendentes(r.usuarioId())));
        });
    }

    /** Reconfere, de cada usuário, todas as datas cobertas pelos relatórios dele. */
    @Transactional
    public Resultado conferirTudo() {
        Resultado total = Resultado.NADA;
        for (Usuario u : usuarios.listar()) {
            if (u.isTitular()) {
                total = total.somado(conferirTudo(u.id()));
            }
        }
        return total;
    }

    /** Reconfere todas as datas cobertas pelos relatórios do usuário. */
    @Transactional
    public Resultado conferirTudo(UUID usuarioId) {
        return relatorios.abrangencia(usuarioId)
                .map(a -> conferir(usuarioId, a.inicio(), a.fim(), "Igual ao RH na reconferência"))
                .orElse(Resultado.NADA);
    }

    /** Reconfere o período para todos os usuários (um feriado mudou). */
    @Transactional
    public Resultado conferirDeTodos(LocalDate inicio, LocalDate fim, String motivo) {
        Resultado total = Resultado.NADA;
        for (Usuario u : usuarios.listar()) {
            if (u.isTitular()) {
                total = total.somado(conferir(u.id(), inicio, fim, motivo));
            }
        }
        return total;
    }

    /** Reconfere datas específicas (um dia mudou). */
    @Transactional
    public Resultado conferirDatas(UUID usuarioId, Collection<LocalDate> datas, String motivo) {
        if (datas.isEmpty()) {
            return Resultado.NADA;
        }
        return conferir(usuarioId, Collections.min(datas), Collections.max(datas), motivo);
    }

    @Transactional
    public Resultado conferir(UUID usuarioId, LocalDate inicio, LocalDate fim, String motivoResolucao) {
        Resultado r = conferirSemAviso(usuarioId, inicio, fim, motivoResolucao);
        if (r.mudouAlgo()) {
            eventos.publishEvent(new ConciliacaoAtualizadaEvento(usuarioId,
                    "%d nova(s), %d resolvida(s)".formatted(r.novas(), r.resolvidas()), totalPendentes(usuarioId)));
        }
        return r;
    }

    private Resultado conferirSemAviso(UUID usuarioId, LocalDate inicio, LocalDate fim, String motivoResolucao) {
        Map<LocalDate, DiaVigente> vigentes = relatorios.vigentes(usuarioId, inicio, fim).stream()
                .collect(Collectors.toMap(v -> v.dia().data(), Function.identity()));
        Map<LocalDate, RegistroJornada> locais = registros.listarPorPeriodo(usuarioId, inicio, fim).stream()
                .collect(Collectors.toMap(RegistroJornada::getDataReferencia, Function.identity()));
        Set<LocalDate> datas = new HashSet<>(vigentes.keySet());
        Map<LocalDate, Divergencia> existentes = divergencias.listar(usuarioId, null, inicio, fim).stream()
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
                    Optional.ofNullable(locais.get(data)), regras.classificar(usuarioId, data));
            if (achado.isPresent()) {
                if (existente == null) {
                    divergencias.salvar(Divergencia.nova(usuarioId, data, vigente.relatorioId(), achado.get(),
                            clock.instant()));
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

    private int totalPendentes(UUID usuarioId) {
        return divergencias.listar(usuarioId, StatusDivergencia.PENDENTE, null, null).size();
    }
}
