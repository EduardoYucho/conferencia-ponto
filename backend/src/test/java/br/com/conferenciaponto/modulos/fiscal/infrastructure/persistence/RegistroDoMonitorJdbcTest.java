package br.com.conferenciaponto.modulos.fiscal.infrastructure.persistence;

import br.com.conferenciaponto.modulos.PostgresDeTeste;
import br.com.conferenciaponto.modulos.fiscal.domain.Alvo;
import br.com.conferenciaponto.modulos.fiscal.domain.Documento;
import br.com.conferenciaponto.modulos.fiscal.domain.EstadoAtual;
import br.com.conferenciaponto.modulos.fiscal.domain.Incidente;
import br.com.conferenciaponto.modulos.fiscal.domain.Leitura;
import br.com.conferenciaponto.modulos.fiscal.domain.Mudanca;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor.Aviso;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor.Disponibilidade;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor.Rodada;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor.RodadaConcluida;
import br.com.conferenciaponto.modulos.fiscal.domain.RegistroDoMonitor.SituacaoDaFonte;
import br.com.conferenciaponto.modulos.fiscal.domain.Situacao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static br.com.conferenciaponto.modulos.fiscal.domain.LeiturasDeTeste.conexao;
import static br.com.conferenciaponto.modulos.fiscal.domain.LeiturasDeTeste.geral;
import static org.assertj.core.api.Assertions.assertThat;

class RegistroDoMonitorJdbcTest {

    private static final Instant T = Instant.parse("2026-11-09T13:05:00Z");
    private RegistroDoMonitorJdbc registro;

    @BeforeEach
    void limpar() {
        registro = RegistroDeTeste.limpo();
    }

    private static RodadaConcluida rodada(Instant em, List<EstadoAtual> estados, List<Mudanca> mudancas,
                                          List<Incidente> incidentes, List<Leitura> gerais, List<SituacaoDaFonte> fontes,
                                          List<Aviso> avisos) {
        return new RodadaConcluida(new Rodada(em, em.plusSeconds(3), estados.size(), 0, false), estados, mudancas,
                incidentes, gerais, fontes, avisos);
    }

