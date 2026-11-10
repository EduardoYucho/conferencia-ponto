package br.com.conferenciaponto.modulos.fiscal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static br.com.conferenciaponto.modulos.fiscal.domain.LeiturasDeTeste.T0;
import static br.com.conferenciaponto.modulos.fiscal.domain.LeiturasDeTeste.geral;
import static org.assertj.core.api.Assertions.assertThat;

class DetectorDeIncidentesTest {

    private final DetectorDeIncidentes detector = new DetectorDeIncidentes();
    private final Map<String, EstadoAtual> estados = new HashMap<>();
    private final Map<String, Incidente> abertos = new HashMap<>();

    /** Aplica uma rodada e guarda o resultado, como o registro faria. */
    private DetectorDeIncidentes.Resultado rodada(int minutos, Leitura... leituras) {
        DetectorDeIncidentes.Resultado r = detector.aplicar(Map.copyOf(estados), Map.copyOf(abertos), List.of(leituras),
                T0.plus(Duration.ofMinutes(minutos)));
        r.estados().forEach(e -> estados.put(e.alvo().chave(), e));
        long id = 1;
        for (Incidente i : r.incidentes()) {
            Incidente gravado = i.id() == null ? new Incidente(id++, i.alvo(), i.inicio(), i.fim(), i.pior(), i.detalhe(),
                    i.leituras()) : i;
            if (gravado.aberto()) {
                abertos.put(gravado.alvo().chave(), gravado);
            } else {
                abertos.remove(gravado.alvo().chave());
            }
        }
        return r;
    }

    @Test
    @DisplayName("Uma queda abre o incidente, as leituras ruins seguintes o atualizam com a pior situação e a volta o fecha")
    void quedaEVolta() {
        DetectorDeIncidentes.Resultado primeira = rodada(0, geral(Documento.NFE, "SP", Situacao.NORMAL, "ok"));
        assertThat(primeira.mudancas()).singleElement().satisfies(m -> {
            assertThat(m.de()).isNull();
            assertThat(m.para()).isEqualTo(Situacao.NORMAL);
        });
        assertThat(primeira.incidentes()).isEmpty();

        DetectorDeIncidentes.Resultado instavel = rodada(5, geral(Documento.NFE, "SP", Situacao.INSTAVEL, "lento"));
        assertThat(instavel.incidentes()).singleElement().satisfies(i -> {
            assertThat(i.id()).isNull();
            assertThat(i.inicio()).isEqualTo(T0.plusSeconds(300));
            assertThat(i.pior()).isEqualTo(Situacao.INSTAVEL);
        });
        assertThat(estados.get("NFE:SP:geral").ruinsSeguidas()).isEqualTo(1);

        DetectorDeIncidentes.Resultado fora = rodada(10, geral(Documento.NFE, "SP", Situacao.FORA, "autorização fora"));
        assertThat(fora.incidentes()).singleElement().satisfies(i -> {
            assertThat(i.id()).isEqualTo(1L);
            assertThat(i.pior()).isEqualTo(Situacao.FORA);
            assertThat(i.detalhe()).isEqualTo("autorização fora");
            assertThat(i.leituras()).isEqualTo(2);
        });
        assertThat(estados.get("NFE:SP:geral").ruinsSeguidas()).isEqualTo(2);

        // voltou a instável: o pior continua sendo "fora", com o que a fonte disse na hora
        rodada(15, geral(Documento.NFE, "SP", Situacao.INSTAVEL, "lento de novo"));
        assertThat(abertos.get("NFE:SP:geral").pior()).isEqualTo(Situacao.FORA);
        assertThat(abertos.get("NFE:SP:geral").detalhe()).isEqualTo("autorização fora");

        DetectorDeIncidentes.Resultado volta = rodada(20, geral(Documento.NFE, "SP", Situacao.NORMAL, "ok"));
        assertThat(volta.incidentes()).singleElement().satisfies(i -> {
            assertThat(i.fim()).isEqualTo(T0.plusSeconds(1200));
            assertThat(i.duracao(Instant.MAX)).isEqualTo(Duration.ofMinutes(15));
            assertThat(i.leituras()).isEqualTo(3);
        });
        assertThat(abertos).isEmpty();
        assertThat(estados.get("NFE:SP:geral").ruinsSeguidas()).isZero();
    }

    @Test
    @DisplayName("Sem informação não abre nem fecha incidente e não zera a contagem de leituras ruins")
    void semInformacaoEhUmBuraco() {
        rodada(0, geral(Documento.CTE, "MG", Situacao.FORA, "fora"));
        assertThat(abertos).containsKey("CTE:MG:geral");

        DetectorDeIncidentes.Resultado buraco = rodada(5, geral(Documento.CTE, "MG", Situacao.SEM_INFORMACAO, "sem internet"));
        assertThat(buraco.incidentes()).isEmpty();
        assertThat(abertos).containsKey("CTE:MG:geral");
        assertThat(estados.get("CTE:MG:geral").ruinsSeguidas()).isEqualTo(1);
        assertThat(buraco.mudancas()).singleElement().satisfies(m -> assertThat(m.para()).isEqualTo(Situacao.SEM_INFORMACAO));

        rodada(10, geral(Documento.CTE, "MG", Situacao.FORA, "fora"));
        assertThat(estados.get("CTE:MG:geral").ruinsSeguidas()).isEqualTo(2);
    }

    @Test
    @DisplayName("O 'desde' muda só quando a situação muda; serviços (não gerais) nunca abrem incidente")
    void desdeEServicos() {
        rodada(0, LeiturasDeTeste.servico(Documento.NFE, "RS", "Autorização", Situacao.NORMAL));
        DetectorDeIncidentes.Resultado mesma = rodada(5, LeiturasDeTeste.servico(Documento.NFE, "RS", "Autorização",
                Situacao.NORMAL));
        assertThat(mesma.mudancas()).isEmpty();
        assertThat(estados.get("NFE:RS:Autorização").desde()).isEqualTo(T0);
        assertThat(estados.get("NFE:RS:Autorização").lidaEm()).isEqualTo(T0.plusSeconds(300));

        DetectorDeIncidentes.Resultado caiu = rodada(10, LeiturasDeTeste.servico(Documento.NFE, "RS", "Autorização",
                Situacao.FORA));
        assertThat(caiu.incidentes()).isEmpty();
        assertThat(estados.get("NFE:RS:Autorização").desde()).isEqualTo(T0.plusSeconds(600));
    }
}
