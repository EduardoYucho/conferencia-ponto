package br.com.conferenciaponto.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Histórico (somente inclusão) de uma mudança feita à mão nas batidas de um dia.
 *
 * @param antes         horários do dia antes da mudança (ordem cronológica)
 * @param depois        horários do dia depois (vazio = o dia foi excluído)
 * @param justificativa motivo informado (ex.: "Corrigido pelo RH: falha no relógio")
 * @param usuario       login de quem mudou
 * @param origem        o que mudou o dia
 */
public record AjusteJornada(UUID id, UUID usuarioId, UUID registroJornadaId, LocalDate data, List<LocalTime> antes,
                            List<LocalTime> depois, String justificativa, String usuario, Instant ajustadoEm,
                            Origem origem) {

    /** O que mudou o dia. */
    public enum Origem {
        /** Batidas incluídas, corrigidas ou removidas no ajuste. */
        AJUSTE,
        /** "Aceitar dados do RH" na conciliação. */
        RH,
        /** Lançamento à mão de um dia sem expediente (fim de semana, feriado). */
        MANUAL,
        /** O dia foi excluído. */
        EXCLUSAO
    }

    public AjusteJornada {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(usuarioId, "usuarioId");
        Objects.requireNonNull(data, "data");
        antes = List.copyOf(antes);
        depois = List.copyOf(depois);
        Objects.requireNonNull(justificativa, "justificativa");
        Objects.requireNonNull(usuario, "usuario");
        Objects.requireNonNull(ajustadoEm, "ajustadoEm");
        origem = origem == null ? Origem.AJUSTE : origem;
    }

    public AjusteJornada(UUID id, UUID usuarioId, UUID registroJornadaId, LocalDate data, List<LocalTime> antes,
                         List<LocalTime> depois, String justificativa, String usuario, Instant ajustadoEm) {
        this(id, usuarioId, registroJornadaId, data, antes, depois, justificativa, usuario, ajustadoEm, Origem.AJUSTE);
    }

    public static AjusteJornada novo(RegistroJornada registro, List<LocalTime> antes, String justificativa,
                                     String usuario, Instant agora) {
        return novo(registro, antes, justificativa, usuario, agora, Origem.AJUSTE);
    }

    public static AjusteJornada novo(RegistroJornada registro, List<LocalTime> antes, String justificativa,
                                     String usuario, Instant agora, Origem origem) {
        return new AjusteJornada(UUID.randomUUID(), registro.getUsuarioId(), registro.getId(), registro.getDataReferencia(),
                antes, registro.getBatidas().horarios(), justificativa, usuario, agora, origem);
    }

    /** O dia foi excluído: as batidas que ele tinha somem (o histórico fica). */
    public static AjusteJornada exclusao(RegistroJornada registro, String justificativa, String usuario, Instant agora) {
        return new AjusteJornada(UUID.randomUUID(), registro.getUsuarioId(), registro.getId(), registro.getDataReferencia(),
                registro.getBatidas().horarios(), List.of(), justificativa, usuario, agora, Origem.EXCLUSAO);
    }
}
