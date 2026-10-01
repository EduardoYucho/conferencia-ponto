package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Horário de trabalho com vigência.
 *
 * @param dias           SEG..DOM -> "08:00-12:00 13:00-17:48" (null = sem expediente)
 * @param cargaSemanalSegundos soma das cargas dos dias com expediente
 * @param padrao         horário padrão da configuração (o usuário ainda não cadastrou o dele)
 */
public record HorarioResponse(UUID id, LocalDate vigenteDesde, int toleranciaMinutos, Map<String, String> dias,
                              int cargaSemanalSegundos, Instant criadoEm, String criadoPor, boolean padrao) {

    public static HorarioResponse de(HorarioTrabalho h) {
        Map<String, String> dias = new LinkedHashMap<>();
        int carga = 0;
        for (DayOfWeek d : DayOfWeek.values()) {
            GradeHoraria grade = h.dias().get(d);
            dias.put(HorarioRequest.SIGLAS.get(d), grade == null ? null : grade.texto());
            carga += grade == null ? 0 : grade.cargaHorariaSegundos();
        }
        boolean padrao = Instant.EPOCH.equals(h.criadoEm()) && "sistema".equals(h.criadoPor());
        return new HorarioResponse(padrao ? null : h.id(), h.vigenteDesde(), h.toleranciaMinutos(), dias, carga,
                padrao ? null : h.criadoEm(), padrao ? null : h.criadoPor(), padrao);
    }
}
