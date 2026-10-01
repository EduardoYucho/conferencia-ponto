package br.com.conferenciaponto.application;

import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;
import br.com.conferenciaponto.domain.model.RegistroJornada;
import br.com.conferenciaponto.domain.model.TipoDia;
import br.com.conferenciaponto.domain.port.HorarioTrabalhoRepository;
import br.com.conferenciaponto.domain.service.ClassificadorDiaService;
import br.com.conferenciaponto.domain.service.MotorCalculoJornadaService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Regras de cálculo de cada usuário numa data: o horário de trabalho vigente (grade do dia da semana e
 * tolerância), o motor de cálculo correspondente e a classificação do dia (útil, sem expediente, feriado,
 * ausência). Quem ainda não cadastrou horário usa o horário padrão da configuração (seg–sex).
 */
public class RegrasJornada {

    private final HorarioTrabalhoRepository horarios;
    private final ClassificadorDiaService classificador;
    private final GradeHoraria gradePadrao;
    private final int toleranciaPadrao;
    /** Horários por usuário (poucos e raramente alterados): evita uma consulta por dia recalculado. */
    private final Map<UUID, List<HorarioTrabalho>> cache = new ConcurrentHashMap<>();

    public RegrasJornada(HorarioTrabalhoRepository horarios, ClassificadorDiaService classificador,
                         GradeHoraria gradePadrao, int toleranciaPadrao) {
        this.horarios = Objects.requireNonNull(horarios, "horarios");
        this.classificador = Objects.requireNonNull(classificador, "classificador");
        this.gradePadrao = Objects.requireNonNull(gradePadrao, "gradePadrao");
        this.toleranciaPadrao = toleranciaPadrao;
    }

    /** Horário que vale para o usuário na data (o primeiro cadastrado vale também para antes dele). */
    public HorarioTrabalho horario(UUID usuarioId, LocalDate data) {
        List<HorarioTrabalho> lista = horarios(usuarioId);
        if (lista.isEmpty()) {
            return padrao(usuarioId);
        }
        HorarioTrabalho vigente = lista.get(0);
        for (HorarioTrabalho h : lista) {
            if (!h.vigenteDesde().isAfter(data)) {
                vigente = h;
            }
        }
        return vigente;
    }

    /** Vigências do usuário, da mais antiga para a mais recente (vazia = horário padrão). */
    public List<HorarioTrabalho> horarios(UUID usuarioId) {
        return cache.computeIfAbsent(usuarioId, id -> List.copyOf(horarios.listarPorUsuario(id)));
    }

    /** Horário padrão da configuração (seg–sex), valendo desde sempre. */
    public HorarioTrabalho padrao(UUID usuarioId) {
        return HorarioTrabalho.semanal(usuarioId, HorarioTrabalho.DESDE_SEMPRE, gradePadrao, toleranciaPadrao,
                "sistema", Instant.EPOCH);
    }

    /** O horário padrão como primeira vigência do usuário (gravada no cadastro). */
    public HorarioTrabalho padraoParaGravar(UUID usuarioId, String criadoPor, Instant agora) {
        return HorarioTrabalho.semanal(usuarioId, HorarioTrabalho.DESDE_SEMPRE, gradePadrao, toleranciaPadrao,
                criadoPor, agora);
    }

    public MotorCalculoJornadaService motor(UUID usuarioId, LocalDate data) {
        return horario(usuarioId, data).motor(data);
    }

    public MotorCalculoJornadaService motor(RegistroJornada registro) {
        return motor(registro.getUsuarioId(), registro.getDataReferencia());
    }

    public TipoDia classificar(UUID usuarioId, LocalDate data) {
        return classificador.classificar(usuarioId, data, horario(usuarioId, data));
    }

    /** O horário do usuário mudou: a próxima consulta relê do banco. */
    public void invalidar(UUID usuarioId) {
        cache.remove(usuarioId);
    }
}
