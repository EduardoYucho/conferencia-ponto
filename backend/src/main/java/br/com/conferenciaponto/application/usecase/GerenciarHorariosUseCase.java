package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.TravaDosMesesConferidos;
import br.com.conferenciaponto.application.evento.CalendarioAlteradoEvento;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.AvisoLegal;
import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.StatusCiclo;
import br.com.conferenciaponto.domain.port.CicloBancoRepository;
import br.com.conferenciaponto.domain.port.HorarioTrabalhoRepository;
import br.com.conferenciaponto.domain.service.LeiDaJornada;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Horário de trabalho de cada usuário, com vigência: "a partir de 01/10, entro às 07:00". Os dias a partir da
 * vigência são recalculados com o horário novo; os anteriores continuam com o que valia para eles.
 *
 * <p>Dias de um ciclo do banco de horas já fechado não mudam (o saldo daquele ciclo foi congelado): a vigência
 * precisa começar depois do último fechamento. O mesmo vale para um mês conferido.
 *
 * <p>O horário também diz a carga semanal que a pessoa deve cumprir (vazia = a soma do horário). As regras da lei
 * ({@link LeiDaJornada}) são conferidas e voltam como avisos: nada impede de salvar.
 */
@Service
public class GerenciarHorariosUseCase {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final HorarioTrabalhoRepository horarios;
    private final CicloBancoRepository ciclos;
    private final RegrasJornada regras;
    private final RecalcularJornadasUseCase recalcular;
    private final TravaDosMesesConferidos trava;
    private final LeiDaJornada lei;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public GerenciarHorariosUseCase(HorarioTrabalhoRepository horarios, CicloBancoRepository ciclos,
                                    RegrasJornada regras, RecalcularJornadasUseCase recalcular,
                                    TravaDosMesesConferidos trava, LeiDaJornada lei,
                                    ApplicationEventPublisher eventos, Clock clock) {
        this.horarios = horarios;
        this.ciclos = ciclos;
        this.regras = regras;
        this.recalcular = recalcular;
        this.trava = trava;
        this.lei = lei;
        this.eventos = eventos;
        this.clock = clock;
    }

    /** Resultado de uma alteração: as vigências atuais e quantos dias já registrados mudaram. */
    public record Alteracao(List<HorarioTrabalho> vigencias, int diasRecalculados) {
    }

    /** Uma vigência com os avisos da lei para o período em que ela vale. */
    public record Vigencia(HorarioTrabalho horario, List<AvisoLegal> avisos) {
    }

    /** As vigências (como {@link #listar}) com os avisos da lei de cada uma (as passadas não são conferidas). */
    @Transactional(readOnly = true)
    public List<Vigencia> listarComAvisos(UUID usuarioId) {
        return comAvisos(listar(usuarioId));
    }

    /** Os avisos de cada vigência, do início dela (ou de hoje, se já começou) até a próxima. */
    public List<Vigencia> comAvisos(List<HorarioTrabalho> lista) {
        LocalDate hoje = LocalDate.now(clock);
        List<Vigencia> resultado = new java.util.ArrayList<>(lista.size());
        for (int i = 0; i < lista.size(); i++) {
            HorarioTrabalho h = lista.get(i);
            LocalDate proxima = i + 1 < lista.size() ? lista.get(i + 1).vigenteDesde() : null;
            boolean passou = proxima != null && !proxima.isAfter(hoje);
            LocalDate inicio = h.vigenteDesde().isAfter(hoje) ? h.vigenteDesde() : hoje;
            resultado.add(new Vigencia(h, passou ? List.of() : lei.avaliarHorario(h, inicio, proxima)));
        }
        return resultado;
    }

    /** Confere um horário antes de salvar (a tela mostra os avisos enquanto a pessoa digita). */
    public Vigencia avaliar(UUID usuarioId, LocalDate vigenteDesde, int toleranciaMinutos,
                            Map<DayOfWeek, GradeHoraria> dias, Integer cargaSemanalSegundos) {
        LocalDate desde = vigenteDesde == null ? LocalDate.now(clock) : vigenteDesde;
        HorarioTrabalho h = new HorarioTrabalho(UUID.randomUUID(), usuarioId, desde, toleranciaMinutos, dias,
                clock.instant(), "avaliacao", cargaSemanalSegundos);
        LocalDate hoje = LocalDate.now(clock);
        LocalDate proxima = regras.horarios(usuarioId).stream().map(HorarioTrabalho::vigenteDesde)
                .filter(d -> d.isAfter(desde)).min(LocalDate::compareTo).orElse(null);
        return new Vigencia(h, lei.avaliarHorario(h, desde.isAfter(hoje) ? desde : hoje, proxima));
    }

    /** Vigências do usuário, da mais antiga para a mais recente (sem cadastro: o horário padrão). */
    @Transactional(readOnly = true)
    public List<HorarioTrabalho> listar(UUID usuarioId) {
        List<HorarioTrabalho> lista = regras.horarios(usuarioId);
        return lista.isEmpty() ? List.of(regras.padrao(usuarioId)) : lista;
    }

