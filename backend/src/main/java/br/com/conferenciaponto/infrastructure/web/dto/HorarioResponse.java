package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.usecase.GerenciarHorariosUseCase;
import br.com.conferenciaponto.domain.model.GradeHoraria;
import br.com.conferenciaponto.domain.model.HorarioTrabalho;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Horário de trabalho com vigência.
 *
 * @param dias                    SEG..DOM -> "08:00-12:00 13:00-17:48" (null = sem expediente)
 * @param cargaSemanalSegundos    a carga que vale: a informada ou, sem ela, a soma do horário
 * @param somaSemanalSegundos     o que o horário soma na semana
 * @param cargaInformada          a carga foi informada (senão, é a soma do horário)
 * @param diferencaSemanalSegundos a carga menos o horário (positivo = falta horário: o fim de semana compensa)
 * @param diferencaPorDiaSegundos a diferença dividida pelos dias com expediente (o que cada dia útil leva ao banco)
 * @param padrao                  horário padrão da configuração (o usuário ainda não cadastrou o dele)
 * @param avisos                  o que esbarra na lei no período em que o horário vale (vazio nos que já passaram)
 */
public record HorarioResponse(UUID id, LocalDate vigenteDesde, int toleranciaMinutos, Map<String, String> dias,
                              int cargaSemanalSegundos, int somaSemanalSegundos, boolean cargaInformada,
                              int diferencaSemanalSegundos, int diferencaPorDiaSegundos, int diasComExpediente,
                              Instant criadoEm, String criadoPor, boolean padrao, List<AvisoLegalResponse> avisos) {

    public static HorarioResponse de(HorarioTrabalho h) {
        return de(h, List.of());
    }

    public static HorarioResponse de(GerenciarHorariosUseCase.Vigencia v) {
        return de(v.horario(), AvisoLegalResponse.de(v.avisos()));
    }

    private static HorarioResponse de(HorarioTrabalho h, List<AvisoLegalResponse> avisos) {
        Map<String, String> dias = new LinkedHashMap<>();
        for (DayOfWeek d : DayOfWeek.values()) {
            GradeHoraria grade = h.dias().get(d);
            dias.put(HorarioRequest.SIGLAS.get(d), grade == null ? null : grade.texto());
        }
        boolean padrao = Instant.EPOCH.equals(h.criadoEm()) && "sistema".equals(h.criadoPor());
        return new HorarioResponse(padrao ? null : h.id(), h.vigenteDesde(), h.toleranciaMinutos(), dias,
                h.cargaSemanalSegundos(), h.somaSemanalSegundos(), h.cargaSemanalInformadaSegundos() != null,
                h.diferencaSemanalSegundos(), h.diferencaPorDiaSegundos(), h.diasComExpediente(),
                padrao ? null : h.criadoEm(), padrao ? null : h.criadoPor(), padrao, avisos);
    }
}
