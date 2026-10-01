package br.com.conferenciaponto.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Histórico (somente inclusão) de um ajuste manual das batidas de um dia.
 *
 * @param antes         horários do dia antes do ajuste (ordem cronológica)
 * @param depois        horários do dia depois do ajuste
 * @param justificativa motivo informado (ex.: "Corrigido pelo RH: falha no relógio")
 * @param usuario       login de quem ajustou
 */
public record AjusteJornada(UUID id, UUID usuarioId, UUID registroJornadaId, LocalDate data, List<LocalTime> antes,
                            List<LocalTime> depois, String justificativa, String usuario, Instant ajustadoEm) {

    public AjusteJornada {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(data, "data");
        antes = List.copyOf(antes);
        depois = List.copyOf(depois);
        Objects.requireNonNull(justificativa, "justificativa");
        Objects.requireNonNull(usuario, "usuario");
        Objects.requireNonNull(ajustadoEm, "ajustadoEm");
    }

    public static AjusteJornada novo(RegistroJornada registro, List<LocalTime> antes, String justificativa,
                                     String usuario, Instant agora) {
        return new AjusteJornada(UUID.randomUUID(), registro.getUsuarioId(), registro.getId(), registro.getDataReferencia(), antes,
                registro.getBatidas().horarios(), justificativa, usuario, agora);
    }
}