    /** Horário que vale hoje. */
    @Transactional(readOnly = true)
    public HorarioTrabalho vigente(UUID usuarioId) {
        return regras.horario(usuarioId, LocalDate.now(clock));
    }

    /**
     * Grava o horário a partir de {@code vigenteDesde} (uma vigência na mesma data é substituída) e recalcula os
     * dias dali em diante.
     */
    @Transactional
    public Alteracao salvar(UUID usuarioId, LocalDate vigenteDesde, int toleranciaMinutos,
                            Map<DayOfWeek, GradeHoraria> dias, String criadoPor) {
        return salvar(usuarioId, vigenteDesde, toleranciaMinutos, dias, null, criadoPor);
    }

    /** @param cargaSemanalSegundos a carga que a pessoa deve cumprir por semana (null = a soma do horário) */
    @Transactional
    public Alteracao salvar(UUID usuarioId, LocalDate vigenteDesde, int toleranciaMinutos,
                            Map<DayOfWeek, GradeHoraria> dias, Integer cargaSemanalSegundos, String criadoPor) {
        if (vigenteDesde == null) {
            throw new RegraNegocioException("HORARIO_SEM_VIGENCIA", "Informe a partir de quando o horário vale.");
        }
        if (vigenteDesde.isAfter(LocalDate.now(clock).plusYears(1))) {
            throw new RegraNegocioException("HORARIO_VIGENCIA_DISTANTE",
                    "O horário pode começar a valer em no máximo um ano.");
        }
        exigirForaDeCicloFechado(usuarioId, vigenteDesde);
        trava.exigirAbertoDesde(usuarioId, vigenteDesde);

        List<HorarioTrabalho> atuais = horarios.listarPorUsuario(usuarioId);
        if (atuais.isEmpty() && vigenteDesde.isAfter(HorarioTrabalho.DESDE_SEMPRE)) {
            // o passado continua com o horário padrão que valia até aqui
            horarios.salvar(regras.padraoParaGravar(usuarioId, criadoPor, clock.instant()));
        }
        Optional<HorarioTrabalho> mesmaData = atuais.stream()
                .filter(h -> h.vigenteDesde().equals(vigenteDesde))
                .findFirst();
        HorarioTrabalho novo = new HorarioTrabalho(mesmaData.map(HorarioTrabalho::id).orElseGet(UUID::randomUUID),
                usuarioId, vigenteDesde, toleranciaMinutos, dias, clock.instant(), criadoPor, cargaSemanalSegundos);
        horarios.salvar(novo);
        return recalcularDesde(usuarioId, vigenteDesde);
    }

    /** Remove uma vigência: os dias dela voltam para o horário anterior. A primeira não pode ser removida. */
    @Transactional
    public Alteracao excluir(UUID usuarioId, UUID horarioId) {
        HorarioTrabalho horario = horarios.buscarPorId(horarioId)
                .filter(h -> h.usuarioId().equals(usuarioId))
                .orElseThrow(() -> new RecursoNaoEncontradoException("HORARIO_NAO_ENCONTRADO",
                        "Horário não encontrado."));
        List<HorarioTrabalho> atuais = horarios.listarPorUsuario(usuarioId);
        if (!atuais.isEmpty() && atuais.get(0).id().equals(horarioId)) {
            throw new RegraNegocioException("HORARIO_INICIAL",
                    "O primeiro horário não pode ser removido: altere-o ou cadastre um novo a partir de uma data.");
        }
        exigirForaDeCicloFechado(usuarioId, horario.vigenteDesde());
        trava.exigirAbertoDesde(usuarioId, horario.vigenteDesde());
        horarios.excluir(horarioId);
        return recalcularDesde(usuarioId, horario.vigenteDesde());
    }

    private Alteracao recalcularDesde(UUID usuarioId, LocalDate desde) {
        int recalculados = recalcular.reavaliar(usuarioId, desde); // também limpa o cache de horários
        LocalDate hoje = LocalDate.now(clock);
        eventos.publishEvent(new CalendarioAlteradoEvento(usuarioId, desde, desde.isAfter(hoje) ? desde : hoje));
        return new Alteracao(listar(usuarioId), recalculados);
    }

    private void exigirForaDeCicloFechado(UUID usuarioId, LocalDate desde) {
        ciclos.listar(usuarioId).stream()
                .filter(c -> c.status() == StatusCiclo.FECHADO)
                .map(CicloBanco::dataFim)
                .max(LocalDate::compareTo)
                .filter(fim -> !desde.isAfter(fim))
                .ifPresent(fim -> {
                    throw new RegraNegocioException("HORARIO_EM_CICLO_FECHADO",
                            "O banco de horas foi fechado em %s: o horário pode mudar a partir de %s."
                                    .formatted(DATA.format(fim), DATA.format(fim.plusDays(1))));
                });
    }
}
