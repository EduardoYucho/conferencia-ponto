package br.com.conferenciaponto.modulos.fiscal.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * O resumo de cada autorizador ({@link Alvo#GERAL}), a partir dos serviços dele lidos na rodada:
 * <ul>
 *   <li>fora, se a autorização está fora (ou se o teste de conexão não teve resposta);</li>
 *   <li>instável, se qualquer outro serviço está instável ou fora (consulta, eventos, inutilização...);</li>
 *   <li>sem informação, se nenhum serviço trouxe informação;</li>
 *   <li>normal, no resto.</li>
 * </ul>
 */
public final class ResumoDoAutorizador {

    private ResumoDoAutorizador() {
    }

    public static List<Leitura> resumir(List<Leitura> servicos) {
        Map<Alvo, List<Leitura>> porAutorizador = new LinkedHashMap<>();
        for (Leitura l : servicos) {
            if (!l.alvo().ehGeral()) {
                porAutorizador.computeIfAbsent(l.alvo().doAutorizador(), a -> new ArrayList<>()).add(l);
            }
        }
        List<Leitura> gerais = new ArrayList<>();
        porAutorizador.forEach((geral, lidas) -> gerais.add(resumo(geral, lidas)));
        return gerais;
    }

    private static Leitura resumo(Alvo geral, List<Leitura> lidas) {
        Origem origem = lidas.get(0).origem();
        Instant verificado = lidas.stream().map(Leitura::verificadoEm).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        Integer tempo = lidas.stream().map(Leitura::tempoMs).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        List<Leitura> comInformacao = lidas.stream().filter(l -> l.situacao() != Situacao.SEM_INFORMACAO).toList();
        if (comInformacao.isEmpty()) {
            return new Leitura(geral, origem, Situacao.SEM_INFORMACAO, lidas.get(0).detalhe(), null, null, verificado);
        }
        if (lidas.size() == 1) {
            Leitura unica = lidas.get(0);
            return new Leitura(geral, origem, unica.situacao(), unica.detalhe(), unica.cstat(), unica.tempoMs(),
                    unica.verificadoEm());
        }
        List<Leitura> ruins = comInformacao.stream().filter(l -> l.situacao().ruim()).toList();
        if (ruins.isEmpty()) {
            return new Leitura(geral, origem, Situacao.NORMAL, "Todos os serviços normais", null, tempo, verificado);
        }
        boolean autorizacaoFora = ruins.stream()
                .anyMatch(l -> l.situacao() == Situacao.FORA && l.alvo().servico().startsWith("Autorização"));
        String detalhe = ruins.stream().map(l -> l.alvo().servico() + ": " + (l.detalhe() == null ? l.situacao() : l.detalhe()))
                .collect(Collectors.joining("; "));
        return new Leitura(geral, origem, autorizacaoFora ? Situacao.FORA : Situacao.INSTAVEL, detalhe, null, tempo,
                verificado);
    }
}