    @Test
    @DisplayName("Estado, mudança e incidente gravados e lidos de volta; o incidente fecha pelo id")
    void estadoMudancaEIncidente() {
        Leitura fora = geral(Documento.NFE, "SP", Situacao.FORA, "Autorização: fora do ar");
        EstadoAtual estado = new EstadoAtual(fora, T, T, 1);
        registro.gravar(rodada(T, List.of(estado), List.of(new Mudanca(fora.alvo(), Situacao.NORMAL, Situacao.FORA,
                fora.detalhe(), T)), List.of(Incidente.abrir(fora, T)), List.of(fora), List.of(), List.of()));

        assertThat(registro.estados()).containsOnlyKeys("NFE:SP:geral");
        assertThat(registro.estados().get("NFE:SP:geral")).isEqualTo(estado);
        Incidente aberto = registro.incidentesAbertos().get("NFE:SP:geral");
        assertThat(aberto.id()).isNotNull();
        assertThat(aberto.pior()).isEqualTo(Situacao.FORA);
        assertThat(registro.ultimaRodada()).get().satisfies(r -> assertThat(r.inicio()).isEqualTo(T));

        Instant depois = T.plus(Duration.ofMinutes(10));
        registro.gravar(rodada(depois, List.of(), List.of(), List.of(aberto.comLeitura(fora).fechado(depois)), List.of(),
                List.of(), List.of()));
        assertThat(registro.incidentesAbertos()).isEmpty();
        assertThat(registro.incidentesDesde(T.minus(Duration.ofDays(1)))).singleElement().satisfies(i -> {
            assertThat(i.fim()).isEqualTo(depois);
            assertThat(i.leituras()).isEqualTo(2);
        });
        assertThat(PostgresDeTeste.jdbc().sql("SELECT count(*) FROM fiscal.mudanca").query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Resumo por hora: soma leituras, ruins, sem informação e tempos; a disponibilidade sai em porcentagem")
    void disponibilidade() {
        List<Leitura> leituras = List.of(conexao(Documento.CTE, "MG", Situacao.NORMAL, 200),
                conexao(Documento.CTE, "MG", Situacao.NORMAL, 400), conexao(Documento.CTE, "MG", Situacao.FORA, null),
                conexao(Documento.CTE, "MG", Situacao.SEM_INFORMACAO, null), conexao(Documento.CTE, "MG", Situacao.NORMAL, 3600));
        for (int i = 0; i < leituras.size(); i++) {
            Leitura l = leituras.get(i);
            Leitura comoGeral = new Leitura(Alvo.geral(l.alvo().documento(), "MG"), l.origem(), l.situacao(), l.detalhe(),
                    null, l.tempoMs(), l.verificadoEm());
            registro.gravar(rodada(T.plus(Duration.ofMinutes(5L * i)), List.of(), List.of(), List.of(),
                    List.of(comoGeral), List.of(), List.of()));
        }

        List<Disponibilidade> d = registro.disponibilidadeDesde(T.minus(Duration.ofHours(1)));
        assertThat(d).singleElement().satisfies(x -> {
            assertThat(x.documento()).isEqualTo(Documento.CTE);
            assertThat(x.autorizador()).isEqualTo("MG");
            assertThat(x.leituras()).isEqualTo(5);
            assertThat(x.ruins()).isEqualTo(1);
            assertThat(x.semInformacao()).isEqualTo(1);
            assertThat(x.tempoMedioMs()).isEqualTo(1400);
            assertThat(x.tempoMaxMs()).isEqualTo(3600);
            assertThat(x.percentual()).as("3 de 4 leituras com informação").isEqualTo(75.0);
        });
        assertThat(PostgresDeTeste.jdbc().sql("SELECT count(*) FROM fiscal.resumo_hora").query(Integer.class).single())
                .as("tudo na mesma hora").isEqualTo(1);
        assertThat(registro.disponibilidadeDesde(T.plus(Duration.ofHours(2)))).isEmpty();
    }

    @Test
    @DisplayName("Fonte: o último sucesso fica quando a tentativa falha; aviso: o mesmo texto mantém o 'desde', sem texto sai")
    void fontesEAvisos() {
        registro.gravar(rodada(T, List.of(), List.of(), List.of(), List.of(),
                List.of(new SituacaoDaFonte("painel-svrs-nfe", T, T, null)),
                List.of(new Aviso(Documento.NFE, "Não há manutenção prevista.", T, T))));
        Instant depois = T.plusSeconds(300);
        registro.gravar(rodada(depois, List.of(), List.of(), List.of(), List.of(),
                List.of(new SituacaoDaFonte("painel-svrs-nfe", depois, null, "O portal respondeu HTTP 503.")),
                List.of(new Aviso(Documento.NFE, "Não há manutenção prevista.", depois, depois))));

        assertThat(registro.fontes()).singleElement().satisfies(f -> {
            assertThat(f.ultimaTentativa()).isEqualTo(depois);
            assertThat(f.ultimoSucesso()).isEqualTo(T);
            assertThat(f.comProblema()).isTrue();
        });
        assertThat(registro.avisos()).singleElement().satisfies(a -> {
            assertThat(a.desde()).isEqualTo(T);
            assertThat(a.vistoEm()).isEqualTo(depois);
        });

        registro.gravar(rodada(depois, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new Aviso(Documento.NFE, "Manutenção programada para domingo.", depois, depois))));
        assertThat(registro.avisos()).singleElement().satisfies(a -> assertThat(a.desde()).isEqualTo(depois));

        registro.gravar(rodada(depois, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new Aviso(Documento.NFE, null, depois, depois))));
        assertThat(registro.avisos()).isEmpty();
    }

    @Test
    @DisplayName("Limpeza: apaga resumos, mudanças, rodadas e alvos sem informação antigos; os incidentes ficam")
    void limpeza() {
        Instant antigo = T.minus(Duration.ofDays(100));
        Leitura semInfo = geral(Documento.MDFE, "SVRS", Situacao.SEM_INFORMACAO, "sumiu");
        Leitura fora = geral(Documento.NFE, "PR", Situacao.FORA, "fora");
        registro.gravar(rodada(antigo, List.of(new EstadoAtual(semInfo, antigo, antigo, 0), new EstadoAtual(fora, T, T, 1)),
                List.of(new Mudanca(fora.alvo(), null, Situacao.FORA, "fora", antigo)),
                List.of(Incidente.abrir(fora, antigo).fechado(antigo.plusSeconds(600))), List.of(fora), List.of(),
                List.of()));

        registro.limpar(T.minus(Duration.ofDays(90)), T.minus(Duration.ofDays(90)), T.minus(Duration.ofDays(7)));

        var jdbc = PostgresDeTeste.jdbc();
        assertThat(jdbc.sql("SELECT count(*) FROM fiscal.resumo_hora").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM fiscal.mudanca").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM fiscal.rodada").query(Integer.class).single()).isZero();
        assertThat(registro.estados()).containsOnlyKeys("NFE:PR:geral");
        assertThat(jdbc.sql("SELECT count(*) FROM fiscal.incidente").query(Integer.class).single()).isEqualTo(1);
    }
}
