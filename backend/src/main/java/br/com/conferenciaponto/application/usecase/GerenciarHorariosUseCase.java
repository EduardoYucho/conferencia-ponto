package br.com.conferenciaponto.application.usecase;

import br.com.conferenciaponto.application.RegrasJornada;
import br.com.conferenciaponto.application.evento.CalendarioAlteradoEvento;
import br.com.conferenciaponto.domain.exception.RecursoNaoEncontradoException;
import br.com.conferenciaponto.domain.exception.RegraNegocioException;
import br.com.conferenciaponto.domain.model.CicloBanco;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.StatusCiclo;
import br.com.conferenciaponto.domain.port.CicloBancoRepository;
import br.com.conferenciaponto.domain.port.HorarioTrabalhoRepository;
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
 * precisa começar depois do último fechamento.
 */
@Service
public class GerenciarHorariosUseCase {

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final HorarioTrabalhoRepository horarios;
    private final CicloBancoRepository ciclos;
    private final RegrasJornada regras;
    private final RecalcularJornadasUseCase recalcular;
    private final ApplicationEventPublisher eventos;
    private final Clock clock;

    public GerenciarHorariosUseCase(HorarioTrabalhoRepository horarios, CicloBancoRepository ciclos,
                                    RegrasJornada regras, RecalcularJornadasUseCase recalcular,
                                    ApplicationEventPublisher eventos, Clock clock) {
        this.horarios = horarios;
        this.ciclos = ciclos;
        this.regras = regras;
        this.recalcular = recalcular;
        this.eventos = eventos;
        this.clock = clock;
    }

    /** Resultado de uma alteração: as vigências atuais e quantos dias já registrados mudaram. */
    public record Alteracao(List<HorarioTrabalho> vigencias, int diasRecalculados) {
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
        if (vigenteDesde == null) {
            throw new RegraNegocioException("HORARIO_SEM_VIGENCIA", "Informe a partir de quando o horário vale.");
        }
        if (vigenteDesde.isAfter(LocalDate.now(clock).plusYears(1))) {
            throw new RegraNegocioException("HORARIO_VIGENCIA_DISTANTE",
                    "O horário pode começar a valer em no máximo um ano.");
        }
        exigirForaDeCicloFechado(usuarioId, vigenteDesde);

        List<HorarioTrabalho> atuais = horarios.listarPorUsuario(usuarioId);
        if (atuais.isEmpty() && vigenteDesde.isAfter(HorarioTrabalho.DESDE_SEMPRE)) {
            // o passado continua com o horário padrão que valia até aqui
            horarios.salvar(regras.padraoParaGravar(usuarioId, criadoPor, clock.instant()));
        }
        Optional<HorarioTrabalho> mesmaData = atuais.stream()
                .filter(h -> h.vigenteDesde().equals(vigenteDesde))
                .findFirst();
        HorarioTrabalho novo = new HorarioTrabalho(mesmaData.map(HorarioTrabalho::id).orElseGet(UUID::randomUUID),
                usuarioId, vigenteDesde, toleranciaMinutos, dias, clock.instant(), criadoPor);
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
