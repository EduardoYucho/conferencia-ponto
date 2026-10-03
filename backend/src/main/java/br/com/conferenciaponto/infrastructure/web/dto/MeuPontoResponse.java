package br.com.conferenciaponto.infrastructure.web.dto;

import br.com.conferenciaponto.application.tela.MeuPontoView;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Tela "Meu ponto": o mês dia a dia.
 *
 * @param anterior  mês para o botão "‹"
 * @param proximo   mês para o botão "›"
 * @param contagem  filtro → quantos dias (até hoje) entram nele
 */
public record MeuPontoResponse(int ano, int mes, String titulo, boolean mesAtual, Referencia anterior,
                               Referencia proximo, MeuPontoView.Totais totais, Map<String, Integer> contagem,
                               List<DiaResponse> dias, List<DiaResponse> proximos, String explicacao) {

    public record Referencia(int ano, int mes) {
        static Referencia de(YearMonth m) {
            return new Referencia(m.getYear(), m.getMonthValue());
        }
    }

    public static MeuPontoResponse de(MeuPontoView v) {
        YearMonth m = v.referencia();
        return new MeuPontoResponse(m.getYear(), m.getMonthValue(), v.titulo(), v.mesAtual(),
                Referencia.de(m.minusMonths(1)), Referencia.de(m.plusMonths(1)), v.totais(),
                v.contagem().entrySet().stream().collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue)),
                v.dias().stream().map(DiaResponse::de).toList(), v.proximos().stream().map(DiaResponse::de).toList(),
                v.explicacao());
    }
}
